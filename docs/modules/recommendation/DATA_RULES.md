# Recommendation Module — Data Rules

**Implementation status**: Partially implemented. `user_events` lives in ClickHouse with three writers and two read paths, and the personalized feed and the Gorse rebuild tool are built on top; nothing else in this module is.

Implemented: `RecommendationFeedService` (the Gorse-backed ranked feed, see `README.md` in this folder), `GorseClient`, `RecommendationFeedbackConsumer` (durable engagement writes to ClickHouse `user_events` plus Gorse feedback), `UserEventRecorder` (fire-and-forget analytics writes), `UserEventImportConsumer` (the outbox path for server-side events that must not reach Gorse), `UserEventAnalyticsRepository` (every ClickHouse read and write of the table), `ImpressionService` (batched impression ingest), the hashtag affinity recompute, and the operator-triggered Gorse rebuild (`rebuild/`, with `GorsePurger`).
The activity-log read surface lives in the `admin` module; this module owns the table and all three write paths.

The writers exist because their durability contracts differ and cannot be met by one component.
`UserEventRecorder` must never fail or slow the request that triggered it, so it drops rows under pressure.
`RecommendationFeedbackConsumer` writes the canonical engagement record that Gorse is rebuilt from, so it must not drop anything and must be idempotent across redelivery.
`UserEventImportConsumer` writes `recommendation.user-event.imported.v1` events, the durable path for a server-side behavioural event that must not reach Gorse; it has no production producer today, and the development seed is its user.

`ImpressionService` is a producer for the second of those, not a fourth writer.
It writes no `user_events` row itself; it enqueues one outbox row per impression and the consumer writes the row, so impressions inherit the durable path's guarantees rather than needing their own.

### Impression ingest

An impression is a client-reported observation that a post was at least half visible in the viewport for one continuous second.
The client performs that measurement; the backend owns the endpoint, the transport, and the durability.

Impressions are submitted in batches to a single endpoint under the recommendations root.
The batch is bounded and an oversized batch is rejected as a validation error rather than truncated, so a client is never told signals were accepted that were in fact discarded.

**Idempotency is client-keyed.**
Each impression carries a client-generated `impressionId`, which becomes the outbox `event_id`.
A retry after a network failure therefore resends the same ids and is absorbed by three guards: `outbox_events.event_id` is `UNIQUE`, `processed_messages` is unique on `(consumer_name, event_id)`, and the ClickHouse row carries the event id in its sorting key, so a copy that slipped past both folds away on merge and never shows under `FINAL`.
`OutboxService.enqueueOnce` is the entry point; the ordinary `enqueue` generates a random event id and would count a resubmitted batch twice.
This dedupe holds for as long as the retention job keeps the rows it depends on: the outbox row for 7 days (`app.retention.outbox-published`) and the inbox marker for 14 days (`app.retention.processed-messages`).
A retry older than both windows is no longer recognised as a duplicate and is processed as a new impression.

**`posts.view_count` is never touched by this path**, and neither is it touched by `POST /posts/{postId}/view`.
Both endpoints emit `post.viewed.v1` and neither writes the counter, which a background job maintains and application code never writes.
The two endpoints are deliberately distinct and must not be merged: one records a single deliberate open of one post, the other ingests batched passive viewport impressions carrying dwell and a surface.

Dwell travels as `dwellSeconds` and the surface as `surface`; both are additive payload keys, so a `post.viewed.v1` message enqueued before they existed stays readable and falls back to a unit feedback value.

Not implemented: `categories`, `user_interests`, `post_categories`.
`post_interaction_scores` and `user_similarity` never had a reader or a writer and were dropped in V132.

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
A topup failure (the trending call or the read-set query, including a `ClickHouseException`) is caught inside `RecommendationSource` and degrades silently to serving Gorse's results alone; it never trips the `gorse` circuit breaker, because that would discard a primary response that already succeeded over an enrichment step that did not.
Topup is only attempted for the personalized path; it is never attempted when the source has already degraded to the popularity ranking, since that state already indicates Gorse is unreliable.

