# Message Module - Data Rules

**Implementation status**: Fully implemented.
Conversation lifecycle, message send/history/delete/read and real-time WebSocket delivery are all in place.
Direct messages no longer produce activity-feed notifications.
Group conversations were removed by V50; messaging is 1-1 only.

---

## Section 1: Canonical Data

| Table | Key Columns | Notes |
|-------|-------------|-------|
| `conversations` | `id`, `created_by`, `last_message_at`, `created_at`, `updated_at`, `direct_pair_key`, `request_status`, `request_recipient_id`, `request_hidden`, `request_resolved_at` | A 1-1 conversation thread. `created_by` is SET NULL if the creator deletes their account. `direct_pair_key` is the two participants' `user_id` values in sorted order, and its unique index is what lets `createDirectConversation` reuse an existing conversation instead of creating a duplicate. `request_status` (V145, `accepted`, `pending` or `declined`) says whether the recipient has accepted the conversation; every conversation that existed before V145 is `accepted`. `request_recipient_id` is the account asked to accept, kept after acceptance as history. `request_hidden` files a pending request in the recipient's hidden requests folder. `request_resolved_at` records when the request was accepted or declined. A declined conversation has its `direct_pair_key` cleared (retired) when its recipient starts a fresh conversation with the sender, so the pair key always belongs to the live conversation. |
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
| A conversation that is not `accepted` names its recipient, and `request_hidden` is never set on an `accepted` conversation | `CHECK` constraints `chk_conversations_request_recipient` and `chk_conversations_request_hidden` (V145) |

### B. Rules Enforced by Application Code

| Rule | Service / Component |
|------|---------------------|
| A user may only send messages to conversations they are an active participant of (`left_at IS NULL`) | `MessageServiceImpl.sendMessage`, `MessageServiceImpl.listHistory`, `MessageServiceImpl.markRead` |
| Every send first takes a row lock on the conversation (`ConversationRepository.lockById`), so sends into one conversation run one after another and each reads the request state and the sender's message count as the previous one left them | `MessageServiceImpl.sendMessage` |
| While a request is pending, its sender may send exactly one message and it must be text; a second message, or any other type, is refused with `MESSAGE_REQUEST_AWAITING_ACCEPTANCE` (409). A declined request refuses every send with the same code and message, so the sender cannot tell it from a pending one. The sender's message count includes tombstoned messages, so deleting the one allowed message does not buy a second | `MessageServiceImpl.sendMessage` through `InteractionPolicyService` (`MESSAGE_SEND`) |
| A send by the recipient of a request that is not yet accepted accepts it: `request_status` becomes `accepted`, `request_hidden` is cleared and `request_resolved_at` is set in the transaction that stores the message | `MessageServiceImpl.applyRequestOutcome` |
| A requester's first message that matches the recipient's Hidden Words, or that the recipient's interaction limit applies to, sets `request_hidden` on the conversation in the same transaction | `MessageServiceImpl.applyRequestOutcome` |
| A retry carrying an Idempotency-Key that already committed replays the stored response before the policy is asked, so the retry of a requester's one allowed message is not refused for having sent it; the same key with a different payload, reply target included, is `MESSAGE_IDEMPOTENCY_CONFLICT` | `MessageServiceImpl.sendMessage` |
| A new message revives the other participant's hidden membership, except that the recipient of a declined request is never revived | `MessageServiceImpl.reactivateOtherParticipants` |
| A conversation has exactly 2 participants; a duplicate request reuses the existing conversation | `ConversationServiceImpl.createDirectConversation` - the only manual path that creates a conversation, always inserting the actor plus exactly one target; it takes the pair lock first, reads the existing conversation, and reuses an accepted, or the sender's pending or declined, conversation without any permission check (a block still refuses it), reactivating the caller's membership if they had left. A recipient who declined and now writes to the sender has the old conversation retired and a new one created |
| Where a new conversation lands comes from `InteractionPolicyService` (`MESSAGE_INITIATE`): `accepted` when the recipient follows the sender, a pending request (`request_recipient_id` set) when the recipient allows everyone and does not follow the sender, a pending request with `request_hidden` when the recipient's interaction limit applies to the sender, and a refusal when the permission is `nobody` or `following` without a follow | `ConversationServiceImpl.createDirectConversation` |
| A mutual follow settles the pair: a conversation provisioned from it is `accepted`, an existing pending request is promoted to `accepted`, and a declined one is retired before a fresh `accepted` conversation is created | `DirectConversationProvisionerImpl.ensureDirectConversation` |
| The conversation list has three folders. `primary` holds accepted conversations and the caller's own outgoing requests, a declined one included because it must read as pending to its sender. `requests` holds incoming pending, non-hidden requests with at least one message, `hidden_requests` the hidden ones, and an incoming request nobody has written to appears in neither | `ConversationRepository.FOLDER_FILTER` |
| The unread total leaves out incoming requests, and `requestCount` counts incoming pending, non-hidden requests with at least one message | `MessageRepository.countTotalUnreadForUser`, `MessageRepository.countPendingRequests` |
| A blocked user may not initiate or reply to messages with the blocker | Initiation enforced by the policy's block rule through `ConversationServiceImpl.createDirectConversation`; replies enforced by the same block rule through `MessageServiceImpl.sendMessage`, which reads the other participant whatever their `left_at` says so a peer who deleted the conversation and then blocked the sender is still found, and answers with the stealth `CONVERSATION_NOT_FOUND` |
| Message soft-delete sets `is_deleted = TRUE` and `deleted_at = NOW()`, and clears `content` to a tombstone | `MessageServiceImpl.deleteMessage` |
| Only the message sender may delete their own message | `MessageServiceImpl.deleteMessage` |
| `last_read_at` on `conversation_participants` is updated when the user reads the conversation | `MessageServiceImpl.markRead` |
| A message of type `'post_share'` must have `shared_post_id` set; `'story_share'` must have `shared_story_id` set | `MessageServiceImpl.sendMessage` payload validation |
| A message of type `'image'` or `'video'` must have `media_asset_id` set | `MessageServiceImpl.sendMessage` payload validation |
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

---

## Section 5: Observability

The Phase 3 query baseline panel reads `pg_stat_statements` for `messages`, `conversations` and `conversation_participants`, the module's three highest-write-volume tables, to track query-plan drift once real production traffic exists.
No application code in this module reads `pg_stat_statements`; the panel queries it directly through the read-only `luvax_monitor` role.
