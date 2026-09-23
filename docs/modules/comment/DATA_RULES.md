# Comment Module — Data Rules

**Implementation status**: Fully implemented.
Threaded comment CRUD (create with HTTP idempotency, edit, soft-delete subtree), likes, content moderation, real-time WebSocket fanout, a Redis recent-comment cache, and notification fanout are all in place, with unit and integration coverage.

---

## Section 1: Canonical Data

| Table | Key Columns | Notes |
|-------|-------------|-------|
| `comments` | `id`, `post_id`, `user_id`, `parent_id`, `root_id`, `depth`, `content`, `moderation_status`, `edited_at`, `deleted_at` | Core comment entity. Adjacency list with `root_id` and `depth` for efficient subtree queries. Soft-deleted via `deleted_at`. `edited_at` records when the content was last changed (V45). |
| `comment_likes` | `user_id`, `comment_id`, `created_at` | One row per (user, comment) pair; compound PK prevents duplicate likes. Canonical like signal for comments. |
| `comment_write_idempotency` | `id`, `user_id`, `idempotency_key`, `request_hash`, `response_body`, `created_at` | Caches the first response for a `(user_id, idempotency_key)` pair so a retried create returns the original result instead of a duplicate comment (V27). |

`comments` and `comment_likes` cannot be rebuilt from any other source if lost.
`comment_write_idempotency` is a durable de-duplication ledger, not derived data: its rows cannot be reconstructed, but losing them degrades only retry safety, not comment content.

---

## Section 2: Derived Data / Cache / Projection

| Data | Location | Rebuilt From | Rebuild Trigger |
|------|----------|--------------|-----------------|
| `comments.like_count` | `comments` table | `COUNT(*)` from `comment_likes` where `comment_id = comment.id` | Trigger `trg_comment_like_count` (V16) |
| `comments.reply_count` | `comments` table | `COUNT(*)` from `comments` where `parent_id = comment.id` and `deleted_at IS NULL` | Trigger `trg_comment_reply_count` (V16) |
| `comments.updated_at` | `comments` table | Auto-maintained | Trigger `trg_comments_updated_at` (V16) |
| `posts.comment_count` | `posts` table | `COUNT(*)` from `comments` where `post_id = post.id` and `deleted_at IS NULL` | Trigger `trg_post_comment_count` (V16) |
| Recent-comment page cache | Redis, key prefix `comment:recent:v3:` | Re-read from `comments` | Cache miss or 300-second TTL expiry |

---

## Section 3: Business Rules

### A. Rules Enforced by the Database

| Rule | Enforced By |
|------|-------------|
| `depth` must be between 0 and 10 (inclusive) | `CHECK (depth BETWEEN 0 AND 10)` (V07) |
| `like_count` and `reply_count` are non-negative | `CHECK (column >= 0)` (V07) |
| `comment_likes` allows at most one like per (user, comment) pair | Compound `PRIMARY KEY (user_id, comment_id)` (V07) |
| At most one cached response per user and idempotency key | `UNIQUE (user_id, idempotency_key)` (V27) |
| `moderation_status` defaults to `approved` | `VARCHAR(20) NOT NULL DEFAULT 'approved'` (V26) |
| Deleting a post cascades to all its comments | `ON DELETE CASCADE` on `comments.post_id` |
| Deleting a parent comment cascades to all its child replies | `ON DELETE CASCADE` on `comments.parent_id` and `comments.root_id` |
| Deleting a user cascades to their comments | `ON DELETE CASCADE` on `comments.user_id` |

`moderation_status` is a `VARCHAR`, not a PostgreSQL enum, so adding a state needs no enum migration.
This is a deliberate departure from the enum-as-constraint-layer rule in `GLOBAL_RULES.md`; the tradeoff is that invalid states are not rejected by the database.

### B. Rules Enforced by Application Code

