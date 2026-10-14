# Comment Module — Data Rules

**Implementation status**: Fully implemented.
Threaded comment CRUD (create with HTTP idempotency, edit, soft-delete subtree), likes, content moderation, real-time WebSocket fanout, a Redis recent-comment cache, and notification fanout are all in place, with unit and integration coverage.

---

## Section 1: Canonical Data

| Table | Key Columns | Notes |
|-------|-------------|-------|
| `comments` | `id`, `post_id`, `user_id`, `parent_id`, `root_id`, `depth`, `content`, `moderation_status`, `hidden_reason`, `edited_at`, `deleted_at` | Core comment entity. Adjacency list with `root_id` and `depth` for efficient subtree queries. Soft-deleted via `deleted_at`. `edited_at` records when the content was last changed (V45). `moderation_status` is `approved` or `hidden`, and `hidden_reason` (`hidden_words`, `limited_interaction` or `hidden_parent`) is set exactly when it is `hidden` (V143). |
| `comment_likes` | `user_id`, `comment_id`, `created_at` | One row per (user, comment) pair; compound PK prevents duplicate likes. Canonical like signal for comments. |
| `comment_write_idempotency` | `id`, `user_id`, `idempotency_key`, `request_hash`, `response_body`, `created_at` | Caches the first response for a `(user_id, idempotency_key)` pair so a retried create returns the original result instead of a duplicate comment (V27). |

`comments` and `comment_likes` cannot be rebuilt from any other source if lost.
`comment_write_idempotency` is a durable de-duplication ledger, not derived data: its rows cannot be reconstructed, but losing them degrades only retry safety, not comment content.

---

## Section 2: Derived Data / Cache / Projection

| Data | Location | Rebuilt From | Rebuild Trigger |
|------|----------|--------------|-----------------|
| `comments.like_count` | `comments` table | `COUNT(*)` from `comment_likes` where `comment_id = comment.id` | Trigger `trg_comment_like_count` (V16) |
| `comments.reply_count` | `comments` table | `COUNT(*)` from `comments` where `parent_id = comment.id`, `deleted_at IS NULL`, `admin_removed_at IS NULL` and `moderation_status = 'approved'` | Trigger `trg_comment_reply_count` (V16, redefined by V95 and V143) |
| `comments.updated_at` | `comments` table | Auto-maintained | Trigger `trg_comments_updated_at` (V16) |
| `posts.comment_count` | `posts` table | `COUNT(*)` from `comments` where `post_id = post.id`, `deleted_at IS NULL`, `admin_removed_at IS NULL` and `moderation_status = 'approved'` | Trigger `trg_post_comment_count` (V16, redefined by V95 and V143) |
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
| `hidden_reason` is present exactly when `moderation_status` is `hidden` | `CHECK ((moderation_status = 'hidden') = (hidden_reason IS NOT NULL))`, constraint `chk_comments_hidden_reason` (V143) |
| A hidden comment is counted by neither `posts.comment_count` nor its parent's `reply_count`, and unhiding it counts it | Triggers `fn_post_comment_count` and `fn_comment_reply_count` as V143 redefined them, which count a row only while it is approved and carries neither tombstone |
| Deleting a post cascades to all its comments | `ON DELETE CASCADE` on `comments.post_id` |
| Deleting a parent comment cascades to all its child replies | `ON DELETE CASCADE` on `comments.parent_id` and `comments.root_id` |
| Deleting a user cascades to their comments | `ON DELETE CASCADE` on `comments.user_id` |

`moderation_status` is a `VARCHAR`, not a PostgreSQL enum, so adding a state needs no enum migration.
This is a deliberate departure from the enum-as-constraint-layer rule in `GLOBAL_RULES.md`; the tradeoff is that invalid states are not rejected by the database.
The one constraint on it is the pairing with `hidden_reason`, so a status other than `approved` or `hidden` is still accepted by the database.
V143 refuses to run while any existing row is not `approved`, with a message naming the count, before it adds that constraint.

### B. Rules Enforced by Application Code

