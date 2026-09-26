# Hashtag Module — Data Rules

**Implementation status**: Fully implemented.
Caption hashtag extraction and normalization at publish time, upsert-on-first-use, removal on unpublish or soft delete, trending snapshot generation, Elasticsearch-backed search with a PostgreSQL trigram fallback, and the administrative lifecycle that lets a term be taken out of circulation are all in place.

---

## Section 1: Canonical Data

| Table | Key Columns | Notes |
|-------|-------------|-------|
| `hashtags` | `id`, `name`, `post_count`, `status`, `status_note`, `status_at`, `status_by`, `created_at` | Canonical hashtag registry. `name` is stored without the `#` prefix. Created on first use, always as `active`. The four status columns record an administrator's lifecycle decision and who took it. |
| `post_hashtags` | `post_id`, `hashtag_id`, `created_at` | Junction table associating posts to hashtags. Canonical many-to-many relationship. |
| `hashtag_trending` | `hashtag_id`, `period_start`, `period_end`, `post_count`, `rank` | Periodic trending snapshots. Populated by a background scheduler. Not real-time. |

These tables cannot be rebuilt from any other source if lost.

---

## Section 2: Derived Data / Cache / Projection

| Data | Location | Rebuilt From | Rebuild Trigger |
|------|----------|--------------|-----------------|
| `hashtags.post_count` | `hashtags` table | `COUNT(*)` from `post_hashtags` where `hashtag_id = hashtag.id` | Trigger `trg_hashtag_post_count` (V16) |
| `hashtag_trending` rows | `hashtag_trending` table | Aggregated from `post_hashtags` over a time window by background job | Scheduled background job |
| Hashtag search index | GIN index `idx_hashtags_name_trgm` | Rebuild by reindexing `hashtags.name` | Automatic on write |

---

## Section 3: Business Rules

### A. Rules Enforced by the Database

| Rule | Enforced By |
|------|-------------|
| `hashtags.name` must be unique (case-sensitive as stored) | `UNIQUE NOT NULL` on `hashtags.name` |
| `hashtags.status` must be one of `'active'`, `'banned'`, `'deleted'`; defaults to `'active'` | `hashtag_status` enum, `DEFAULT 'active'` (V67) |
| `status_by` becomes NULL if the deciding administrator's account is deleted | `ON DELETE SET NULL` on `status_by` FK |
| `hashtags.name` max 100 characters | `VARCHAR(100)` |
| `hashtags.post_count` is non-negative | `CHECK (post_count >= 0)` |
| `post_hashtags` allows at most one association per (post, hashtag) pair | Compound `PRIMARY KEY (post_id, hashtag_id)` |
| Deleting a post cascades to its `post_hashtags` rows (and decrements `hashtags.post_count` via trigger) | `ON DELETE CASCADE` on `post_hashtags.post_id` |
| Deleting a hashtag cascades to its `post_hashtags` rows and `hashtag_trending` rows | `ON DELETE CASCADE` on FK references |
| `hashtag_trending` PK is `(hashtag_id, period_start)` — one trending record per hashtag per period | Compound `PRIMARY KEY` |

### B. Rules Enforced by Application Code

