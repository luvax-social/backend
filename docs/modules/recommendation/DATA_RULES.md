# Recommendation Module — Data Rules

**Implementation status**: Partially implemented. `user_events` has two writers, a repository and a read path, and the personalized feed is built on top; nothing else in this module does.

Implemented: `RecommendationFeedService` (the Gorse-backed ranked feed, see `README.md` in this folder), `GorseClient`, `RecommendationFeedbackConsumer` and `UserEventJdbcRepository` (durable engagement writes to `user_events` plus Gorse feedback), `UserEventRecorder` (fire-and-forget analytics writes to `user_events`), `ImpressionService` (batched impression ingest), `UserEventRepository` (read, keyset-paged), `UserEventsPartitionJob` (partition maintenance), and the `UserEvent` entity with its enum and converters.
The activity-log read surface lives in the `admin` module; this module owns the table and both write paths.

The two writers exist because their durability contracts are opposites and cannot be met by one component.
`UserEventRecorder` must never fail or slow the request that triggered it, so it drops rows under pressure.
`RecommendationFeedbackConsumer` writes the canonical engagement record that Gorse is rebuilt from, so it must not drop anything and must be idempotent across redelivery.

`ImpressionService` is a producer for the second of those, not a third writer.
It writes no `user_events` row itself; it enqueues one outbox row per impression and the consumer writes the row, so impressions inherit the durable path's guarantees rather than needing their own.

### Impression ingest

An impression is a client-reported observation that a post was at least half visible in the viewport for one continuous second.
The client performs that measurement; the backend owns the endpoint, the transport, and the durability.

Impressions are submitted in batches to a single endpoint under the recommendations root.
The batch is bounded and an oversized batch is rejected as a validation error rather than truncated, so a client is never told signals were accepted that were in fact discarded.

**Idempotency is client-keyed.**
Each impression carries a client-generated `impressionId`, which becomes the outbox `event_id`.
A retry after a network failure therefore resends the same ids and is absorbed by three constraints that already existed: `outbox_events.event_id` is `UNIQUE`, `processed_messages` is unique on `(consumer_name, event_id)`, and `UserEventJdbcRepository.insertIgnoreDuplicate` keys the row on the event id.
`OutboxService.enqueueOnce` is the entry point; the ordinary `enqueue` generates a random event id and would count a resubmitted batch twice.
This dedupe holds for as long as the retention job keeps the rows it depends on: the outbox row for 7 days (`app.retention.outbox-published`) and the inbox marker for 14 days (`app.retention.processed-messages`).
A retry older than both windows is no longer recognised as a duplicate and is processed as a new impression.

**`posts.view_count` is never touched by this path**, and neither is it touched by `POST /posts/{postId}/view`.
Both endpoints emit `post.viewed.v1` and neither writes the counter, which a background job maintains and application code never writes.
The two endpoints are deliberately distinct and must not be merged: one records a single deliberate open of one post, the other ingests batched passive viewport impressions carrying dwell and a surface.

Dwell travels as `dwellSeconds` and the surface as `surface`; both are additive payload keys, so a `post.viewed.v1` message enqueued before they existed stays readable and falls back to a unit feedback value.

Not implemented: `categories`, `user_interests`, `post_categories`, `post_interaction_scores`, `user_similarity`.

### What Gorse receives, and what it deliberately does not

| Signal | Gorse feedback type | Value | Note |
|--------|--------------------|-------|------|
| `post.liked.v1` | `like` | 1.0 | positive |
| `post.saved.v1` | `save` | 1.0 | positive |
| `comment.created.v1` | `comment` | 1.0 | positive |
| `post.shared.v1` | `share` | 1.0 | positive |
| `comment.liked.v1` | `like` | 0.5 | attributed to the parent post |
| `post.viewed.v1` | `read` | dwell seconds, or 1.0 | the negative training signal |
| story views | none | none | recorded in `user_events` only |