**The read-set is a bounded, lossy snapshot, not a complete history.**
It is read from ClickHouse `user_events` inside a configurable time window (`app.recommendation.read-set-window`, default 90 days) capped at a configurable row count (`app.recommendation.read-set-max-rows`, default 2000).
A viewer whose read history exceeds either bound simply gets an incomplete read-set, which can make the topup's second pass show something read long ago as if it were merely "read recently."
This is accepted rather than engineered around.
The query filters `user_id` (the first sorting column), `event_type = 'post_view'`, `entity_id IS NOT NULL` and the `created_at` range, and it does not use `FINAL`: the result is only ever read as a set of ids, so an unmerged duplicate is harmless.

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
| `luvax_analytics.user_events` (ClickHouse) | `id`, `user_id`, `event_type`, `entity_type`, `entity_id`, `metadata`, `feedback_type`, `feedback_value`, `created_at`, `ingested_at` | Raw behavioral event stream and the system of record for it. Append-only, `ReplacingMergeTree` ordered by `(user_id, event_type, created_at, id)`, monthly partitions, 12-month retention. `feedback_type` and `feedback_value` hold the exact Gorse feedback the row was sent with, null when it was never sent. `session_id`, `platform`, `ip_address` and `user_agent` were dropped: nothing wrote them in production and nothing read them. The PostgreSQL table was dropped in V131. |

These tables cannot be rebuilt if lost - `user_events` is the raw behavioral record, kept for 12 months; `user_interests` may reflect manual user selections not derivable from behavior alone.

---

## Section 2: Derived Data / Cache / Projection

| Data | Location | Rebuilt From | Rebuild Trigger |
|------|----------|--------------|-----------------|
| `user_hashtag_affinity` | PostgreSQL table | ClickHouse `user_events` joined to `post_hashtags` (Section 3D) | The affinity job, every 12 hours |
| The Gorse store (users, items, feedback) | Gorse's own database | PostgreSQL `users` and `posts`, and the feedback recorded in ClickHouse `user_events` | The operator-triggered rebuild (Section 3E); the live pipeline keeps it current otherwise |

---

## Section 3: Business Rules

### A. Rules Enforced by the Database

| Rule | Enforced By |
|------|-------------|
| `categories.name` and `categories.slug` must be unique | `UNIQUE NOT NULL` on each |
| `user_interests` allows at most one row per (user, category) pair | Compound `PRIMARY KEY (user_id, category_id)` |
| `post_categories` allows at most one row per (post, category) pair | Compound `PRIMARY KEY (post_id, category_id)` |
| `post_categories.confidence` must be in [0, 1] | `DECIMAL(4,3)` precision; application must enforce range |
| `user_events.event_type` must be one of the 20 values of the ClickHouse `Enum8` | `event_type Enum8(...)` in `luvax_analytics.user_events`: an unknown value is rejected with code 691, and adding a `UserEventType` value needs a `V{n}` ClickHouse migration in the same commit |
| Deleting a user cascades to `user_interests`; `user_events` rows are not deleted with the user, because ClickHouse has no foreign keys, and they age out with the 12-month retention | `ON DELETE CASCADE` on the PostgreSQL references only |
| Deleting a post cascades to `post_categories` | `ON DELETE CASCADE` on FK references |
| `user_events` rows older than 12 months are removed, in whole monthly parts, so a month is gone between 12 and 13 months after it began | `TTL toDateTime(created_at) + INTERVAL 12 MONTH` with `ttl_only_drop_parts = 1` |

**Deduplication is the engine's job, and exact reads use `FINAL`.**

`ReplacingMergeTree` keeps one row per sorting key, and the key ends in the event id, so two copies of one event fold into one on merge.
Until a merge runs both copies exist: an unmerged duplicate is visible without `FINAL` and invisible with it.
The activity log and the affinity signals therefore read with `FINAL`, and the For You read-set does not, because it only builds a set of ids.
No version column exists: rows never change after insert, and the consumers write the envelope's `occurredAt` as `created_at`, never the delivery time, so which copy the engine keeps does not matter.
Insert-time block deduplication is deliberately off, because content-hash deduplication would also swallow two distinct events that happen to carry identical content.

`created_at` is always supplied by the writer and never defaulted, because an async insert is processed at flush time and the event time is when the request or the domain event happened.
`id` defaults to `generateUUIDv4()` only for the rows `UserEventRecorder` writes, which have no domain id.

Batch reads that need more rows than the reader profile allows set `max_result_rows = 0` in their own SQL and stay bounded by the profile's memory cap; a whole-table `FINAL` scan exceeds the reader's memory, so the Gorse rebuild reads its feedback in batches of users.

### B. Rules Enforced by Application Code