| Rule | Service / Component |
|------|---------------------|
| Hashtags are extracted from post `caption` at publish time using `#word` parsing | `PostServiceImpl.extractHashtags` |
| Hashtag names must be normalized (lowercase, trimmed) before lookup or insert | `HashtagServiceImpl.normalize` |
| A hashtag row is created if it does not exist (`INSERT ... ON CONFLICT DO NOTHING` or upsert) when a post uses it | `HashtagRepository.upsertByName` |
| When a post is unpublished or soft-deleted, its `post_hashtags` rows must be deleted (triggering `post_count` decrement) | `HashtagServiceImpl.removeHashtagsForPost`, called from `PostServiceImpl` on archive and on soft delete |
| Hashtag search uses the `idx_hashtags_name_trgm` GIN index for fuzzy matching, narrowed to `status = 'active'` | `HashtagRepository.searchByNameTrgm`, the PostgreSQL fallback `HashtagSearchServiceImpl` degrades to when Elasticsearch is unavailable; Elasticsearch is the primary path. There is no separate autocomplete endpoint, so `GET /hashtags/search` is the whole of that surface |
| The trending background job writes to `hashtag_trending` with a `(period_start, period_end)` window and a computed `rank`, counting only hashtags whose `status` is `active` | `HashtagTrendingServiceImpl.runTrendingJob` / `snapshotTrending` |
| Taking a hashtag out of circulation deletes its `hashtag_trending` rows in the same transaction as the status change | `HashtagLifecycleServiceImpl.changeStatus` - a hashtag is usually banned in reaction to something happening right now, which is exactly when it is at the top of the trending list, so waiting for the next job cycle leaves it there for the worst possible hour. The job filter above is the second half of the same rule: it stops the next snapshot putting the tag straight back |
| A status change enqueues the ordinary index-sync event, and the consumer resolves it against current state | `HashtagLifecycleServiceImpl.changeStatus`, `HashtagIndexSyncConsumer` - the envelope carries the hashtag id only and the consumer reads `status` and `post_count` from the database, so an upsert event becomes an index delete the moment the row says the tag has left circulation. No event type was added for this |
| `upsertByName` must never resurrect a hashtag an administrator has taken out of circulation | `HashtagRepository.upsertByName` - `ON CONFLICT (name) DO NOTHING` leaves the existing row untouched, so first-use traffic cannot write over the decision. Pinned by `HashtagRepositoryIT`, because the danger is a later change to `DO UPDATE` |
| The administrative create must report a conflict rather than silently succeed | `HashtagLifecycleServiceImpl.create` - an ordinary JPA save with no `ON CONFLICT` clause, flushed inside the call so the unique index on `name` answers as a conflict rather than as a late failure at commit. This is the deliberate opposite of `upsertByName`, and for the same reason: first-use traffic must not disturb an existing row, while an administrator creating a name somebody already owns must be told |
| A post write path may not associate a banned hashtag | `HashtagService.findBannedNames` answers a whole caption in one statement; `upsertHashtagsForPost` refuses outright, and `upsertHashtagsForPostSkippingBanned` is the single entry point that associates the rest and reports what it skipped. See `post/DATA_RULES.md` for where each is called |
| The administrative registry reads span every status | `HashtagRepositoryCustom` - the inversion of the `status = 'active'` predicate every public read carries is contained to that one interface, so no public read path can pick up an unfiltered query by accident |

**Normalization before insert**:
- `hashtags.name` is stored lowercase. The DB UNIQUE constraint is case-sensitive.
- The Service layer MUST normalize hashtag names to lowercase before any insert or lookup.
- Names longer than 100 characters (the `hashtags.name` column bound) are dropped during normalization, not truncated, so a truncated prefix never aliases a legitimately distinct shorter tag.
- Failure to normalize before insert will result in duplicate hashtags differing only by case, bypassing the uniqueness guarantee.
- Rule owner: `HashtagService` — normalize to lowercase before `findByName` or `save`.

### C. Scope Simplifications

- `hashtag_trending` is populated by a periodic batch job; trending data may be minutes or hours stale.
- No real-time trending calculation.
- No hashtag following (users cannot subscribe to a hashtag).
- The administrative listing with no status filter has no index. It orders by `created_at` alone, which `idx_hashtags_status_created` cannot serve because that index leads with `status`. Measured at 14 ms against 200,000 rows: a parallel sequential scan plus a top-N heapsort. `users` has the same gap for the same reason, so closing it here alone would be inconsistent.

---

### D. The Lifecycle, and the Line It Does Not Cross

`hashtag_status` has three values.
No transition between two different values is forbidden; a move to the state already held is refused with `ADMIN_INVALID_TRANSITION`, because there is nothing to record.

| State | Reached by | Effect |
|-------|-----------|--------|
| `active` | first use, or an administrator returning a tag to circulation | discoverable everywhere, accepted on every write |
| `banned` | an administrator | absent from hashtag search, from trending and from the search index; refused on every post write path with `422 POST_BANNED_HASHTAG` |
| `deleted` | an administrator | as `banned`, and additionally left out of the hashtag list on a post response |

