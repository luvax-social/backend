# Notification Module - Data Rules

**Implementation status**: Implemented.
The module owns the activity feed: its rows, their actors, the per-user seen watermark, the REST contract under `/api/v1/notifications` and the live topic `/topic/notifications.{userId}`.

---

## Section 1: Canonical Data

| Table | Key Columns | Notes |
|-------|-------------|-------|
| `notifications` | `id`, `recipient_id`, `actor_id`, `type`, `category`, `entity_type`, `entity_id`, `post_id`, `message`, `admin_action_id`, `activity_at`, `read_at`, `aggregation_key`, `is_group_open`, `group_started_at`, `actor_count`, `actor_verified`, `created_at`, `deleted_at` | One feed row. An aggregatable event joins an open group instead of adding a row. `actor_id` is the newest actor and is null for a platform notice. `activity_at` is the feed sort key. `deleted_at` is the soft delete. |
| `notification_actors` | `(notification_id, actor_id)`, `acted_at` | Every actor of a row, one row each (V117). A group has many, every other row with an actor has one, and a platform notice has none. |
| `notification_seen_states` | `user_id`, `seen_activity_at`, `seen_id`, `previous_activity_at`, `previous_id`, `advanced_at` | One row per user (V117, backfilled by V120). Both watermarks are `(activity_at, id)` tuples in feed order. |
| `notifications_pre_overhaul_archive` | every column of `notifications` as it was before V116 | Written once by V116 before the overhaul rewrote any row. Read by nothing; kept so the rewrite can be audited and, if needed, reversed. |

These tables cannot be rebuilt from any other source if lost.

### Columns in detail

- `category` (`notification_category`: `like`, `comment`, `mention`, `follow`, `story`, `message`, `system`) is derived from `type` by `NotificationType.category()` and drives the list filters.
  `message` belongs only to the retired `message` type.
- `entity_type` / `entity_id` is the polymorphic target, shaped by each producer (Section 3B).
  `post_id` carries the post of every post, comment and post-moderation row, so a client can open the post without a second lookup.
  A follow row has neither: its only subject is the actor.
- `message` is the reason text a moderator gave, where the producer sends one.
- `admin_action_id` links a moderation notice to the `admin_actions` row it reports; that link is what offers an appeal.
  A support answer has none.
- `aggregation_key` is `{type}:{targetId}` for `like_post`, `like_comment` and `story_view`, and `follow` for `follow`; null for every other type.
- `is_group_open` and `group_started_at` define the aggregation window (Section 3C).
- `actor_count` is maintained by trigger `trg_notification_actor_count` from `notification_actors`, per the denormalised counter policy.
- `actor_verified` records whether the row's newest actor holds a verified badge; it backs the `verified` filter.

---

## Section 2: Derived Data / Cache / Projection

| Data | Location | Rebuilt From | Rebuild Trigger |
|------|----------|--------------|-----------------|
| `notifications.actor_count` | Column | `COUNT(*)` of `notification_actors` per row | Trigger `trg_notification_actor_count` on every membership insert and delete |
| `notifications.actor_verified` | Column | `users.is_verified` of the row's `actor_id` | Set on every write; rewritten in batches by `user.verification-changed.v1` when a badge is granted or revoked |
| Unseen badge | Computed at query time | Visible rows above `seen`, bounded by `LIMIT 100` | Query time; `99` with `capped = true` means 99+ |
| Pinned follow-request entry | Computed at query time | `follows` with `status = 'pending'` (social module), never notification rows | Query time, on `GET /notifications/state` and in every live envelope |
| Feed hydration (targets, previews, moderation, relationship) | Computed at read time | Owning-module preview services | Every page, every live push |
| Live push | Per-user STOMP topic `/topic/notifications.{userId}` | Cannot be rebuilt: best effort; a client refetches after a reconnect | Outbox events `notification.*` through the `notification.live.events` fanout exchange |

---

## Section 3: Business Rules

### A. Rules Enforced by the Database

| Rule | Enforced By |
|------|-------------|
| `type` is one of the 19 values of `notification_type` | enum |
| `category` is one of the 7 values of `notification_category` | enum (V115) |
| At most one open group per recipient and key | partial unique index `uq_notifications_open_group (recipient_id, aggregation_key) WHERE is_group_open AND deleted_at IS NULL` |
| An actor appears in a row at most once | primary key of `notification_actors` |
| `actor_count` equals the membership count and never goes below zero | trigger `trg_notification_actor_count`, `CHECK (actor_count >= 0)` |
| A seen or previous watermark is a complete tuple or absent | `CHECK` constraints on `notification_seen_states` |
| Deleting a recipient cascades to their rows, memberships and seen state | `ON DELETE CASCADE` |
| Deleting an actor account removes its memberships; `actor_id` becomes null | `ON DELETE CASCADE` on `notification_actors.actor_id`, `ON DELETE SET NULL` on `notifications.actor_id` |

Indexes (V121, built `CONCURRENTLY`; V122 dropped the ones they replace):