| Rule | Service / Component |
|------|---------------------|
| `user_events` rows are append-only; existing events must never be updated or deleted by the application | `UserEventAnalyticsRepository` issues only `INSERT` and `SELECT`; the writer user holds only `INSERT` on the database, and retention is the table's own TTL |
| An impression must survive a client retry without being counted twice | `ImpressionServiceImpl` via `OutboxService.enqueueOnce` - the client-supplied `impressionId` becomes the outbox `event_id`, and the `UNIQUE` constraint on it absorbs the resubmission. Never use the ordinary `enqueue` on this path; it generates a random event id and would double count |
| An impression must never write `posts.view_count` | `ImpressionServiceImpl` - it only enqueues an outbox row. The counter is maintained by a background job and is never written from application code, on this path or the single-post view path |
| Analytics event writes must be fire-and-forget (non-blocking to the user action that triggered them) | `UserEventRecorder` - the insert runs on a virtual thread of its own, so it neither joins nor extends the caller's transaction, uses `async_insert = 1, wait_for_async_insert = 0` so the acknowledgement is immediate, and every failure ends in a warn log and a dropped row |
| An analytics write must never fail the request that triggered it | `UserEventRecorder` - no retry, no outbox, no dead letter. `OutboxService` exists for events that must reach RabbitMQ; these are not those. A drop is counted in `luvax_analytics_user_events_dropped_total` with `reason` `permits`, `not_ready`, `circuit_open` or `error`. With `wait_for_async_insert = 0` a bad row cannot be reported back to the recorder, so ClickHouse's own `FailedAsyncInsertQuery` counter is what an alert watches |
| Event writes must not be able to exhaust the connection pool | `UserEventRecorder` - submission is bounded by 8 permits, well under the 12-connection ClickHouse writer pool, and a submission with no permit free is dropped immediately rather than queued or blocked, because backpressure onto a request thread would defeat the rule above; the pool's 2 second checkout timeout means a starved pool fails fast into a drop |
| `UserEventRecorder` writes only three event types | `session_start` on any route that issues a session, `search` on both search surfaces carrying the term, `profile_view` for another account's profile. Chosen for investigative value per unit of write volume |
| A dropped or delayed analytics write must not lose the request's trace | `UserEventRecorder`'s task decorator restores the caller's W3C trace context on the virtual thread before the insert runs, so the write's own span (a `jdbc` span tagged with the ClickHouse datasource) still joins the request's trace even though it commits independently and on no schedule the caller can observe |
| Engagement event writes must not drop rows and must survive redelivery | `RecommendationFeedbackConsumer` writes ClickHouse first with `wait_for_async_insert = 1`, so the acknowledgement follows the part write, and only then Gorse. The row id is the domain event id, so a replay lands on the same key and folds away. A Gorse failure rolls back the inbox marker and the retry re-inserts an identical row. Writing ClickHouse first means Gorse never holds feedback without a durable record. These rows are the canonical record Gorse is rebuilt from, which is why they take the durable path rather than the dropping one |
| An engagement row stores the exact Gorse feedback it was sent with | `RecommendationFeedbackConsumer` - `feedback_type` and `feedback_value` are the type and value passed to `GorseClient.insertFeedback`, so dwell seconds and the half-weight comment like are reproduced by a rebuild rather than re-derived. Rows written by the recorder and the import consumer have neither and are never sent |
| An actor that no longer exists is a permanent failure | `RecommendationFeedbackConsumer` - `UserSummaryService.exists` is checked before the write, because ClickHouse has no foreign key to refuse it; the message is dead-lettered once |
| A ClickHouse outage must pause ingestion, not dead-letter it | `RecommendationFeedbackConsumer` and `UserEventImportConsumer` nack with requeue on `ClickHouseUnavailableException`, and `AnalyticsIngestionController` stops the listener containers while the `clickhouse` breaker is open, so the messages wait ready in their queues. A rejected request or a malformed envelope dead-letters at once; a Gorse failure keeps the retry-then-dead-letter path |
| An engagement write must never fail the user action that triggered it | `PostLikeServiceImpl` / `PostSaveServiceImpl` enqueue an outbox row inside the domain transaction; the event reaches `user_events` and Gorse later, off the request thread |
| A view of one's own profile is not recorded | `UserServiceImpl.assemblePublicProfile` - excluded at the call site rather than filtered out later, so the table does not fill with the views that answer no question |
| A read of `user_events` must be bounded by `created_at` | `AdminUserEventServiceImpl` - the window (at most 30 days) is what limits the scan of an unfiltered activity page, and a read bounded only by `user_id` would touch every retained month. ClickHouse being unavailable is a `503 ANALYTICS_UNAVAILABLE` |
| `user_interests.score` is updated by an ML job; application code must not overwrite ML-derived scores directly | `[NOT YET IMPLEMENTED]` |
| `post_categories.confidence` must be in [0.000, 1.000]; validate before insert | `[NOT YET IMPLEMENTED]` |

### C. Scope Simplifications