**Deleting is never a row delete.**
`DELETE /api/v1/admin/hashtags/{hashtagId}` sets the status.
Removing the row would cascade to `post_hashtags` and drive `trg_hashtag_post_count` over every post that used the tag, rewriting history nothing asked to rewrite, and it could not be undone.

**Banning hides the tag, never the posts.**
The status filter belongs on hashtag surfaces only: hashtag search, trending, the search index, and the hashtag list on a post.
It must never reach the post feed, the profile listing, post search hydration, or any other query that filters posts.
A post carrying a banned tag keeps appearing everywhere it appeared before, and its caption keeps the literal `#tag` text in every case.
An implementation that adds a status predicate to a post query is wrong however it is worded, and should be rejected in review.

The two are different decisions.
Banning a term says the term should stop being a way to find things.
It does not say every post that ever used it is in breach, and a moderator that wants a particular post gone has `remove_post` for exactly that.

### E. The Detail Surface: Name Resolution and Posts by Hashtag

Two reads back the hashtag detail page.
`GET /api/v1/hashtags/name/{name}` resolves a name to its record, and `GET /api/v1/hashtags/{hashtagId}/posts` lists the published posts carrying it.

**Read-side normalization must be the write-side function.**
`HashtagLookupServiceImpl.getByName` calls `HashtagService.normalize`, the same function `PostServiceImpl`'s caption extraction runs before insert.
It is delegated, never reimplemented: a second normalizer drifts from the first silently, and the failure it produces is a hashtag that is reachable on write and unreachable on read.
`HashtagSearchServiceImpl.normalizeQuery` is a pre-existing private duplicate of that logic, differing in that it applies no length cap; it is left alone here because changing the search surface was out of scope, but it should be collapsed into `HashtagService.normalize`.

**An out-of-circulation hashtag is refused, never answered empty.**
Both reads raise `HASHTAG_UNAVAILABLE` (404) for a `banned` or `deleted` row, distinct from `HASHTAG_NOT_FOUND` (404) for a name or id that matches nothing.
Returning `200` with an empty page would make "this hashtag is not available" indistinguishable from "this hashtag has no posts yet", and those two states have to read differently to a user.
The second state is real and reachable: a zero-post `active` hashtag exists in the seeded dataset and is absent from the Elasticsearch index by the rule in `HashtagIndexSyncConsumer`, which indexes only hashtags carrying live posts.

**The posts read carries no hashtag status predicate.**
The lifecycle gate is applied once, to the hashtag, before the post query runs.
It never becomes a filter on the post query itself, per section D: banning a term hides the term, not the posts that used it.

**Degradation.**
Elasticsearch is the primary path, a `post_hashtags` join the fallback, gated by the same `elasticsearchSearch` circuit breaker the search surfaces use.
Unlike post caption search, this read does not set `degraded`: `post_hashtags` is the source of truth for hashtag membership, so the PostgreSQL answer is complete rather than empty, and only its ranking source differs.
Both tiers order by `(created_at, id)` descending and page on the same `OffsetCursorCodec` cursor, so a cursor issued by one tier stays valid when the next request is served by the other.
A keyset cursor was rejected for that reason: Elasticsearch cannot resume from a `(created_at, id)` tuple the way the PostgreSQL query can, so the two tiers would need incompatible cursor formats and a mid-pagination degradation would break the client's paging.

**Pagination depth is bounded at a window of 10,000.**
`offset + limit + 1` (the over-fetch included) may not exceed 10,000, and a request that would is refused with `PAGINATION_DEPTH_EXCEEDED` (400), distinct from `INVALID_CURSOR` (400), which means the cursor itself is malformed.
The number is Elasticsearch's own default `index.max_result_window`, which neither settings file under `src/main/resources/elasticsearch/settings/` overrides.
A lower bound would refuse pages Elasticsearch can serve; a higher one would hand it a window it rejects.
The PostgreSQL fallback wants the same bound for an unrelated reason: `OFFSET 10000` on the join is a linear skip of ten thousand rows.