| Index | Serves |
|-------|--------|
| `idx_notifications_feed (recipient_id, activity_at DESC, id DESC) WHERE deleted_at IS NULL` | the `all` list, the head, the unseen count |
| `idx_notifications_feed_unread ... WHERE read_at IS NULL AND deleted_at IS NULL` | the `unread` filter |
| `idx_notifications_feed_category (recipient_id, category, activity_at DESC, id DESC)` | the category filters |
| `idx_notifications_feed_verified ... WHERE actor_verified AND deleted_at IS NULL` | the `verified` filter |
| `uq_notifications_open_group` | group serialisation |
| `idx_notifications_aggregation_key ... WHERE aggregation_key IS NOT NULL AND deleted_at IS NULL` | retraction from closed groups |
| `idx_notification_actors_recent (notification_id, acted_at DESC)` | the display actors of a page |
| `idx_notification_actors_actor (actor_id)` | block, unfollow and verification resync by actor |

### B. Producers

Every write goes through `NotificationService.create(NotificationDraft)`, which applies the rules in Section 3C, or through `retract`, `resolveFollowRequest`, `onBlock` and `resyncActorVerified`.

| Type | Producer | Actor | Entity | `post_id` | `message` | `admin_action_id` |
|------|----------|-------|--------|-----------|-----------|-------------------|
| `like_post` | `PostNotificationConsumer` (`post.liked.v1`, `post.unliked.v1`) | liker | `post` | post | - | - |
| `like_comment` | `CommentNotificationConsumer` (`comment.liked.v1`, `comment.unliked.v1`) | liker | `comment` | post | - | - |
| `comment_post`, `reply_comment` | `CommentNotificationConsumer` (`comment.created.v1`) | commenter | `comment` | post | - | - |
| `mention_comment` | `CommentNotificationConsumer` (`comment.created.v1`) | commenter | `comment` | post | - | - |
| `mention_post` | no producer; seed data only | author | `post` | post | - | - |
| `follow`, `follow_request` | `SocialNotificationConsumer` (`user.followed.v1`, `user.follow-requested.v1`) | follower | none | - | - | - |
| `story_view` | `StoryNotificationConsumer` (`story.viewed.v1`) | viewer | `story` | - | - | - |
| `warning` | `AdminNotificationConsumer` (`user.warned.v1`) | none | `warning` | - | - | audit row |
| `post_removed`, `post_restored` | `AdminServiceImpl`, synchronously | none | `post` | post | reason | audit row |
| `report_post_removed` | `AdminServiceImpl` | none | `report` | post | - | audit row |
| `report_dismissed` | `AdminServiceImpl` | none | `report` | - | reason | audit row |
| `comment_removed`, `story_removed`, `message_removed` | `AdminServiceImpl` | none | `admin_action` | - | reason | audit row |
| `support_ticket_update` | `SupportTicketServiceImpl`, `VerificationServiceImpl`, synchronously | none | `support_ticket` or `verification_request` | - | - | - |
| `message` | none since the overhaul | - | - | - | - | - |

Direct messages left the feed.
`message.notification.queue`, its DLQ and bindings are retired; `RetiredQueueCleaner` deletes both queues from the broker at startup, idempotently.
V119 soft-deleted every existing `message` row.
The enum value and `user_settings.notify_messages` stay, because historical rows and the setting remain readable.

Relationship events reach this module through the outbox on `notification.queue`: `user.unfollowed.v1`, `user.follow-request.approved.v1`, `user.follow-request.rejected.v1`, `user.blocked.v1`, and `user.verification-changed.v1` from the support module.
The social module does not call this module directly, because this module already depends on `SocialService` and a direct call would close a bean cycle.

### C. Rules Enforced by Application Code

| Rule | Where |
|------|-------|
| No row for one's own action | `NotificationServiceImpl.create` |
| No row when the type is disabled in `notification_type_configs.is_enabled`; the disabled set is cached for 60 seconds | `NotificationTypePolicy` |
| No row when the recipient turned the category off in `user_settings` (`notify_likes`, `notify_comments`, `notify_follows`, `notify_mentions`); platform notices and story views ignore settings | `NotificationServiceImpl.recipientAllows` |
| No row when actor and recipient are blocked in either direction; a platform notice has no actor and is never suppressed | `NotificationServiceImpl.create` |
| An aggregatable event opens the recipient's group for its key or joins the open one; a group accepts actors for `app.notification.aggregation-window` (default 24 hours) from `group_started_at`, and an older open group is closed lazily by the next write | `NotificationAggregationRepository.upsertGroup` |
| A new member moves the group to that actor and marks it unread again; a repeated member changes nothing | `upsertGroup` |
| `activity_at` is `clock_timestamp()` taken as the last statement of the writing transaction, never the application clock | `touchActivity` |
| A retraction (unlike, unfollow, rejected request) removes only that actor, never moves the row or changes its read state, and soft-deletes a row left with no actor | `retract`, `settle` |
| A block removes each side from the other's groups and pending requests; other rows stay hidden by the read filter while the block lasts | `onBlock` |
| An approved request converts in place to `follow`: position, read state and seen state are kept | `resolveFollowRequest` |
| Consumers act on the relationship as it stands when the event is processed (the like row exists, the follow status), not as the event described it, so redelivered or reordered pairs converge | `PostNotificationConsumer`, `CommentNotificationConsumer`, `SocialNotificationConsumer` |
| A comment notification suppresses a mention of the same person in the same comment; a repeated handle is one mention | `CommentNotificationConsumer` |
| Hydration goes through the owning module's service only: `PostPreviewService`, `CommentPreviewService`, `StoryPreviewService`, `SupportTicketPreviewService`, `ModerationNoticeService`, `UserSummaryService`, `SocialService`; no foreign repository is injected | `NotificationItemAssembler` |
| A moderation notice shows kind, date and a text snippet to the content's author only, never media | `ModerationNoticeService`, `NotificationItemAssembler` |