| Rule | Service / Component |
|------|---------------------|
| Top-level comments have `depth = 0`, `parent_id IS NULL`, `root_id IS NULL`. No CHECK constraint enforces this combination. | `CommentServiceImpl.create` |
| `root_id` is set to the top-level ancestor's `id` for replies at any depth | `CommentServiceImpl.create` — `parent.getRootId() != null ? parent.getRootId() : parent.getId()` |
| `depth` is set to `parent.depth + 1` when creating a reply | `CommentServiceImpl.create` |
| A comment whose `depth` would exceed 10 is rejected before the database call | `CommentServiceImpl.create` — throws `COMMENT_DEPTH_EXCEEDED` (400) |
| A user may like their own comment, matching post likes | `CommentServiceImpl.likeComment` — no owner check |
| Soft delete sets `deleted_at` on the comment **and its whole subtree** in one statement | `CommentRepository.softDeleteSubtree`, called by `CommentServiceImpl.deleteComment` |
| The delete reports how many comments it removed, counting the target itself | `CommentServiceImpl.deleteComment` returns the statement's own affected-row count as `deletedCommentCount`, and puts the same number in `comment.deleted.v1` |
| The same number is available before the delete, under the same name and the same definition | `CommentServiceImpl.getDeletionScope` via `CommentRepository.countSubtree` |
| A caller without the authority to delete a comment cannot read its deletion scope | `CommentServiceImpl.getDeletionScope` — owner or admin, answering `COMMENT_NOT_FOUND` (404) rather than 403 to anyone else |
| Soft-deleted comments are excluded from every query | `@SQLRestriction("deleted_at IS NULL")` on the `Comment` entity |
| Only the comment owner may edit their comment | `CommentServiceImpl.edit` — throws `COMMENT_FORBIDDEN` (403) |
| An edit stamps `edited_at`; nothing else ever writes it | `CommentServiceImpl.editComment`, the only caller of `Comment.setContent` in `src/main` |
| `updated_at` is not an edit signal and must never be compared against `created_at` to derive one | `trg_comments_updated_at` (V16) fires on any row change, including the counter updates `trg_comment_like_count` and `trg_comment_reply_count` issue |
| Only the comment owner **or an admin** may soft-delete a comment | `CommentServiceImpl.delete` — owner check with an admin override |
| Content is normalized before moderation and persistence | `CommentContentNormalizer` |
| Content failing a blocked-word rule is rejected on create and on edit | `CommentModerationServiceImpl.check` — throws `COMMENT_MODERATION_REJECTED` (422) |
| A repeat create within the slow-mode window is rejected | `CommentServiceImpl` — throws `COMMENT_SLOW_MODE_ACTIVE` (429); disabled when `slowModeSeconds` is 0 |
| An idempotency key reused with a different request payload is rejected | throws `COMMENT_IDEMPOTENCY_CONFLICT` (409) |
| Flat reply lists filter by `root_id`; no `WITH RECURSIVE` CTE is used | `CommentRepository` |
| At most 10 `@mention` usernames are resolved per comment | `CommentServiceImpl` — `MENTION` pattern `@([a-zA-Z0-9_]{1,30})`, `MAX_MENTIONS = 10` |
| Mention usernames resolve only to non soft-deleted users | `CommentUserRepository.findByUsernameAndDeletedAtIsNull` |
| Commenting on a post generates a `comment_post` notification for the post owner | `CommentNotificationConsumer`, from `comment.created.v1` |
| Replying to a comment generates a `reply_comment` notification for the parent comment owner | `CommentNotificationConsumer`, from `comment.created.v1` |
| User mentions in comment `content` generate `mention_comment` notifications | `CommentNotificationConsumer`, from the `mentionedUserIds` event field |
| Liking a comment generates a `like_comment` notification | `CommentNotificationConsumer`, from `comment.liked.v1` |
| No notification is created when the actor is also the recipient | `NotificationServiceImpl.create` — a single general guard covering every notification type, so a self-like or a self-reply produces no row |
| A user may only comment on a post that is visible to them | `CommentAccessPolicyServiceImpl.assertCanComment` — delegates to `PostVisibilityService.isVisibleTo`, throws `POST_COMMENTING_RESTRICTED` |
| The viewer's per-row like state is batch-resolved, never per row | `CommentViewerStateServiceImpl` |

Notifications are delivered asynchronously through the transactional outbox, not written inline.
`CommentServiceImpl` enqueues a `DomainEventEnvelope` in the same transaction as the comment write; `CommentNotificationConsumer` creates the notification rows after the broker delivers the event.

### C. Read Ordering and Pagination

Both list endpoints paginate by keyset over the `(created_at, id)` tuple, newest first.
The cursor is the opaque base64url encoding of that tuple and carries no other field.
`created_at` alone is not unique, so `id` is part of the sort key and of the cursor; without it a tie group straddling a page boundary loses or repeats rows.