**A comment like is a deliberate reduction, not the raw signal.**
It is a user-to-comment relation, but Gorse's item space is posts, so the only usable mapping attributes it to the comment's parent post.
It reuses the `like` feedback type at half weight rather than taking a type of its own, because a new type would need its own entry in `positive_feedback_types` and would dilute the bucket the collaborative model trains on.
The raw signal is not lost: `user_events` still records the true `comment_like` event type.

**Feedback `Value` accumulates; it is not overwritten.**
Measured against v0.5.11: inserting 2.0 then 5.0 for one (type, user, item) tuple leaves a single row holding 7.0.
So an impression's dwell is a running total of that viewer's time on that post, not the duration of the last impression, which is the intended reading.
It also means the row-level idempotency of `insertFeedback` does not extend to the value: a replay that reached Gorse twice would inflate it.
What prevents that is the inbox guard keyed on the event id, not the recommender.

**A share is a positive example.**
It costs the user more effort than a like and is a deliberate endorsement to a specific person rather than a passive signal, so `share` is listed in `positive_feedback_types`.

**Story views are never sent to Gorse. Do not reopen this.**
Stories expire after 24 hours, while Gorse fits on a schedule and caches recommendation results for the configured `cache_expire`, so a story could be recommended after it has ceased to exist.
There is also no item-space fit: stories are not posts, and inserting them as items would pollute the catalogue the post recommender ranks over.
Story views are recorded in `user_events` only.

### Exhaustion topup, not replacement

`enable_replacement` is `false`.
It was measured on, once, against the seeded dataset: 65 of the top 200 personalized results for a test user were already-read and ranked ahead of unread items (mean rank 92.2 versus 103.0), which reintroduces read items immediately rather than only once the unread catalogue is exhausted.
See `.workspace/reports/rec_onboarding/prompt1_verification.md` section G5 for the full measurement.

Exhaustion is instead handled in `RecommendationSource`, the candidate-source pipeline stage.
Gorse's personalized list excludes read items outright with replacement off.
When Gorse returns fewer candidates than requested, the source backfills from the time-decayed trending recommender in two ordered passes: trending items the viewer has not read, then trending items the viewer has read.
A read item therefore only ever appears once every unread trending candidate has been exhausted, and always at the tail of the batch.
The topup target is the caller's own over-fetch parameter, already sized for downstream visibility filtering; no separate configuration exists for it.
A topup failure (the trending call or the read-set query) is caught inside `RecommendationSource` and degrades silently to serving Gorse's results alone; it never trips the `gorse` circuit breaker, because that would discard a primary response that already succeeded over an enrichment step that did not.
Topup is only attempted for the personalized path; it is never attempted when the source has already degraded to the popularity ranking, since that state already indicates Gorse is unreliable.

**The read-set is a bounded, lossy snapshot, not a complete history.**
It is read from `user_events` inside a configurable time window (`app.recommendation.read-set-window`, default 90 days) capped at a configurable row count (`app.recommendation.read-set-max-rows`, default 2000).
A viewer whose read history exceeds either bound simply gets an incomplete read-set, which can make the topup's second pass show something read long ago as if it were merely "read recently."
This is accepted rather than engineered around.
The query needs no new index: `idx_user_events_user (user_id, created_at DESC)` (V15) already serves the `user_id` equality plus `created_at` range predicates the query issues.

**Pagination is deterministic across pages, with one accepted trade-off and one known residual gap.**
The ranked-feed cursor carries two independent offsets: one into Gorse's personalized (or popularity) list, one into the trending list the topup reads from.
Both only ever advance forward and are fully carried in the cursor.
Within a topup round, the whole fetched trending chunk is considered spent once any of it is reached, not just the candidates actually selected from it; an unread candidate beyond what was needed to fill the shortfall, or a read candidate scanned past while filling from unread, is not retried on a later page.
This is a deliberate simplicity/determinism trade-off in the same family as the read-set window and cap.