The guard is not cosmetic.
Left to fail naturally, an over-deep request surfaces as `UncategorizedElasticsearchException: search_phase_execution_exception`, whose class name begins with `org.springframework.data.elasticsearch.`, which is exactly what the availability classifier treats as an outage.
The request would therefore degrade silently to a linear PostgreSQL skip **and** record a failure against the `elasticsearchSearch` circuit breaker, which is shared with `HashtagSearchServiceImpl.search` and `PostSearchServiceImpl.searchPosts` and configures no `ignoreExceptions`.
A client paging deep could open a breaker that degrades two unrelated search surfaces.
For the same reason the hashtag lifecycle refusal is raised **before** the breaker-wrapped call, not inside it: a banned hashtag is a caller error, not an Elasticsearch outage, and must not be charged to the breaker.
Everything that can fail for a reason other than Elasticsearch being unwell therefore lives in `PostByHashtagServiceImpl`, and `PostByHashtagSearchReader` holds the breaker and nothing else.

In practice the bound is far out of reach: the largest hashtag in the seeded dataset carries 40 posts, and at the default page size of 20 the cap is page 500.

**A zero-post hashtag cannot be offered by a surface that cannot then serve it.**
Three surfaces can name a hashtag, and none of them can name one the detail page then fails on:

| Surface | Backed by | Excludes zero-post tags because |
|---|---|---|
| Hashtag search | Elasticsearch `hashtags` index | `HashtagIndexSyncConsumer` indexes only `active` tags carrying live posts |
| Trending | `hashtag_trending` in PostgreSQL | the job aggregates `FROM post_hashtags ... GROUP BY hashtag_id`, so a tag with no rows produces no group; it additionally filters `h.status = 'active'` |
| Composer suggestions and personalised trending | platform trending plus `user_hashtag_affinity` | affinity derives from `user_events` joined to `post_hashtags`, so it too requires at least one association, and banned or deleted tags are excluded at write time |

Trending is **not** subject to the Elasticsearch rule at all - it reads PostgreSQL - so its exclusion is independent and structural rather than inherited.
Verified against the seeded dataset: zero `hashtag_trending` rows reference a non-active or zero-post hashtag.

The one reachable gap is benign and is a staleness window, not an unreachable tag.
A hashtag can be trending and have its posts removed before the next job cycle, leaving a snapshot row whose tag now has `post_count = 0`.
Clicking it resolves normally and renders the "no posts yet" state rather than failing, because `GET /hashtags/name/{name}` reads live PostgreSQL rows and answers for any `active` tag regardless of post count.
A tag that is *banned* while trending is purged from `hashtag_trending` in the same transaction, so that case cannot produce a dead link either.

**Transaction boundary.**
The PostgreSQL fallback lives in its own bean, `PostByHashtagPostgresReader`.
A Resilience4j fallback runs outside the `@Transactional` boundary of the method it covers, so assembling a post response there hits the lazy `Post.media` collection with no Hibernate session.
Self-invocation would not restore the boundary either. Crossing a bean boundary is what puts the transaction interceptor back in the path.

### F. Trending: Platform and Personalised

Two surfaces, one response shape, so the client renders both with one component.

`GET /api/v1/hashtags/trending` is the platform snapshot, unchanged except that it now carries `pinned` and `source`, and orders pinned hashtags first.
`GET /api/v1/hashtags/trending/for-you` is the personalised list.

**A pin leads the whole list, not the page it lands on.**
The ordering is applied in the database (`ORDER BY (h.pinned_at IS NULL), t.rank ASC, t.hashtag_id DESC`), because sorting an already-paged result would only float a pinned hashtag to the top of whichever page it already fell on.

**The blend is on rank, never on the two scores directly.**
This is the load-bearing rule of the personalised surface.
An affinity score is a share of one user's own decayed total, so a user with three hashtags scores about 0.33 on each while a user with 134 scores about 0.007 on each.
A trending score is a post count over a window.
Those are not the same unit, and combining them numerically would rank by how broad a user's interests happen to be rather than by what those interests are.