- Gorse retrains on its own schedule, so feed ranking may lag behind actual user behavior by minutes, and the hashtag affinity is a 12-hourly recompute.
- Analytics reads lag writes by seconds: an event is visible after the outbox publishes it and the consumer writes it (the recorder's rows after the async flush).
- ClickHouse partitions `user_events` monthly by itself; there is no partition maintenance job.
- No A/B testing infrastructure for recommendation algorithms.
- No explicit user "not interested" signal. Negative training examples are inferred rather than declared: a `read` with no accompanying positive feedback is what the factorization machine ranker trains against. A seeded database with no read signal therefore teaches the ranker nothing, which is why `SeedOutboxEmitter` emits `post.viewed.v1` and `AnalyticsSeedWriter` no longer writes `post_view` rows of its own.

---

## Section 3D: `user_hashtag_affinity`

A derived read model: how strongly each user leans toward each hashtag, over a bounded window.
Three surfaces consume it - personalised trending, composer suggestions, and the interest similarity candidate source planned next - so it is built once rather than three times.

Fully rebuildable from ClickHouse `user_events` joined to `post_hashtags`.
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

### Where the signals come from

The recompute reads per-user, per-target contributions from ClickHouse on the batch pool with `FINAL`, so the sums are exact, and streams the rows into a session-local temporary table `affinity_signal_stage` in batches of 5,000.
The unchanged upsert then joins the stage to `post_hashtags` and `hashtags` inside the same PostgreSQL transaction as the stale sweep.
Summing per (user, post) first and joining to hashtags afterwards gives the same weight and event count as the previous all-PostgreSQL statement, up to floating-point rounding (ClickHouse computes in `Float64`, PostgreSQL computed in `numeric`); `HashtagAffinityClickHouseIT` replays one fixture through the old SQL, kept in the test as the reference, and the new path and compares them within 1e-8 on the score and 1e-6 on the weight.
Every read is bounded on both sides by `created_at`, so ClickHouse skips every monthly partition outside the 90 day window.
Measured at stress scale (3.03 million events over 12 months, reader profile) the ClickHouse part took about 1.3 seconds and 161 MiB, so it fits the profile's caps.

A ClickHouse failure leaves the previous rows untouched, because nothing in PostgreSQL changes before the read completes, and the job logs it and waits for the next cycle.

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

## Section 3E: Gorse rebuild

An operator tool that makes Gorse's store agree with PostgreSQL and ClickHouse again.
It exists because the For You feed once broke silently when Gorse's item catalogue drifted from `posts`: every Gorse call still succeeded while its candidates no longer resolved to live posts, and `auto_insert_item = true` creates a visible item for any feedback that names an unknown one (`README.md` section 9).

### Trigger and lifecycle

- `GorseRebuildRunner` is `@ConditionalOnProperty(name = "GORSE_REBUILD", havingValue = "true")`, the same mechanism as `SEED_DATA`, and is not tied to the `seed` profile.
- It starts on `ApplicationReadyEvent` on a daemon thread named `gorse-rebuild`, so startup and the health check are never delayed.
- `GORSE_REBUILD_TOKEN` names the run and is required; the runner logs an error and does nothing without it.
- No run with that token: a run is created and starts at `PREFLIGHT`.
- A run with that token that is `RUNNING` or `FAILED`: it resumes from its phase and checkpoint, so a crash or a redeploy resumes rather than restarts.
- A run that is `DONE` or `FAILED_VERIFICATION`: the runner logs that it already finished and does nothing, so leaving the toggle set across a restart repeats nothing; a new rebuild needs a new token.
- There is no HTTP endpoint.
- Pacing keys: `app.recommendation.gorse-rebuild.requests-per-second` (`GORSE_REBUILD_REQUESTS_PER_SECOND`, 5), `user-batch-size` (500), `item-batch-size` (500) and `feedback-batch-size` (1000); they change how fast the rebuild pushes, never what it produces.
- Every Gorse call goes through the `gorseRebuild` rate limiter.

### State

`gorse_rebuild_runs` (V133) holds one row per token: `status` (`RUNNING`, `FAILED`, `FAILED_VERIFICATION`, `DONE`), `phase`, the `checkpoint_id` (the last PostgreSQL id fully sent in the current phase), the progress counts, `last_error` and timestamps.
The seed reset does not truncate it: it records operations, not seed content.

### Phases

Each batch is one step: send, then commit the checkpoint and counts in one short statement.
Every Gorse write is an overwrite, so repeating the batch that was in flight when a crash happened changes nothing.

| Phase | What it does |
|-------|--------------|
| `PREFLIGHT` | The ClickHouse schema gate is ready, Gorse answers `GET /api/items`, and the application's PostgreSQL role holds `TRUNCATE` on every table of the `gorse` database (`has_table_privilege`). Any failure ends the run `FAILED` before anything is changed |
| `PURGE` | `AnalyticsIngestionController.suspend("gorse-rebuild")` stops the `recommendationFeedback` listener for the rest of the run, so no live feedback interleaves and its messages wait in the queue; then `GorsePurger.purge()` truncates `feedback`, `items`, `users`, `documents`, `values`, `time_series_points` and `message`. A failure is fatal here, unlike the seed reset's warning |
| `USERS` | Every non-deleted user, in id order, pushed with `POST /api/users` (an upsert) |
| `ITEMS` | Every post, soft-deleted ones included, pushed with `POST /api/items` (an upsert); an item is hidden when the post is not `published` or is soft-deleted, which is what `PostIndexSyncConsumer` does live; labels come from `HashtagService.getHashtagNamesForPosts` |
| `FEEDBACK` | Per batch of users, the ClickHouse sums of `feedback_value` per (`feedback_type`, user, post) over rows that carry a `feedback_type`, keeping only posts PostgreSQL knows (the rest are counted in `feedback_skipped`), sent with `PUT /api/feedback`, which overwrites, where `POST` accumulates |
| `VERIFY` | Pages Gorse's items and compares them with PostgreSQL: the same set of ids, the same hidden flag on every item, and 20 sampled feedback tuples read back within 1e-6 of the ClickHouse sums (read through `GET /api/user/{user}/feedback/{type}`, because Gorse 0.5.11 drops the connection on the per-tuple endpoint when it does not hold the tuple) |
| `DONE` | `finished_at` is set; in every terminal state the feedback listener is resumed |

Feedback older than the 12-month `user_events` retention is not restored.
Recorder and import rows carry no `feedback_type` and are never sent, as they never were live.

### Failure handling and observability

- A Gorse 5xx or I/O failure is retried three times (2 s, 4 s, 8 s), after which the run is `FAILED` with its checkpoint and a restart with the same token resumes; a 4xx fails the run at once.
- A verification difference ends the run `FAILED_VERIFICATION`, logging the counts (`missing`, `stray`, `hiddenMismatch`, `feedbackMismatch`) and up to 20 example ids each.
- Logs are prefixed `[gorse-rebuild] token=<token>`: `starting phase=PREFLIGHT` or `resuming phase=<phase> checkpoint=<id>`, one `phase=<USERS|ITEMS|FEEDBACK> sent=<n> checkpoint=<id>` line per batch, and a final `phase=DONE users=<n> items=<n> feedback=<n> feedbackSkipped=<n>`.
- Meters: `luvax_gorse_rebuild_phase` (0 idle, 1 `PREFLIGHT`, 2 `PURGE`, 3 `USERS`, 4 `ITEMS`, 5 `FEEDBACK`, 6 `VERIFY`, 7 done, -1 failed), `luvax_gorse_rebuild_sent_total{kind}`, `luvax_gorse_rebuild_skipped_total{reason="unknown_post"}` and `luvax_gorse_rebuild_verification_failures_total`.

### Privilege the purge needs

The application's PostgreSQL role must be allowed to `TRUNCATE` the seven tables of the `gorse` database, which it does not own by default.
Without that grant the `PREFLIGHT` phase ends the run `FAILED` with `Gorse's store cannot be purged: role <role> lacks the TRUNCATE privilege on gorse.public.<table>` and changes nothing; grant the privilege and restart with the same token.
The operational grant, verification and rollback statements are in the observability handoff, not here.

---

## Section 4: Inter-Module Dependencies

| Dependency | Direction | Nature |
|------------|-----------|--------|
| `users` | inbound | `user_interests` references `users.id`; `user_events.user_id` is a plain column in ClickHouse with no foreign key; `UserSummaryService.exists` is the actor check the feedback consumer makes |
| `post` | inbound | `post_categories` references `posts.id`; `user_events` references posts via `entity_id`; the rebuild reads every `posts` row and `HashtagService` supplies item labels |
| `hashtag` | inbound | `user_events` captures `hashtag_click` events with `entity_type = 'hashtag'` |
| `story` | inbound | `user_events` captures `story_view` events with `entity_type = 'story'` |
| `common/analytics` | outbound | Every ClickHouse read and write goes through `ClickHouseOperations`; the four analytics listeners, `recommendationFeedback` and `userEventImport` among them, are started and stopped by `AnalyticsIngestionController` |