| Rule | Service / Component |
|------|---------------------|
| Top-level comments have `depth = 0`, `parent_id IS NULL`, `root_id IS NULL`. No CHECK constraint enforces this combination. | `CommentServiceImpl.create` |
| `root_id` is set to the top-level ancestor's `id` for replies at any depth | `CommentServiceImpl.create` — `parent.getRootId() != null ? parent.getRootId() : parent.getId()` |
| `depth` is set to `parent.depth + 1` when creating a reply | `CommentServiceImpl.create` |
| A comment whose `depth` would exceed 10 is rejected before the database call | `CommentServiceImpl.create` — throws `COMMENT_DEPTH_EXCEEDED` (400) |
| A user may like their own comment, matching post likes | `CommentServiceImpl.likeComment` — no owner check |
| Soft delete sets `deleted_at` on the comment **and its whole subtree** in one statement | `CommentRepository.softDeleteSubtree`, called by `CommentServiceImpl.deleteComment` |
| The delete reports how many of the comments it removed the caller can see, counting the target itself; that is every comment for an administrator or the owner of the post, and otherwise the approved comments plus the caller's own hidden ones, so a hidden reply by another user is never counted; a comment carrying `admin_removed_at` is never counted for anyone but them | `CommentServiceImpl.deleteComment` returns the statement's own affected-row count to an administrator or the post owner and `CommentRepository.countSubtreeVisibleTo` to anyone else |
| `comment.deleted.v1` carries the statement's full affected-row count as `deletedCommentCount` for the record, and `visibleDeletedCount` for live viewers | `CommentServiceImpl.deleteComment` |
| The same number is available before the delete, under the same name and the same definition, for the same caller | `CommentServiceImpl.getDeletionScope` via `CommentRepository.countSubtree` for an administrator or the post owner and `countSubtreeVisibleTo` for anyone else |
| A caller without the authority to delete a comment cannot read its deletion scope | `CommentServiceImpl.getDeletionScope` — owner or admin, answering `COMMENT_NOT_FOUND` (404) rather than 403 to anyone else |
| Soft-deleted comments are excluded from every query | `@SQLRestriction("deleted_at IS NULL")` on the `Comment` entity |
| Only the comment owner may edit their comment | `CommentServiceImpl.edit` — throws `COMMENT_FORBIDDEN` (403) |
| An edit stamps `edited_at`; nothing else ever writes it | `CommentServiceImpl.editComment`, the only caller of `Comment.setContent` in `src/main` |
| `updated_at` is not an edit signal and must never be compared against `created_at` to derive one | `trg_comments_updated_at` (V16) fires on any row change, including the counter updates `trg_comment_like_count` and `trg_comment_reply_count` issue |
| Only the comment owner **or an admin** may soft-delete a comment | `CommentServiceImpl.delete` — owner check with an admin override |
| Content is normalized before moderation and persistence | `CommentContentNormalizer` |
| Content that is empty, longer than 2200 characters, an all-capitals message over 50 characters, or a repeat of one word more than three times is rejected on create and on edit | `CommentModerationServiceImpl.check` - throws `COMMENT_MODERATION_REJECTED` (422) |
| A word list is not a moderation rule here: configured blocked words were retired, and the platform-wide Hidden Words registry hides a matching comment from the account it is aimed at instead of rejecting it from its author | `HiddenWordService` in the social module |
| Startup fails while `app.comment.blocked-words` is still configured, with a message naming the administrator Hidden Words registry, so a retired list can never be dropped silently | `LegacyBlockedWordsGuard` |
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
| A user may only comment on a post that is visible to them; this is a read-visibility gate and carries no interaction rule | `CommentAccessPolicyServiceImpl.assertCanComment`, which delegates to `PostVisibilityService.isVisibleTo` and throws `POST_COMMENTING_RESTRICTED` |
| Every create asks the interaction policy with `COMMENT`, the post owner as target and the parent comment's author as secondary target, after the spam rules and before the idempotency key is reserved | `CommentServiceImpl.doCreateComment`, `InteractionPolicyService.evaluate` |
| A denied create answers `POST_COMMENTING_RESTRICTED` (403) when the actor and the post owner are blocked, and `COMMENT_NOT_FOUND` (404) for any other denial, which is a block with the parent comment's author: the replier must not learn that author blocked them | `CommentServiceImpl.refusalFor` |
| A comment the post owner's Hidden Words or interaction limit filters is persisted with `moderation_status = 'hidden'` and `hidden_reason` `hidden_words` or `limited_interaction`; the policy's `HIDDEN_WORD` maps to the first, `LIMITED_NON_FOLLOWER` and `LIMITED_RECENT_FOLLOWER` to the second | `CommentHiddenReason.from` |
| The create response to the author is built the same way whether the comment is hidden or not and carries no hidden marker, because the author is not told | `CommentServiceImpl.doCreateComment`; `CommentResponse` has no status component |
| `comment.created.v1` carries `visibility` (`visible` or `hidden`); `comment.edited.v1` carries it too, for the same reason | `CommentServiceImpl.enqueueCreated`, `CommentServiceImpl.editComment` |
| Each distinct mentioned account is evaluated once with `MENTION` after the block filter; `DENY` and `ALLOW_HIDDEN` both drop it from `mentionedUserIds` so it is never notified, and only `ALLOW` keeps it | `CommentServiceImpl.resolveMentions` |
| Every edit runs the `COMMENT` policy with the new content, whether the comment is approved or hidden; a denial refuses the edit with the same mapping as a create, so an author the post owner blocked cannot add text to a hidden comment, and `ALLOW_HIDDEN` switches an approved comment to hidden with the policy reason, or replaces the `hidden_parent` reason of a hidden reply with the policy reason so releasing its parent cannot release text the owner's own rules filter | `CommentServiceImpl.applyInteractionPolicyToEdit` |
| An edit never makes a hidden comment approved; it keeps the recorded reason except that `hidden_parent` becomes `hidden_words` or `limited_interaction` when the policy hides the new text in its own right, and a `hidden_parent` reply the edit leaves allowed stays `hidden_parent`; only the post owner unhides | `CommentServiceImpl.applyInteractionPolicyToEdit` |
| The post owner's own comments are always allowed, because the policy answers `SELF` for an actor acting on themselves | `InteractionPolicyServiceImpl` |
| A Hidden Words entry or an interaction limit decides only comments written after it is in force; adding a word or turning on a limit leaves existing comments approved, and removing a word or turning a limit off leaves a hidden comment hidden | `HiddenWordsNotRetroactiveIT`, `LimitNotRetroactiveIT` |
| The viewer's per-row like state is batch-resolved, never per row | `CommentViewerStateServiceImpl` |
| A hidden comment is visible to its author only; every by-id path answers anyone else with `COMMENT_NOT_FOUND`, exactly as for a comment that does not exist: like, unlike, the replies list, the context chain, a reply naming it as `parentId`, an edit and a delete | `CommentServiceImpl.findVisibleToActor`, `isConcealedFrom` and `isListable` |
| An admin may still delete a hidden comment, and the owner of a hidden comment may edit, delete and like it | `CommentServiceImpl.deleteComment` |
| The author of a hidden comment may reply to it, and the reply goes through the interaction policy like any comment; the reply is saved hidden, so a visible reply never carries a hidden parent's id into counters, lists or live frames | `CommentServiceImpl.doCreateComment` |
| A reply hidden only because its parent is hidden carries `hidden_reason = 'hidden_parent'`, a reason the interaction policy never produces; a reply the policy also hides keeps the policy's own reason, `hidden_words` or `limited_interaction` | `CommentServiceImpl.doCreateComment`, `CommentHiddenReason.HIDDEN_PARENT` |
| The post owner lists every hidden comment of the post, whatever its reason, newest first by the `(created_at, id)` keyset, each row with its author summary and `hiddenReason`; comments by an account in a block with the owner, soft-deleted comments and moderator-removed comments are left out | `CommentServiceImpl.listHiddenComments`, `CommentRepository.findFirstHiddenForPost`, `findHiddenForPostBefore`, `HiddenCommentResponse` |
| Anyone but the post owner, the hidden comment's own author included, is answered `POST_NOT_FOUND` by the review list exactly as for a post that does not exist; an anonymous caller is refused by authentication | `CommentServiceImpl.listHiddenComments` |
| Only the post owner unhides; an administrator or moderator uses the moderation endpoints, and an edit never approves a hidden comment | `CommentServiceImpl.unhideComment` |
| An unhide answers `COMMENT_NOT_FOUND` unless the comment is hidden, not deleted, not removed by a moderator, on a post the caller owns, and written by an account not in a block with the caller; the same answer covers a missing comment, so it reveals nothing | `CommentServiceImpl.unhideComment` |
| An unhide of a comment whose own parent is still hidden is refused with `COMMENT_PARENT_STILL_HIDDEN` (409); the owner unhides the parent first, which releases a `hidden_parent` reply with it | `CommentServiceImpl.assertParentReleased` |
| The unhide is one conditional `UPDATE` that matches only a hidden, undeleted, unremoved comment on the owner's post, so of two concurrent unhides exactly one succeeds and the other answers `COMMENT_NOT_FOUND` | `CommentRepository.approveHiddenForPostOwner` |
| Unhiding a comment also releases its descendants: a recursive walk follows only rows whose `hidden_reason` is `hidden_parent`, so a descendant hidden for its own reason stays hidden and so does everything beneath it | `CommentRepository.approveHiddenParentReplies` |
| The counters follow an unhide through the V143 triggers: the post's `comment_count` and the parent's `reply_count` rise by one for each comment released, and application code writes neither | `trg_post_comment_count`, `trg_comment_reply_count` |
| An unhide emits one `comment.unhidden.v1` for the unhidden comment and one for each released descendant, the target first, each carrying `postId`, `commentId`, `commentOwnerId`, `depth`, `comment` (the broadcast projection) and `parentId` and `rootId` when set | `CommentServiceImpl.enqueueUnhidden` |
| An unhide creates no notification: the author was never told the comment was hidden and is not told it was released, and the accounts it mentions or answers were never notified of it; `comment.unhidden.v1` is bound to the live tier through `comment.#` and not to `comment.notification.queue` | `CommentRabbitBindingConfig` |
| The live consumer sends `comment.unhidden.v1` to the post's viewers as it would a created comment, dropping it for every account in a block with the comment's author | `CommentLiveFanoutConsumer.frameFor`, `CommentLiveBlockFilterInterceptor` |
| A comment persisted hidden enqueues `comment.hidden.v1` in the same transaction, once at creation (reason `hidden_words`, `limited_interaction` or `hidden_parent`) and once more only when an edit moves an approved comment to hidden; an edit of a comment that was already hidden, including a `hidden_parent` reply that takes a policy reason, enqueues nothing. The payload is `postId`, `commentId` and `reason`, the actor is the commenter, and the event is an internal analytics signal | `CommentServiceImpl.enqueueHidden` |
| `comment.hidden.v1` reaches no client: the live consumer returns no frame for it even though `comment.#` is bound to the live tier, and no notification queue is bound to it, because the author is never told a comment was hidden. Its only consumer is the recommendation module's import consumer, which records it as the `comment_hidden` user event, and `comment.unhidden.v1` is recorded there as `comment_unhide` | `CommentLiveFanoutConsumer.frameFor`, `RecommendationRabbitBindingConfig` |
| `comment.liked.v1` carries `visibility` like the created and edited events, so the live consumer can skip a hidden comment's likes | `CommentServiceImpl.likeComment`, `unlikeComment` |
| The delete event carries both the full subtree count and `visibleDeletedCount`, the approved comments with neither tombstone that the delete removes, counted before the soft delete in the same transaction | `CommentServiceImpl.deleteComment`, `CommentRepository.countVisibleSubtree` |
| The HTTP response to the deleter and the live frame use different counts: the response counts what the deleter can see, the frame counts what every viewer can see | `CommentDeletionScopeResponse` and `visibleDeletedCount` through `CommentServiceImpl.deleteComment` |
| The edited event carries `previousVisibility` and `rootId`, so a live removal frame can say whether viewers held the comment | `CommentServiceImpl.editComment` |