Reciprocal rank fusion is used instead: each hashtag scores `w / (60 + rank)` in each list it appears in, summed across lists.
The constant 60 flattens the gap between adjacent top positions; without it rank 1 would score twice rank 2 and whichever list ranked a hashtag first would dominate outright.
Platform weight is 1.0 and affinity weight 1.5, so the caller's own interests lead without erasing the platform signal.
Ties break on hashtag id, giving a total order, so the list cannot reshuffle between two calls.

**Novelty is reserved, not incidental.**
Three slots in ten are held for hashtags adjacent to the caller's interests but not among them.
Adjacency is co-occurrence: hashtags appearing on the same posts as hashtags the caller engages with, excluding any the caller already has an affinity row for.
Co-occurrence rather than raw popularity, because popularity would surface the same handful of platform-wide hashtags to every caller, which is exactly what the platform tab already shows.
Reserved slots are backfilled from the blend when the adjacency pool is thin, so reserving them can never shorten a page.

**The personalised tab is never empty.**
A caller with no affinity rows silently receives the platform list.
That is the normal cold-start state, not an error.

**Caching.**
Redis, key `hashtag:trending:personalised:{userId}:{page}:{size}`, TTL 10 minutes.
Both inputs move on scheduled job cycles rather than on requests, so a shorter TTL would re-run the fusion over data that cannot have changed.
Cache failure is swallowed and the list is computed from PostgreSQL: Redis is a cache tier here, and failing the request would turn an optional accelerator into a hard dependency.

**Caller errors never reach a circuit breaker.**
Neither trending surface is behind one, and neither introduces one.
Where a breaker does gate a hashtag read - the posts-by-hashtag Elasticsearch path - the lifecycle refusal and the depth guard are applied before the breaker-wrapped call, for the reason given in section 3E.

### G. Administrative Pin

`pinned_at` and `pinned_by` on `hashtags`, added by V92; `pinned_at` doubles as the flag, matching the tombstone columns elsewhere in this schema, so there is no way to hold one without the other.

| Rule | Where |
|---|---|
| Pinning a `banned` or `deleted` hashtag is refused with `HASHTAG_UNAVAILABLE` | `HashtagLifecycleServiceImpl.pin` |
| Pinning an already-pinned hashtag is refused with `ADMIN_INVALID_TRANSITION` | `HashtagLifecycleServiceImpl.pin` |
| Unpinning a hashtag that is not pinned is refused with `ADMIN_INVALID_TRANSITION` | `HashtagLifecycleServiceImpl.unpin` |
| Taking a hashtag out of circulation clears any pin | `HashtagLifecycleServiceImpl.changeStatus` |

The pin is cleared inside `changeStatus` rather than in the admin layer, so no future caller of that method can forget it.
Leaving a pin on a banned hashtag would keep promoting the exact term an administrator acted to suppress.

`admin_action_type` gains `pin_hashtag` and `unpin_hashtag` (V93) with their `moderation_action_configs` rows (V94), added as a pair because an enum value without a config row breaks the vocabulary surface.
Two values rather than one reversible action, matching `ban_hashtag`/`unban_hashtag`: an audit row has to say which direction the state moved.
Neither requires a reason - pinning grants prominence rather than taking a capability away, which is the line V18 drew - and both are reversible.

## Section 4: Inter-Module Dependencies

| Dependency | Direction | Nature |
|------------|-----------|--------|
| `post` | inbound | `post_hashtags` links posts to hashtags; trigger fires on `post_hashtags` insert/delete. The post module also reads `HashtagService` for the banned-name check and for the hashtag list on a post response |
| `admin` | inbound | `AdminHashtagService` owns the lifecycle endpoints and the `admin_actions` row; this module owns the table and every side effect, the same division `remove_post` uses with `post` |
| `recommendation` | inbound | `user_events` records `hashtag_click` events referencing `entity_type = 'hashtag'` |