Measured against a running stack: before a fix, paginating one viewer through three pages produced 81 duplicate ids out of 300, because Gorse's own `[recommend.ranker]` merges the trending recommender as one of its inputs, so an item Gorse's personalized list had already shown on an earlier page was a realistic candidate for the trending topup to resurface on a later page.
The topup now excludes the viewer's complete Gorse history up to the current page, not only the current round's results, which brought the measured duplicate count to zero across the same three-page run.
The reverse direction is not closed: Gorse's own paginated output cannot be filtered against what the topup already showed on an earlier page, since Gorse's API accepts no exclusion list, so a full fix would require post-hoc filtering with the same precise offset accounting the topup fix required.
This residual gap was not observed in the measured run and is documented here as a known limitation, not engineered around.
See `.workspace/reports/rec_onboarding/prompt2_verification.md` for the numbers.

### Explore: excluding followed accounts

`GET /api/v1/recommendations/feed` accepts an `excludeFollowed` query parameter, default `false`.
When `true` it serves the discovery ("Explore") variant of the same pipeline: candidates authored by accounts the viewer already follows (via `SocialService.getAcceptedFollowingExcludingBlocks`) are removed in the same filtering step that already applies the block and visibility rules, not in a second pipeline or a second endpoint.

**The chronological-following fallback is suppressed under `excludeFollowed`, not merely filtered.**
When both ranked sources are exhausted or unavailable on the first page, the personalized feed normally falls back to the chronological following feed.
That fallback is, by definition, exactly the accounts an Explore-style caller asked to exclude, so entering it under `excludeFollowed` would show precisely the wrong content.
An exhausted Explore result is therefore an honest empty page instead.

---

## Section 1: Canonical Data

| Table | Key Columns | Notes |
|-------|-------------|-------|
| `categories` | `id`, `name`, `slug`, `parent_id` | Interest taxonomy. Hierarchical (self-referential via `parent_id`). Managed by the team, not by users. |
| `user_interests` | `user_id`, `category_id`, `score` | User-to-category interest weights. Updated by ML jobs or explicit user selection. `score` is a decimal in [0, 10]. If scores are assigned solely by ML jobs, treat as Derived. |
| `post_categories` | `post_id`, `category_id`, `confidence` | Post-to-category assignments. `confidence` in [0, 1]. Assigned at upload or by ML classifier. |
| `user_events` | `id`, `user_id`, `session_id`, `event_type`, `entity_type`, `entity_id`, `metadata`, `created_at` | Raw behavioral event stream. Append-only. Partitioned by month (`PARTITION BY RANGE (created_at)`). |

These tables cannot be rebuilt if lost — `user_events` is the raw behavioral record; `user_interests` may reflect manual user selections not derivable from behavior alone.

---

## Section 2: Derived Data / Cache / Projection

| Data | Location | Rebuilt From | Rebuild Trigger |
|------|----------|--------------|-----------------|
| `post_interaction_scores` | `post_interaction_scores` table | Recomputed from `post_likes`, `comments`, `post_saves`, `user_events` by background scheduler | Scheduled job. Never write to this table from Controller or Service code. |
| `user_similarity` scores | `user_similarity` table | Recomputed by ML job from `user_interests`, `user_events`, `follows` | Scheduled ML job. Constraint `user_id_a < user_id_b` eliminates duplicate pairs. |
| Explore page feed | Redis cache | Built from `post_interaction_scores` ordered by `total_score DESC` | Cache miss or TTL expiry |

---

## Section 3: Business Rules

### A. Rules Enforced by the Database

| Rule | Enforced By |
|------|-------------|
| `categories.name` and `categories.slug` must be unique | `UNIQUE NOT NULL` on each |
| `user_interests` allows at most one row per (user, category) pair | Compound `PRIMARY KEY (user_id, category_id)` |
| `post_categories` allows at most one row per (post, category) pair | Compound `PRIMARY KEY (post_id, category_id)` |
| `post_categories.confidence` must be in [0, 1] | `DECIMAL(4,3)` precision; application must enforce range |
| `user_similarity` must use `(min_id, max_id)` ordering to prevent duplicate pairs | `CHECK (user_id_a < user_id_b)` |
| `user_similarity.similarity_score` must be in [0, 1] | `CHECK (similarity_score BETWEEN 0 AND 1)` |
| `user_events.event_type` must be one of the 20 values in `event_type` enum | `event_type` enum |
| `user_events.platform` must be one of `'ios'`, `'android'`, `'web'` if provided | `CHECK (platform IN ('ios', 'android', 'web'))` |
| Deleting a user cascades to `user_interests`, `user_similarity`, and `user_events` rows | `ON DELETE CASCADE` on all FK references |
| Deleting a post cascades to `post_categories` and `post_interaction_scores` | `ON DELETE CASCADE` on FK references |
| `user_events` is partitioned by `created_at` month; a default partition catches unmatched dates | Declarative partitioning; `user_events_default` partition |