Notifications are delivered asynchronously through the transactional outbox, not written inline.
`CommentServiceImpl` enqueues a `DomainEventEnvelope` in the same transaction as the comment write; `CommentNotificationConsumer` creates the notification rows after the broker delivers the event.

### C. Read Ordering and Pagination

Both list endpoints paginate by keyset over the `(created_at, id)` tuple, newest first.
The cursor is the opaque base64url encoding of that tuple and carries no other field.
`created_at` alone is not unique, so `id` is part of the sort key and of the cursor; without it a tie group straddling a page boundary loses or repeats rows.

| Rule | Enforced By |
|------|-------------|
| A reply or top-level list shows an approved comment to everyone and a hidden comment to its author only, by the predicate `moderation_status = 'approved' OR (moderation_status = 'hidden' AND user_id = :viewerId)`; a null viewer matches no author and sees approved comments only, and nothing marks the author's own hidden row | `CommentRepository.findFirstTopLevelExcluding`, `findTopLevelBefore`, `findFirstReplies`, `findRepliesBefore` |
| The first-page top-level query still seeks `idx_comments_post_root_id` (V36) with the `OR` predicate, pinned by an `EXPLAIN` assertion | `CommentKeysetRowLossIT.firstTopLevelPage_withTheViewerOrPredicate_stillUsesTheKeysetIndex` |
| A hidden comment is never pinned, not even for its author | `findTopLikedTopLevel` keeps `moderation_status = 'approved'` |
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

