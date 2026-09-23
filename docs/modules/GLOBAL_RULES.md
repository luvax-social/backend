# Global Rules — Data Rules

Cross-cutting conventions that apply to all modules. Do not duplicate these in per-module files — reference this document instead.

---

## 1. Data Tier Classification

| Tier | Technology | Role | Rebuild-able? |
|------|------------|------|---------------|
| Source of Truth | PostgreSQL | Canonical, durable data | No — this IS the source |
| Cache | Redis | Fast reads, session tokens, rate-limit state | Yes — rebuild from PostgreSQL |
| Search Index | Elasticsearch (`posts`, `hashtags` only) | Full-text search | Yes — rebuild from PostgreSQL |
| Event Stream | RabbitMQ | Async event delivery | No persistence guarantee |

**Conflict resolution rule**: If a data conflict exists between tiers, PostgreSQL is always correct.

---

## 2. Denormalized Counter Policy

All counters are maintained exclusively by PostgreSQL triggers (introduced in Flyway V16, with later modules adding their own in later migrations). Application code (Service, Repository) must never directly increment or decrement these counters.

| Counter | Table | Trigger | Source Table |
|---------|-------|---------|--------------|
| `follower_count` | `users` | `trg_follow_counts` | `follows` |
| `following_count` | `users` | `trg_follow_counts` | `follows` |
| `post_count` | `users` | `trg_post_count` | `posts` |
| `like_count` | `posts` | `trg_post_like_count` | `post_likes` |
| `comment_count` | `posts` | `trg_post_comment_count` | `comments` |
| `save_count` | `posts` | `trg_post_save_count` | `post_saves` |
| `like_count` | `comments` | `trg_comment_like_count` | `comment_likes` |
| `reply_count` | `comments` | `trg_comment_reply_count` | `comments` (self-referential) |
| `post_count` | `hashtags` | `trg_hashtag_post_count` | `post_hashtags` |
| `view_count` | `stories` | `trg_story_view_count` | `story_views` |
| `like_count` | `stories` | `trg_story_like_count` (V49) | `story_likes` |
| `actor_count` | `notifications` | `trg_notification_actor_count` (V117) | `notification_actors` |

If a counter appears stale, the correct action is to recalculate from the source join table — not to patch the counter directly.

All counter columns have `CHECK (column >= 0)` enforced at the database level. Triggers use `GREATEST(counter - 1, 0)` to prevent underflow on decrement.

**Exception — `posts.view_count`**: This counter is NOT maintained by a PostgreSQL trigger. It is updated by a background job due to high-volume write concerns. As a result, `view_count` may lag behind real-time activity. This is a deliberate tradeoff. Application code must not attempt to increment `view_count` directly from a request path.

---

## 3. Soft Delete Policy

Tables that use soft delete via a `deleted_at TIMESTAMPTZ` column:

| Table | Module | Status |
|-------|--------|--------|
| `users` | auth / users | **Column reserved, never written.** See the note below |
| `posts` | post | Live |
| `comments` | comment | Live |
| `stories` | story | Live |
| `messages` | message (uses `is_deleted BOOLEAN` + `deleted_at`, not pure soft-delete pattern) | Live |
| `notifications` | notification (V116; set by the recipient's delete and by a retraction that leaves a row with no actor) | Live |

> **`users.deleted_at` is not written by anything in the application.**
> The column exists and is indexed, but no service, repository, migration, or trigger sets it, and there is no self-service account deletion endpoint.
> Account removal today is `status = 'deactivated'`, an admin-only action that leaves `deleted_at` null.
> Every read path already filters `deleted_at IS NULL`, so those filters are correct and should stay; they simply never exclude anything at present.
>
> The retention rules in the subsection below are therefore a **specification for when a soft-delete path is added**, not a description of current behaviour.
> They are correct and worth keeping, but no code exercises them today.
> Do not reason about soft-deleted user rows as a state the running system can produce.
> A soft-deleted user can only be created by direct SQL, which is how the one observed during the phase 3 audit came to exist.

Rules:
- All queries against soft-deleted tables must include `WHERE deleted_at IS NULL` unless explicitly retrieving deleted records.
- Partial indexes already enforce `deleted_at IS NULL` for common access patterns; always use these indexes.
- Cascading behavior on soft delete is defined per module in each module's `DATA_RULES.md`.
- Hard deletes are reserved for administrative actions only.

### Username and Email Retention on Soft Delete

**Rule**: Soft delete does NOT release a user's `username` or `email`.

Forward-looking, per the note above: no path currently soft-deletes a user, so these rules bind the design of the eventual deletion flow rather than describing behaviour observable today.
The enforcement they call for is already in place, so adding that flow will not require revisiting them.

- The `UNIQUE` constraints on `users.username` and `users.email` remain enforced regardless of `deleted_at` value.
- A soft-deleted account continues to hold its username and email.
- Username identity is case-insensitive and enforced table-wide by the unique functional index `idx_users_username_lower` on `lower(username)` (V43), which deliberately has no `deleted_at` predicate so a soft-deleted account keeps its claim. Stored casing is preserved; see `users/DATA_RULES.md`.
- Availability checks must therefore be table-wide. `UserRepository.existsByUsername` is the single guard on every write path and spans soft-deleted rows by design.
- Username and email only become available after a hard delete (permanent row removal via a scheduled purge job).
- No purge job currently exists. Until one is implemented, soft-deleted accounts hold their username and email permanently.
- This mirrors the behavior of Instagram, which holds deleted account identifiers for a minimum grace period before releasing them.

---

## 4. Transactional Boundaries

- `@Transactional` is placed on Service **impl** methods only — never on interfaces or Controllers.
- Cross-module writes that must be atomic must be coordinated within a single Service method's transaction boundary.
- Never call multiple Service methods from a Controller and expect atomicity across them.

---

## 5. ID Strategy

- All primary keys are `UUID` generated by `gen_random_uuid()` (PostgreSQL `pgcrypto` extension, Flyway V01).
- Do not generate UUIDs in application code unless there is a documented reason.
- Join/relationship tables use composite primary keys (e.g., `post_likes(user_id, post_id)`).

---

## 6. Timestamp Policy

| Column | Set By | Notes |
|--------|--------|-------|
| `created_at` | Database `DEFAULT NOW()` | Do not set in application code |
| `updated_at` | Trigger `fn_update_updated_at` | Do not set in application code for tables that have this trigger |
| `deleted_at` | Application code | Set to `NOW()` on soft delete; `NULL` to restore |

Tables with `updated_at` triggers: `users` (`trg_users_updated_at`), `posts` (`trg_posts_updated_at`), `comments` (`trg_comments_updated_at`), `conversations` (`trg_conversations_updated_at`).

All timestamps use `TIMESTAMPTZ` (timezone-aware). Store and compare in UTC.

---

## 7. Media Upload Flow

**Rule**: The server does not receive media file bytes directly. All media uploads use pre-signed URLs.

Flow:
1. Client requests a pre-signed upload URL from the backend, providing `content-type` and `file-size`.
2. Backend validates the request, generates a Cloudflare R2 pre-signed URL, and returns it to the client.
3. Client performs a `PUT` request directly to R2 using the pre-signed URL.
4. After upload completes, client sends an "upload complete" notification to the backend, including: `storage_key`, `cdn_url`, `media_type`, `mime_type`, `file_size`, `width`, `height`, `duration` (video only), `blurhash`.
5. Backend issues a head-object request to R2 for that `storage_key` and rejects the notification unless an object exists there whose size and content type match the submitted metadata.
6. Backend creates the `media_assets` record using the provided metadata.

**Rule**: All media metadata (`width`, `height`, `duration`, `mime_type`, `file_size`, `blurhash`) is collected client-side and submitted by the client. The server does not perform server-side media inspection at upload time.

The existence check in step 5 is not media inspection. The server reads the object's response headers; it never fetches or decodes the file body, so `width`, `height`, `duration`, and `blurhash` remain client-supplied and unverified.

**Rule**: Upload confirmation fails closed. If object storage cannot be reached, the notification is rejected as retryable and no `media_assets` row is written. A row must never exist for an object that is absent, because every reader of `media_assets` treats the row's existence as proof the object is live.

---

## 8. Scope Simplifications (Deliberate Tradeoffs)

| Area | Simplification | Accepted Degradation |
|------|---------------|----------------------|
| Notifications | Real-time delivery is best-effort over a per-user STOMP topic carrying typed events (`upserted`, `read-state`, `deleted`, `seen`, `requests`) with the feed state after each; the REST list remains authoritative. The badge counts rows above a per-user seen watermark, bounded at 99+ | A missed push is recovered by refetching after a reconnect. Delivery latency is bounded by the outbox publisher's polling interval, not by the socket. A row committing within commit latency of a concurrent seen advance can be counted as seen without having been shown |
| Realtime transport | In-memory STOMP broker on a single application instance; the RabbitMQ fanout tier in front of it is multi-instance-safe, the broker itself shares nothing | A second instance delivers correctly but has no shared session state; nothing beyond delivery has been designed or tested for it |
| Feed / Explore ranking | `post_interaction_scores` updated by a background scheduler, not in real-time | Feed ranking may lag behind actual activity by minutes |
| Hashtag trending | `hashtag_trending` populated by a scheduled background job | Trending data is a periodic snapshot, not live |
| Email verification / password reset tokens | Stored in Redis, not PostgreSQL | Tokens are lost on full Redis flush; user must re-request |
| Full-text search | Two tiers by domain. `posts` and `hashtags` are indexed in Elasticsearch and queried behind the `elasticsearchSearch` circuit breaker; hashtag search falls back to `pg_trgm`, post search falls back to an empty page. `users` has no Elasticsearch index; username search queries the PostgreSQL `pg_trgm` GIN index `idx_users_username_trgm` directly via `ILIKE`, with **no** circuit breaker, because the backend is the source of truth and there is no lower tier to degrade to | User search ranking is popularity-ordered, not relevance-scored. A term matching a large fraction of the table degrades to a parallel sequential scan - measured at 56 ms against 200,000 rows - bounded by a 2-character minimum, a 10,000-row offset cap, a 100-row page cap, and required authentication |

### What a client sees when post search degrades

The post search fallback returns `200` with an empty page, not an error.
That is deliberate, and it means the response carries the same success envelope a genuine no-match carries.

Those two cases were previously indistinguishable: the degraded response and a real no-match differed only in the response timestamp.
A client could not tell "no posts matched your query" from "the search tier is unavailable", so it could not word an empty state honestly.

`CursorPageResponse` therefore carries a `degraded` boolean.
It is `false` on every complete result, including a genuine no-match, and `true` only on the post search fallback.
A client should read `degraded` before rendering an empty state and say the search is temporarily unavailable rather than that nothing matched.

The field appears on every cursor-paginated response because the envelope is shared, and it is `false` on all of them except this one fallback.

Hashtag search does **not** set it.
Its fallback answers from PostgreSQL, which is the source of truth, so the results are real and complete for the query and only the ranking differs from Elasticsearch relevance.
Post search sets it because it returns nothing at all.
| Recommendation | `user_similarity` and `post_interaction_scores` populated by external ML jobs | Recommendations may lag behind recent user behavior |
| Story expiry | Expired stories remain in the database until a cleanup job removes them | `expires_at` must always be checked; do not rely on row absence alone |

---

## Enum vs. Config Table Relationship

Several domain tables use PostgreSQL enum types as the column type for classification fields:

| Table | Enum column | Enum type |
|-------|-------------|-----------|
| `notifications` | `type` | `notification_type` |
| `notifications` | `category` | `notification_category` |
| `admin_actions` | `action_type` | `admin_action_type` |
| `reports` | `report_reason` | `report_reason` |
| `reports` | `report_type` | `report_type` |

Alongside these, the schema includes config tables (`notification_type_configs`, `moderation_action_configs`, `report_reason_configs`) that store display metadata and behavioral policy for each enum value.

**Rule: Enums are the constraint layer. Config tables are the metadata layer.**

- The enum enforces that only valid, schema-defined values can be written to the column. This is enforced at the database level with zero overhead on the write path.
- The config table stores supplementary metadata: `display_name`, `template_key`, `is_enabled`, `is_user_toggleable`, `scope`, etc. This data is read by the application layer for rendering, routing, and policy decisions.
- The two layers are complementary. Config tables do not replace enums.

**Implication**: Adding a new notification type, report reason, or admin action type requires a schema migration (adding the value to the enum). This is intentional — these are domain primitives, not user-configurable data that an admin can add arbitrarily at runtime.

**Read path**: Services read config tables to determine display behavior and policy. The enum value in the row is the source of truth for what the event IS. The config table is the source of truth for how it should be DISPLAYED and HANDLED.

**Empty-set convention in `report_reason_configs.applies_to`**: an empty array means the reason applies to **every** `report_type`, never to none.
The column is `NOT NULL DEFAULT '{}'`, so empty is the permissive default rather than an unfilled value.
Four rows carry it - `spam`, `harassment`, `scam` and `other` - because each applies to an account as readily as to a piece of content, and enumerating all five types would say the same thing at more length.
The rows that do enumerate are the ones that exclude a type: `nudity`, `violence` and `hate_speech` list the four content types and omit `user`, and `false_information` additionally omits `message`.
A client filtering the reason list by the type being reported must therefore treat an empty list as a match.
The convention is stated in V18 where the table is created, and published to consumers on the `appliesTo` field of the vocabulary endpoint.

---

## Metadata Configuration Tables

The following tables store runtime configuration and feature policy. They are NOT business logic source-of-truth tables. They exist to allow policy changes without code deployments.

| Table | Purpose | Status |
|-------|---------|--------|
| `system_settings` | System-wide configurable limits and TTL values | Live |
| `notification_type_configs` | Notification type registry with display and toggle settings | Live |
| `moderation_action_configs` | Admin moderation action registry | Live |
| `feature_flags` | Feature enable/disable control per environment | **Table exists, never read.** See the note below |

> **`feature_flags` has no runtime reader anywhere in `src/main`.**
> The table is created and seeded with one decorative `group_chat` row by the V18 migration, but no repository, service, `@Query` method, or `JdbcTemplate`/`JdbcClient` call in the application reads it.
> The actual per-environment feature-toggle mechanism in this codebase today is a per-module `@ConfigurationProperties` boolean — for example `MessageProperties.groupChatEnabled`, whose own Javadoc states plainly that there is no runtime `feature_flags` reader and that this property, not the table's `group_chat` row, is the real switch.
> The row above is therefore a specification for a feature-flag mechanism that has not been built, not a description of current behaviour.
> Do not point a new toggle at this table expecting it to be read; wire it through the owning module's own configuration properties instead, or build a general-purpose runtime reader as its own scoped piece of work.

**Rule**: These tables must never store secrets, private keys, OAuth credentials, database URLs, or any sensitive environment-specific values. Those remain in environment variables.