**The catch-all partition is not the safety net it appears to be, and the consequence is measured rather than argued.**

A write whose month has no declared partition does **not** fail. It lands in `user_events_default` silently, so nothing signals the problem.
Once it has, that month's partition can never be created: PostgreSQL refuses with `updated partition constraint for default partition "user_events_default" would be violated by some row`.
The gap becomes permanent, and the trapped rows become unprunable, because every window-bounded read then has to scan the default partition to prove it holds nothing relevant.
Detaching the default and writing to an undeclared month fails outright with `no partition of relation "user_events" found for row`, which is what the default is preventing.

So the default stays, because rejecting an analytics write is worse than absorbing it, and rows accumulating in `user_events_default` are the alert condition. V69 closed the 2026-07 hole that V14 and V28 left between them, and `UserEventsPartitionJob` keeps the horizon ahead from there.

Partition pruning, measured against a table populated across four months:

| Window | Partitions scanned |
|--------|--------------------|
| Entirely inside one month | that month alone |
| Crossing one month boundary | exactly the two months it spans |
| Reaching past the last declared partition | the declared months it spans, plus `user_events_default` |
| Unbounded, `user_id` only | every declared partition and the catch-all |

The last row is the read the activity log's mandatory window exists to make impossible.

### B. Rules Enforced by Application Code

| Rule | Service / Component |
|------|---------------------|
| `user_events` rows are append-only; existing events must never be updated or deleted | `UserEventRecorder` and `UserEventJdbcRepository` issue only `INSERT`; the `UserEvent` entity is `@Immutable` and has no persist path |
| An impression must survive a client retry without being counted twice | `ImpressionServiceImpl` via `OutboxService.enqueueOnce` - the client-supplied `impressionId` becomes the outbox `event_id`, and the `UNIQUE` constraint on it absorbs the resubmission. Never use the ordinary `enqueue` on this path; it generates a random event id and would double count |
| An impression must never write `posts.view_count` | `ImpressionServiceImpl` - it only enqueues an outbox row. The counter is maintained by a background job and is never written from application code, on this path or the single-post view path |
| Analytics event writes must be fire-and-forget (non-blocking to the user action that triggered them) | `UserEventRecorder` - the insert runs on a virtual thread of its own, so it neither joins nor extends the caller's transaction, and every failure ends in a warn log and a dropped row |
| An analytics write must never fail the request that triggered it | `UserEventRecorder` - no retry, no outbox, no dead letter. `OutboxService` exists for events that must reach RabbitMQ; these are not those |
| Event writes must not be able to exhaust the connection pool | `UserEventRecorder` - submission is bounded by a permit count well under the Hikari pool size, and a submission with no permit free is dropped immediately rather than queued or blocked, because backpressure onto a request thread would defeat the rule above |
| `UserEventRecorder` writes only three event types | `session_start` on any route that issues a session, `search` on both search surfaces carrying the term, `profile_view` for another account's profile. Chosen for investigative value per unit of write volume |
| A dropped or delayed analytics write must not lose the request's trace | `UserEventRecorder`'s task decorator restores the caller's W3C trace context on the virtual thread before the insert runs, so the write's own span still joins the request's trace even though it commits independently and on no schedule the caller can observe |
| Engagement event writes must not drop rows and must survive redelivery | `RecommendationFeedbackConsumer` with `UserEventJdbcRepository.insertIgnoreDuplicate` - the row id is the domain event id and the insert is `ON CONFLICT DO NOTHING`, so a replay is a no-op. These rows are the canonical record Gorse is rebuilt from, which is why they take the durable path rather than the dropping one |
| An engagement write must never fail the user action that triggered it | `PostLikeServiceImpl` / `PostSaveServiceImpl` enqueue an outbox row inside the domain transaction; the event reaches `user_events` and Gorse later, off the request thread |
| A view of one's own profile is not recorded | `UserServiceImpl.assemblePublicProfile` - excluded at the call site rather than filtered out later, so the table does not fill with the views that answer no question |
| A read of `user_events` must be bounded by `created_at` | `AdminUserEventServiceImpl` - the window is the only predicate that prunes partitions; a read bounded only by `user_id` touches every partition ever declared |
| `post_interaction_scores.engagement_score` is computed as: `likes + comments * 2 + saves * 3` | `[NOT YET IMPLEMENTED]` — defined in schema comment |
| `post_interaction_scores.recency_score` applies a time-decay factor based on `posts.created_at` | `[NOT YET IMPLEMENTED]` |
| `user_interests.score` is updated by an ML job; application code must not overwrite ML-derived scores directly | `[NOT YET IMPLEMENTED]` |
| New `user_events` partitions must be created before their `created_at` range begins (monthly) | `UserEventsPartitionJob` - ensures the current month and the next two, daily. It ensures the whole window rather than only its far edge, and runs daily rather than monthly, because a run missed on the first of a month would otherwise leave a hole that outlives the outage |
| `post_categories.confidence` must be in [0.000, 1.000]; validate before insert | `[NOT YET IMPLEMENTED]` |