### C2. Hidden Comments on Live, Notification and Report Paths

A hidden comment is shown to its author only, and the author must not be able to tell it is hidden.
Nobody else may learn that it exists, so every delivery path that carries a comment checks its visibility.

| Rule | Enforced By |
|------|-------------|
| A hidden comment produces no `comment_post`, `reply_comment` or `mention_comment` notification, and no `like_comment` notification, for any recipient | `CommentNotificationConsumer.dispatch` returns early for `visibility = hidden` on the created and liked events |
| A hidden comment produces no live frame: its created and liked events are skipped | `CommentLiveFanoutConsumer.frameFor` |
| An edit that leaves a comment hidden is sent to viewers as a removal in the shape of a `comment.deleted.v1` frame for that one comment, never as the edit, and never with the text | `CommentLiveFanoutConsumer.frameFor` |
| That removal carries `deletedCommentCount` 1 when `previousVisibility` is `visible` and 0 otherwise, so an event without the field is treated as already hidden, the choice that cannot name a comment no viewer held | `CommentLiveFanoutConsumer.frameFor` |
| The removal frame is not delivered to the author's own sessions, because the author's view of their comment must not change | The author is added to the dropped-viewer set that `CommentLiveBlockFilterInterceptor` already applies per session |
| A removal frame for a comment no viewer held (a delete with `visibleDeletedCount` 0, or an edit of a comment that was already hidden) carries exactly `postId`, `commentId` and `deletedCommentCount` 0, with no `commentOwnerId`, `rootId` or `parentId`, because each of those would identify the hidden comment's author or thread | `CommentLiveFanoutConsumer.noViewerRemoval` |
| The live delete frame forwards `visibleDeletedCount` as its `deletedCommentCount` and carries no other count, so viewers cannot learn how many hidden comments a delete removed; an event without the field reports 0 | `CommentLiveFanoutConsumer.frameFor` |
| The post owner may report a hidden comment, and the author's attempt is refused with `REPORT_SELF_NOT_ALLOWED` as for a visible comment; anyone else is answered `REPORT_TARGET_NOT_FOUND` | `ReportTargetRepositoryImpl.findOwnerId`, see the report module's data rules |
| `posts.comment_count` and `reply_count` come from triggers that ignore hidden rows, so an author sees a count one lower than the comments they can read; this is accepted and is never patched on the client | Triggers `fn_post_comment_count` and `fn_comment_reply_count` (V143) |