### D. Visibility

One predicate, `NotificationFeedRepository.VISIBLE`, decides what the list, the head, the unseen count and every live push can show:
the row is the viewer's, is not deleted, and is either a platform notice (`category = 'system'`) or has at least one actor who is active, not deleted, and not blocked in either direction.
The displayed actor count subtracts actors hidden by the same rule.
A target that has become unavailable (deleted, removed, expired, private) keeps the row and reports the target as unavailable instead of hiding it.

### E. Seen, Read and the Badge

- **Read** is per row (`read_at`), set by opening or by `PUT /notifications/{id}/read`, cleared by `DELETE /notifications/{id}/read` or when a group gains a member.
  `PATCH /notifications/read-all` takes an `upTo` tuple and marks only rows at or below it, so rows the client never rendered stay unread.
- **Seen** is per user: `POST /notifications/seen` advances `seen` to a tuple the client rendered.
  The server clamps it to `LEAST(client tuple, row tuple, now())` and never moves it backwards.
- `previous` rotates from `seen` only on the first advance after `app.notification.seen-session-gap` (default 30 minutes) of inactivity, so the "new" section stays stable across reloads within one visit.
- The badge counts visible rows above `seen`, follow-request rows included, bounded by `LIMIT 100`: `count` is at most 99 and `capped` is true beyond it.
- The pinned follow-request entry is read from `follows`, not from rows, and pending requests never appear as list rows under any filter.
- The first page of every filter carries `head`, the newest visible row of the whole feed, follow requests included, so a client can advance `seen` to it.

**Residual watermark race.**
`activity_at` is taken last, just before commit, but a row can still commit a few milliseconds after a concurrent `POST /seen` has read the clock.
Such a row sorts just below the new watermark and is counted as seen without being shown.
The window is commit latency; it is accepted rather than closed with a lock on every write.

### F. Failure Mode

- Producers write through the transactional outbox; consumers deduplicate through `processed_messages`, retry on the ladder in `app.messaging.consumer.*`, and dead-letter to their DLQ: `notification.dlq`, `comment.notification.dlq`, `story.notification.dlq`, `post.notification.dlq`, `admin.notification.dlq`. Inbox markers are retained 14 days (`app.retention.processed-messages`) before a scheduled job purges them.
- The synchronous admin and support producers write in the caller's transaction, so a notice exists exactly when the decision does.
- A live push that fails is logged and dropped; the REST feed is authoritative.

### G. Scope Simplifications

- No TTL: rows accumulate until the recipient deletes them or an actor retraction empties them.
- Soft delete only; nothing hard-deletes a row except the cascade from a deleted recipient.
- No mobile push; `push_tokens` is unused by this module.

---

## Section 4: Inter-Module Dependencies

| Dependency | Direction | Nature |
|------------|-----------|--------|
| `users` | outbound | `UserSummaryService` for actor summaries and the verified flag; `UserNotificationPreferencesService` for `user_settings` toggles |
| `social` | outbound | `SocialService` for block checks, follow status, the relationship block and the pending-request summary |
| `social` | inbound | follow, unfollow, request, approve, reject and block events on `notification.queue` |
| `post` | outbound | `PostPreviewService` for post targets and thumbnails |
| `post` | inbound | `post.liked.v1` and `post.unliked.v1` through `PostNotificationConsumer` on `post.notification.queue` |
| `comment` | outbound | `CommentPreviewService` for comment snippets and tombstones |
| `comment` | inbound | `comment.created.v1`, `comment.liked.v1`, `comment.unliked.v1` through `CommentNotificationConsumer` |
| `story` | outbound | `StoryPreviewService` for story targets and expiry |
| `story` | inbound | `story.viewed.v1` through `StoryNotificationConsumer` |
| `admin` | outbound | `ModerationNoticeService` for the moderation block and appeal eligibility |
| `admin` | inbound | synchronous `create` from `AdminServiceImpl`; `user.warned.v1` through `AdminNotificationConsumer` |
| `support` | outbound | `SupportTicketPreviewService` for ticket targets |
| `support` | inbound | synchronous `create` from `SupportTicketServiceImpl` and `VerificationServiceImpl`; `user.verification-changed.v1` |
| `message` | none | direct messages left the feed |
