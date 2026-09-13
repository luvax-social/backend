# Notification Module — Data Rules

**Implementation status**: Implemented. Service, controller, repository, mapper, and messaging (RabbitMQ consumer and live-push) Java files exist for this module.

---

## Section 1: Canonical Data

| Table | Key Columns | Notes |
|-------|-------------|-------|
| `notifications` | `id`, `recipient_id`, `actor_id`, `type`, `entity_type`, `entity_id`, `post_id`, `is_read`, `read_at`, `created_at` | One row per notification event. `actor_id` is SET NULL if the acting user deletes their account. Polymorphic target via `entity_type` + `entity_id`. `post_id` is an additive, insert-only enrichment: for `COMMENT_POST`, `REPLY_COMMENT`, `LIKE_COMMENT`, and `MENTION_COMMENT` it carries the post the comment belongs to (`entity_id` stays the comment id), so a client can open the post without a second lookup. Null for non-content types and for rows created before this column existed. No FK, consistent with `entity_id`. |

This table cannot be rebuilt from any other source if lost.

---

## Section 2: Derived Data / Cache / Projection

| Data | Location | Rebuilt From | Rebuild Trigger |
|------|----------|--------------|-----------------|
| Unread notification count | Computed at query time | `COUNT(*)` from `notifications` where `recipient_id = ?` and `is_read = FALSE` | Query-time (partial index `idx_notifications_unread` accelerates this) |
| Live push delivery | Per-user STOMP topic (`/topic/notifications.{userId}`), fed via the transactional outbox and a RabbitMQ fanout exchange | Cannot be rebuilt — delivery is best-effort and fire-and-forget; the `notifications` row remains authoritative and a missed push is recovered on the next `GET /notifications` | Row insert in `NotificationServiceImpl.create` |

---

## Section 3: Business Rules

### A. Rules Enforced by the Database

| Rule | Enforced By |
|------|-------------|
| `type` must be one of the 16 values in `notification_type` enum | `notification_type` enum |
| `is_read` defaults to `FALSE` | `DEFAULT FALSE NOT NULL` |
| `actor_id` becomes NULL if the acting user deletes their account | `ON DELETE SET NULL` on `actor_id` FK |
| Deleting a recipient user cascades to all their notifications | `ON DELETE CASCADE` on `recipient_id` FK |
| There is no `updated_at` column; notifications are immutable after creation | Schema design |
| There is no `deleted_at` column; notifications use hard delete only | Schema design |

### B. Rules Enforced by Application Code

| Rule | Service / Component |
|------|---------------------|
| A notification must not be created if the recipient has disabled the relevant notification type in `user_settings` (e.g., `notify_likes = FALSE`) | `NotificationServiceImpl.create` |
| A notification must not be created if the actor is blocked by the recipient | `NotificationServiceImpl.create` |
| A user must not receive a notification for their own actions (e.g., liking their own post) | `NotificationServiceImpl.create` |
| Marking a notification as read sets `is_read = TRUE` and `read_at = NOW()` | `NotificationServiceImpl.markAsRead` |
| Bulk "mark all as read" updates all unread notifications for the recipient | `NotificationServiceImpl.markAllAsRead` |
| Push delivery uses `push_tokens` from the users module; token failures must not block the notification write to PostgreSQL | `[NOT YET IMPLEMENTED]` — mobile push via `push_tokens` is a distinct, unbuilt feature from the in-app WebSocket live push below |
| Notification creation is dispatched asynchronously via RabbitMQ from the source event (follow, comment, story view, moderation warning); the row write itself is synchronous within that consumer's transaction | `SocialNotificationConsumer`, `CommentNotificationConsumer`, `StoryNotificationConsumer`, `AdminNotificationConsumer` |
| A `warning` notification is created with a null `actor_id`, is never suppressed by a user setting, and is never suppressed by a block | `AdminNotificationConsumer`, `NotificationServiceImpl.isTypeEnabled` - a warning comes from the platform rather than a person, and an account that had blocked the moderator would otherwise never learn it had been warned. `notification_type_configs` records it as `is_user_toggleable = FALSE` |
| Stale notifications (e.g., for a deleted post) must be handled gracefully on read — `entity_id` may reference a soft-deleted or hard-deleted entity | `[NOT YET IMPLEMENTED]` |

**Failure Mode**:
- Notifications are created as a result of domain events delivered over RabbitMQ.
- A failed delivery is retried on the ladder in `app.messaging.consumer.*` and then dead-lettered: `notification.dlq` is declared and bound, as are the per-domain `comment.notification.dlq`, `story.notification.dlq`, `message.notification.dlq` and `admin.notification.dlq`.
- A message that exhausts its attempts is therefore held for inspection rather than silently dropped, which is what the earlier text here described.

### C. Scope Simplifications

- Live push is best-effort over `/topic/notifications.{userId}`; the REST list stays authoritative and a client must still poll `GET /notifications` on load and after any reconnect, since a missed push is not replayed.
- No notification grouping (e.g., "Alice and 5 others liked your post"); each event creates one row.
- No TTL or auto-expiry for notifications; they accumulate indefinitely unless explicitly deleted.
- No `deleted_at` column; notification cleanup, if needed, requires hard deletes.

---

## Section 4: Inter-Module Dependencies

| Dependency | Direction | Nature |
|------------|-----------|--------|
| `users` | inbound | `recipient_id` and `actor_id` reference `users.id`; `user_settings` governs notification preferences |
| `post` | inbound | Notifications reference posts via polymorphic `entity_id` when `entity_type = 'post'` |
| `comment` | inbound | Notifications reference comments via polymorphic `entity_id` when `entity_type = 'comment'` |
| `story` | inbound | Notifications reference stories via polymorphic `entity_id` when `entity_type = 'story'` |
| `social` | inbound | Follow events trigger `follow` and `follow_request` notification types |
| `message` | inbound | Message events trigger `message` notification type |