### D. Scope Simplifications

- Comment depth is capped at 10.
  The cap is enforced both by the database `CHECK` constraint and at the service layer before the insert, so a rejected reply returns `COMMENT_DEPTH_EXCEEDED` rather than a constraint violation.
- Instagram-style UI shows only two levels (top-level plus direct replies).
  Deeper nesting is stored; rendering depth is a client concern.
- No comment edit history is stored; only the current `content` and the `edited_at` timestamp of the most recent change are retained.
  This differs from the `post` module, which has a `post_edit_history` table (V22).
  A client can tell that a comment was edited and when, but not what it used to say.
- Moderation is rule-based only: emptiness, length, all-capitals and repeated words.
  Word lists live in the Hidden Words registry, not here, and there is no classifier, no review queue, and no appeal path.
  `moderation_status` carries the hidden state the post owner's controls produce, and nothing else writes it.
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
- The deletion scope counts every comment the caller can see that the delete would remove, including replies by users the caller has blocked.
  Filtering those out would make the number disagree with what the delete does, which is the defect the endpoint exists to close.
- The exceptions are a hidden comment written by another user and a comment a moderator removed, neither of which is counted for anyone but an administrator or the post owner.
  Counting a hidden reply would tell the caller that it exists, and counting a removed one would report a comment the lists already omit.