| Rule | Enforced By |
|------|-------------|
| Replies are ordered newest-first by `(created_at, id)` on every page, with no pinned block | `CommentRepository.findFirstReplies`, `findRepliesBefore` |
| Top-level comments are ordered newest-first by `(created_at, id)` on every page | `CommentRepository.findFirstTopLevelExcluding`, `findTopLevelBefore` |
| The **first page only** of a post's top-level comments is preceded by up to three pinned comments, ordered by `(like_count, created_at, id)` descending, in the default `top` sort mode | `CommentRepository.findTopLikedTopLevel`, capped by `CommentServiceImpl.PINNED_COMMENT_COUNT` |
| `sort=newest` suppresses the pinned block entirely: nothing is prepended and nothing is excluded, so the comments `top` would have pinned appear in their chronological position | `CommentSortOrder.NEWEST` resolves to an empty pinned list and an empty exclusion array; the same two queries serve both modes |
| An unrecognised `sort` value is rejected rather than falling back to the default | `CommentSortOrder.from` throws `BAD_REQUEST` (400) naming the accepted values |
| A cursor is bound to the sort mode that issued it and is rejected under the other | Separate `CursorScope` tags, `cmt` for `top` and `cmtn` for `newest`; a cross-mode replay fails the scope check with `INVALID_CURSOR` (400) |
| A comment needs at least one like to be pinned | `like_count > 0` in `findTopLikedTopLevel` |
| A pinned comment must be top-level, approved, and not soft-deleted | The eligibility predicate of `findTopLikedTopLevel`, matching the partial predicate of `idx_comments_post_top_liked` (V41) |
| A pinned comment appears exactly once across the whole paginated stream | The pinned ids are resolved on every page and filtered in SQL by `findFirstTopLevelExcluding` and `findTopLevelBefore`, so `LIMIT` still yields a full body page and a pinned comment old enough to fall on a later page is not returned a second time |
| The pinned block is additional to the requested `limit`, not counted against it | `CommentServiceImpl.toPage` - the first page returns up to `limit + 3` items |
| `startCursor` and `endCursor` are derived from the newest-first body only | `CommentServiceImpl.toPage` - deriving them from the pinned block would seek the next page to an arbitrary position |
| `CommentResponse.pinned` marks membership of the pinned block; it is never true on page two, on a single-comment response, or anywhere under `sort=newest` | `CommentResponse.asPinned`, applied only to the pinned rows of the first page, and there is no pinned block at all under `newest` |

`like_count` is deliberately absent from the cursor, in both sort modes.
It is mutable, so a keyset over it would let rows cross a page boundary between requests and be lost or repeated.
Pinning a fixed-size block to the first page is what keeps the ranking visible without putting a mutable column in the sort key of the paginated stream.
The two sort modes therefore differ only in whether that block is computed, never in the ordering of the stream itself.

### D. Scope Simplifications

- Comment depth is capped at 10.
  The cap is enforced both by the database `CHECK` constraint and at the service layer before the insert, so a rejected reply returns `COMMENT_DEPTH_EXCEEDED` rather than a constraint violation.
- Instagram-style UI shows only two levels (top-level plus direct replies).
  Deeper nesting is stored; rendering depth is a client concern.
- No comment edit history is stored; only the current `content` and the `edited_at` timestamp of the most recent change are retained.
  This differs from the `post` module, which has a `post_edit_history` table (V22).
  A client can tell that a comment was edited and when, but not what it used to say.
- Moderation is blocked-word matching only.
  There is no classifier, no review queue, and no appeal path; `moderation_status` exists to support one later without a schema change.
- The Redis recent-comment cache is best-effort.
  A cache failure degrades to a database read; it never fails the request.
- The pinned exclusion set is recomputed on every page rather than carried in the cursor.
  If a like arrives between two page requests and changes which comments are pinned, a comment can be missed or repeated once across that boundary.
  The alternative was to carry the pinned ids in the cursor, which would change its wire format for a window bounded to three rows and only while a like lands mid-pagination.
  The recompute costs one index seek per page, which `CommentPinnedTopCommentsIT` pins at an exact statement count.
- The pre-delete deletion scope is an estimate, not a reservation.
  The subtree can grow or shrink between the scope call and the delete, and no lock is taken to prevent that.
  The confirmation dialogue shows the estimate; the delete's own return value is authoritative.
  Closing the gap would mean locking a subtree across two requests, which costs more than the discrepancy it prevents.
- The deletion scope counts every comment the delete would remove, including replies by users the caller has blocked.
  Filtering those out would make the number disagree with what the delete does, which is the defect the endpoint exists to close.
- Top comments are pinned to the first page only, rather than the whole list being ranked by like count.
  A full ranking would put a mutable column in the keyset sort key, which is the defect class the `(created_at, id)` cursor exists to prevent.
  The accepted degradation is that a highly-liked comment is not surfaced anywhere on page two onward.

---

## Section 4: Inter-Module Dependencies

| Dependency | Direction | Nature |
|------------|-----------|--------|
| `post` | inbound | Every comment belongs to a `post_id`; the `comment_count` trigger updates `posts`; the comment-create gate delegates to `PostVisibilityService` |
| `users` | inbound | Every comment belongs to a `user_id`; `@mention` usernames resolve against `users` |
| `social` | inbound | Block and follow state reach comments through the post visibility policy |
| `notification` | outbound | `comment.created.v1`, `comment.liked.v1` and `comment.unliked.v1` drive `comment_post`, `reply_comment`, `mention_comment` and `like_comment` notifications; a mention of the person the comment already notifies is dropped, and an unlike retracts the liker from the group |
| `notification` | inbound | `CommentPreviewService` gives the notification feed each comment's snippet and tombstone state; `GET /api/v1/comments/{commentId}/context` returns a comment with every ancestor so a notification can open a reply wherever it sits |
| `report` | inbound | Reports can target a comment via polymorphic `entity_id` |
| `admin` | inbound | Moderator remove and restore actions target comments and write an `admin_actions` audit row |