### C. Scope Simplifications

- Recommendation scores (`post_interaction_scores`, `user_similarity`) are computed by batch jobs, not in real-time. Feed ranking may lag behind actual user behavior by minutes.
- `user_events` partitions are maintained by `UserEventsPartitionJob`, which ensures the current month and the next two, daily; a default partition still catches anything outside that window. The earlier statement that partitions existed only through June 2026 is obsolete.
- No A/B testing infrastructure for recommendation algorithms.
- No explicit user "not interested" signal. Negative training examples are inferred rather than declared: a `read` with no accompanying positive feedback is what the factorization machine ranker trains against. A seeded database with no read signal therefore teaches the ranker nothing, which is why `SeedOutboxEmitter` emits `post.viewed.v1` and `AnalyticsSeedWriter` no longer writes `post_view` rows of its own.

---

## Section 3D: `user_hashtag_affinity`

A derived read model: how strongly each user leans toward each hashtag, over a bounded window.
Three surfaces consume it - personalised trending, composer suggestions, and the interest similarity candidate source planned next - so it is built once rather than three times.

Fully rebuildable from `user_events` joined to `post_hashtags`.
Losing the table costs only the next scheduled recompute, so it is a cache tier by the classification in `GLOBAL_RULES.md`, not a source of truth.

### Key shape: in place, not versioned by window

The primary key is `(user_id, hashtag_id)` and each run overwrites the previous score.
Versioning by window was considered and rejected: no consumer reads a historical window, all three ask only what the user leans toward now, and the row count would multiply by the number of retained windows on a table already sized users by hashtags.
The rollback argument that usually favours versioning is weak here specifically, because the job is a full recompute of a rolling window: a bad run is corrected by the next run twelve hours later rather than by restoring its predecessor.

`window_start` and `window_end` are kept on every row even though the score is not versioned by them.
A score is meaningless without the interval it was computed over, and a stale row left by a job that stopped running is otherwise undetectable.

### Writers

**Written only by the affinity job.** No request path may write this table.

The job runs on a 12 hour cycle and assumes a single application instance with no distributed scheduler lock, the same assumption `platform_stats` makes.
What makes that safe is the write shape rather than the schedule: the recompute is `INSERT ... ON CONFLICT (user_id, hashtag_id) DO UPDATE`, so a second concurrent run rewrites the same rows with the same values instead of duplicating them.