- Top comments are pinned to the first page only, rather than the whole list being ranked by like count.
  A full ranking would put a mutable column in the keyset sort key, which is the defect class the `(created_at, id)` cursor exists to prevent.
  The accepted degradation is that a highly-liked comment is not surfaced anywhere on page two onward.
- A reply written by the author of a hidden comment in the same instant the owner unhides that comment commits after the release statement has read its snapshot, so it can stay `hidden_parent` under an approved parent.
  The owner's review list shows it and the owner unhides it with one more call, which is allowed because its parent is no longer hidden.
  Locking the parent on every reply to close the gap would put a row lock on the hot comment-create path for a window of one statement.
- An edit that hides a parent between the unhide's parent check and its `UPDATE` can leave an approved child under a hidden parent until the owner acts.
  The parent check and the conditional `UPDATE` are separate statements, and only the target row is locked by the second.
  The owner's review list shows the hidden parent, and unhiding it is the repair; no lock is taken on the parent because the window is one statement wide and only the author of the parent can open it.
- A live frame that removes a comment no viewer held (a delete, or an edit of a comment that was already hidden) carries only the post id, the comment id and a count of zero.
  The topic is shared by every subscriber, so the frame cannot be limited to the author without a per-session audience filter.
  The id is opaque and the frame carries no text, author or position, which is the disclosure accepted in exchange for the author's other sessions learning of the delete.

---

## Section 4: Inter-Module Dependencies

| Dependency | Direction | Nature |
|------------|-----------|--------|
| `post` | inbound | Every comment belongs to a `post_id`; the `comment_count` trigger updates `posts`; the comment-create gate delegates to `PostVisibilityService` |
| `users` | inbound | Every comment belongs to a `user_id`; `@mention` usernames resolve against `users` |
| `social` | inbound | Block and follow state reach comments through the post visibility policy |
| `social` | inbound | `CommentServiceImpl` asks `InteractionPolicyService` before it persists a comment, an edit or a mention, and stores the hidden state the answer calls for |
| `notification` | outbound | `comment.created.v1`, `comment.liked.v1` and `comment.unliked.v1` drive `comment_post`, `reply_comment`, `mention_comment` and `like_comment` notifications; a mention of the person the comment already notifies is dropped, and an unlike retracts the liker from the group |
| `notification` | inbound | `CommentPreviewService` gives the notification feed viewer-specific comment and parent snippets, withholding text for tombstones, blocks in either direction, and unapproved comments except to their authors; `GET /api/v1/comments/{commentId}/context` returns a comment with every ancestor so a notification can open a reply wherever it sits. |
| `report` | inbound | Reports can target a comment via polymorphic `entity_id`; a hidden comment can be reported by the owner of its post only, and its author is refused as for a visible comment |
| `admin` | inbound | Moderator remove and restore actions target comments and write an `admin_actions` audit row |
