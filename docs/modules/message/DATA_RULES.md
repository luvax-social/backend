# Message Module - Data Rules

**Implementation status**: Fully implemented.
Conversation lifecycle, message send/history/delete/read and real-time WebSocket delivery are all in place.
Direct messages no longer produce activity-feed notifications.
Group conversations were removed by V50; messaging is 1-1 only.

---

## Section 1: Canonical Data

| Table | Key Columns | Notes |
|-------|-------------|-------|
| `conversations` | `id`, `created_by`, `last_message_at`, `created_at`, `updated_at`, `direct_pair_key` | A 1-1 conversation thread. `created_by` is SET NULL if the creator deletes their account. `direct_pair_key` is the two participants' `user_id` values in sorted order, and its unique index is what lets `createDirectConversation` reuse an existing conversation instead of creating a duplicate. |
| `conversation_participants` | `conversation_id`, `user_id`, `is_admin`, `joined_at`, `left_at`, `last_read_at` | Membership record for each participant. `left_at IS NOT NULL` means the user has left the conversation. `is_admin` is a holdover from the removed group feature and carries no meaning on a 1-1 conversation. |
| `messages` | `id`, `conversation_id`, `sender_id`, `message_type`, `content`, `media_asset_id`, `shared_post_id`, `shared_story_id`, `reply_to_id`, `is_deleted`, `deleted_at`, `admin_removed_at` | Individual messages. Sender-owned soft-delete via `is_deleted = TRUE` + `deleted_at`, which clears `content` and is irreversible. `admin_removed_at` (V77) is the independent moderation tombstone; it preserves the payload so a restore can return it, and a message is hidden when either tombstone is set. |

`conversations.is_group`, `group_name`, and `group_avatar_url` were removed by V50 along with the group-conversation feature: messaging is 1-1 only now. The columns' data was copied into `archived_group_conversations` before the drop, and the group's participant and message rows were copied into `archived_group_participants` and `archived_group_messages` respectively, so no group history was destroyed by the removal.

These tables cannot be rebuilt from any other source if lost.

---

## Section 2: Derived Data / Cache / Projection

| Data | Location | Rebuilt From | Rebuild Trigger |
|------|----------|--------------|-----------------|
| `conversations.last_message_at` | `conversations` table | Latest `messages.created_at` for the conversation | Trigger `trg_conversation_last_message` (V16) |
| `conversations.updated_at` | `conversations` table | Auto-maintained | Trigger `trg_conversations_updated_at` (V16) |
| Unread message count | Computed at query time | `COUNT(*)` from `messages` where `created_at > conversation_participants.last_read_at` and `is_deleted = FALSE` | Query-time |
| Conversation list cache | Redis | Rebuild from `conversations` joined with `conversation_participants` | Cache miss or TTL expiry |

---

## Section 3: Business Rules

### A. Rules Enforced by the Database

| Rule | Enforced By |
|------|-------------|
| `messages.message_type` must be one of `'text'`, `'image'`, `'video'`, `'post_share'`, `'story_share'` | `message_type` enum |
| `conversation_participants` allows at most one row per (conversation, user) pair | Compound `PRIMARY KEY (conversation_id, user_id)` |
| `messages.shared_post_id` becomes NULL if the shared post is deleted | `ON DELETE SET NULL` |
| `messages.shared_story_id` becomes NULL if the shared story is deleted | `ON DELETE SET NULL` |
| `messages.reply_to_id` becomes NULL if the replied-to message is deleted | `ON DELETE SET NULL` |
| `conversations.created_by` becomes NULL if the creator deletes their account | `ON DELETE SET NULL` |
| Deleting a conversation cascades to participants and messages | `ON DELETE CASCADE` |
| Deleting a user cascades to their `conversation_participants` rows | `ON DELETE CASCADE` on FK to `users` |

### B. Rules Enforced by Application Code

| Rule | Service / Component |
|------|---------------------|
| A user may only send messages to conversations they are an active participant of (`left_at IS NULL`) | `MessageServiceImpl.sendMessage`, `MessageServiceImpl.listHistory`, `MessageServiceImpl.markRead` |
| A conversation has exactly 2 participants; a duplicate request reuses the existing conversation | `ConversationServiceImpl.createDirectConversation` - the only path that creates a conversation, always inserting the actor plus exactly one target; reactivates the caller's membership if they had left |
| A blocked user may not initiate or reply to messages with the blocker | Initiation enforced by `ConversationServiceImpl.assertNotBlocked`, called from `createDirectConversation`; replies enforced by `MessageServiceImpl.assertNotBlockedForDirectMessage`, called from `sendMessage` |
| Message soft-delete sets `is_deleted = TRUE` and `deleted_at = NOW()`, and clears `content` to a tombstone | `MessageServiceImpl.deleteMessage` |
| Only the message sender may delete their own message | `MessageServiceImpl.deleteMessage` |
| `last_read_at` on `conversation_participants` is updated when the user reads the conversation | `MessageServiceImpl.markRead` |
| A message of type `'post_share'` must have `shared_post_id` set; `'story_share'` must have `shared_story_id` set | `MessageServiceImpl.sendMessage` payload validation |
| A message of type `'image'` or `'video'` must have `media_asset_id` set | `MessageServiceImpl.sendMessage` payload validation |
| `user_settings.allow_message_requests` governs whether non-followers can initiate a conversation | `ConversationServiceImpl.assertMessageRequestAllowed`, called from `createDirectConversation` |
| A live WebSocket SUBSCRIBE to a conversation's topic is rejected unless the subscriber is an active participant | `MessageWebSocketAuthInterceptor` |

**`sender_id` cascade behavior** `[FIXED - V32]`:
`sender_id` now uses `ON DELETE SET NULL` instead of `ON DELETE CASCADE` (migration V32).
Deleting a user's account preserves their past messages for the remaining participants instead of destroying them; a null `sender_id` renders as a deleted-user placeholder in message responses.

### C. Scope Simplifications

- Messages use a hybrid soft-delete pattern: `is_deleted BOOLEAN` + `deleted_at TIMESTAMPTZ`. This differs from the `deleted_at`-only pattern used by other modules. Both fields must be set on delete.
- No end-to-end encryption in v1.
- No message reactions.
- No read receipts beyond `last_read_at` at the conversation level.
- Group chat was removed entirely (V50); messaging is 1-1 only. The `feature_flags.group_chat` table row (V18) is a leftover from before the removal - it is decorative, and there is no runtime `feature_flags` reader in this codebase.
- Real-time delivery uses a single in-process `SimpleBroker` (no distributed STOMP relay), so delivery is best-effort per instance; a client recovers any missed event through the message history endpoint on reconnect.

---

## Section 4: Inter-Module Dependencies

| Dependency | Direction | Nature |
|------------|-----------|--------|
| `users` | inbound | `sender_id`, `created_by`, `conversation_participants.user_id` all reference `users.id` |
| `media` | outbound | `messages.media_asset_id` references `media_assets` for image/video messages |
| `post` | outbound | `messages.shared_post_id` references `posts` for shared-post messages |
| `story` | outbound | `messages.shared_story_id` references `stories` for shared-story messages |
| `social` | inbound | Block relationships govern messaging permissions |
| `notification` | none | Direct messages left the activity feed. `MessageNotificationConsumer`, `message.notification.queue`, its DLQ and bindings are retired, and `RetiredQueueCleaner` deletes both queues from the broker at startup. V119 soft-deleted the existing `message` notification rows. The `message` enum value and `user_settings.notify_messages` remain so historical rows and the setting stay readable. |
| `report` | inbound | Reports can target a message via polymorphic `entity_id` |