Each run stamps `computed_at` and then deletes rows carrying an older stamp, in the same transaction as the upsert.
Both statements committing together is what stops a reader seeing the previous run's rows for one user and this run's for another.
A user who stops engaging, or a hashtag that leaves circulation, therefore loses its rows rather than keeping a score frozen at whatever it held when the job last saw it.

The job catches and logs its own failures rather than letting them propagate.
Spring's scheduler abandons a `fixedDelay` task whose method throws, which would silently stop every later run; this model is rebuildable and a missed cycle is corrected by the next one, so surviving to the next cycle matters more than surfacing the failure from the scheduler.

### The `user_events` time bound is mandatory here

`user_events` is partitioned by month with a `DEFAULT` catch-all.
Every read in the derivation is bounded on both sides by `created_at`, because a predicate on `user_id` alone prunes nothing and touches every partition ever declared.

Measured on the seeded database, 24 partitions declared, `EXPLAIN (ANALYZE, BUFFERS)` on the bounded derivation read: **4 partitions scanned** (`user_events_2026_06` through `user_events_2026_09`), 61 ms total.
The three older months are index scans; the current month is a sequential scan because it holds 43,375 of the rows.

### Weighting, decay and normalisation

| Event | Weight | Why |
|---|---|---|
| `post_save` | 4.0 | a deliberate keep-for-later, the strongest statement of interest available |
| `post_share` | 3.0 | endorsement to other people |
| `post_comment` | 3.0 | effortful public engagement |
| `post_like` | 2.0 | cheap approval |
| `post_view` | 0.25 | passive, and by far the most numerous |
| `post_unsave` | -4.0 | exact negation of `post_save` |
| `post_unlike` | -2.0 | exact negation of `post_like` |
| `hashtag_click` | 3.0 | direct navigational intent, joined on the hashtag itself rather than through a post |

**`post_view = 0.25` was calibrated against a seeding artefact and must be re-measured against real traffic.**
Do not read it as a considered production constant.
At the time it was chosen, all 21,548 `post_view` rows in the seeded database carried a single date (2026-09-07) while every other event type spanned the full 90 days.
That is an artefact of how the seed replays events: `SeedOutboxEmitter` emits `post.viewed.v1` and the consumer stamps the row at replay time, so every view looks like it happened at once, and looks like it happened now.
The combination made views simultaneously the most numerous signal and, after time decay, the most recent one, which is why the weight sits an order of magnitude below a like rather than merely below it.
Under real traffic, views will spread across the window like every other event and the same 0.25 will suppress them further than intended.
Re-measure it against a production event distribution before treating the ranking as tuned.

Reversals negate their own action exactly, so a user who liked and then unliked a post contributes nothing from that pair.
A hashtag whose contributions sum to zero or below is dropped rather than stored at zero.

`hashtag_click` is unioned in separately because its `entity_id` is already a hashtag id: it needs no join through `post_hashtags`.

Decay is exponential with a **30 day half-life** across a **90 day window**.
Three half-lives span the window, so its far edge still contributes about an eighth rather than falling off a cliff, while last week clearly outranks last month.
A 7 day half-life would make the 90 day window pointless, since the far edge would contribute a hundredth of a percent; a 60 day half-life would barely separate the two ends.

Scores are normalised into each user's share of their own decayed total, so the values for one user sum to 1.
This is what makes a user with three thousand events comparable with one with thirty.
Without it, the blend that reads this table would rank by activity volume rather than by interest.

### Cold start

A user with no events in the window ends with no rows.
That is the correct outcome, not a gap to paper over: the read path handles an empty result by falling back to the platform list, and the job never fabricates a row to avoid one.

## Section 4: Inter-Module Dependencies

| Dependency | Direction | Nature |
|------------|-----------|--------|
| `users` | inbound | `user_interests`, `user_events`, `user_similarity` reference `users.id` |
| `post` | inbound | `post_categories`, `post_interaction_scores` reference `posts.id`; `user_events` references posts via `entity_id` |
| `hashtag` | inbound | `user_events` captures `hashtag_click` events with `entity_type = 'hashtag'` |
| `story` | inbound | `user_events` captures `story_view` events with `entity_type = 'story'` |
