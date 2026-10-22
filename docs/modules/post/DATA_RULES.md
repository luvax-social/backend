# Post Module — Data Rules

**Implementation status**: Core CRUD, lifecycle (including admin-moderated removal and restore), likes, saves, visibility, Elasticsearch search, caption edit history, carousel media-count validation, and publish-time hashtag extraction are all implemented.
Drafts, scheduled posts, the owner archive, per-media alt text, the places registry, people tags, collaborative posts and persisted mentions are implemented; see "Post creation" in Section 3B.
One rule remains outstanding: a background job to update `posts.view_count`; see Section 3B.

---

## Section 1: Canonical Data

| Table | Key Columns | Notes |
|-------|-------------|-------|
| `posts` | `id`, `user_id`, `caption`, `post_type`, `status`, `place_id`, `published_at`, `scheduled_at`, `deleted_at` | Core post entity. Soft-deleted via `deleted_at`. Status lifecycle: `draft` or `scheduled` to `published`, then `archived` or `removed`. A scheduled post carries `scheduled_at`; `published_at` is the instant the post first became published and orders every list. |
| `post_media` | `id`, `post_id`, `media_asset_id`, `position`, `alt_text` | Ordered media items attached to a post. Supports carousel (multiple images/videos). `alt_text` is at most 500 characters and null when the author wrote none. |
| `post_user_tags` | `id`, `post_id`, `post_media_id`, `tagged_user_id`, `x_position`, `y_position`, `status`, `created_by` | People tagged on one media item, positioned as fractions of the item (0 to 1). `status` is `pending` until an account that reviews its tags approves it, then `approved`. |
| `post_collaborators` | `post_id`, `user_id`, `status`, `invited_by`, `invited_at`, `responded_at`, `accepted_at` | Co-authorship. `status` is `invited`, `accepted`, `declined`, `left` or `removed`; rows are kept after a response so `accepted_at` can attribute performance. |
| `post_mentions` | `post_id`, `mentioned_user_id`, `handle`, `created_at` | Caption mentions stored by user id, so a mention keeps pointing at the account it named after a rename. |
| `archived_post_locations`, `archived_post_user_tags`, `archived_post_media_alt_texts` | copies of dropped or rewritten values | Written by V152, V154 and V160 before they changed or dropped data; read by no code. |
| `post_likes` | `user_id`, `post_id`, `created_at` | One row per (user, post) pair; compound PK prevents duplicate likes. Canonical like signal. |
| `post_saves` | `user_id`, `post_id`, `created_at` | One row per (user, post) pair; compound PK prevents duplicate saves. Canonical bookmark signal. |
| `post_edit_history` | `id`, `post_id`, `editor_id`, `previous_caption`, `edited_at` | Append-only caption edit audit (V22). Rows are never updated or soft-deleted; retention is permanent until post hard-delete (FK cascade). |
| `post_not_interested` | `user_id`, `post_id`, `created_at` | One row per (user, post) pair (V139); compound PK prevents duplicates. Rows are deleted with either the user or the post (FK cascade). Written only by `PostFeedbackServiceImpl`. |

These tables cannot be rebuilt from any other source if lost.

---

## Section 2: Derived Data / Cache / Projection

| Data | Location | Rebuilt From | Rebuild Trigger |
|------|----------|--------------|-----------------|
| `posts.like_count` | `posts` table | `COUNT(*)` from `post_likes` where `post_id = post.id` | Trigger `trg_post_like_count` (V16) |
| `posts.comment_count` | `posts` table | `COUNT(*)` from `comments` where `post_id = post.id` and `deleted_at IS NULL` | Trigger `trg_post_comment_count` (V16) |
| `posts.save_count` | `posts` table | `COUNT(*)` from `post_saves` where `post_id = post.id` | Trigger `trg_post_save_count` (V16) |
| `posts.view_count` | `posts` table | No trigger; intended to be updated by a background job | `[NOT YET IMPLEMENTED]` - `POST /api/v1/posts/{postId}/view` records a `post.viewed.v1` event (consumed into the ClickHouse `user_events` table as `post_view`), but no job yet aggregates it back into this counter, so `view_count` still never changes from its default |
| `posts.updated_at` | `posts` table | Auto-maintained | Trigger `trg_posts_updated_at` (V16) |
| `posts.published_at` | `posts` table | Set to `NOW()` the first time `status` becomes `published` and never cleared; an explicit value on insert is respected, which is what lets the seed backdate a post | Trigger `trg_posts_published_at` (V151) |
| `users.post_count` | `users` table | `COUNT(*)` of published, non-deleted posts the user owns or co-authors (an accepted `post_collaborators` row) | Triggers `trg_post_authors_count` and `trg_post_count` on `posts`, and `trg_post_collaborator_count` on `post_collaborators` (V159) |
| `places.post_count` | `places` table | `COUNT(*)` of published, non-deleted posts at the place | Trigger `trg_place_post_count` (V154); `fn_recount_place_posts` (V156) repairs it |

---

## Section 3: Business Rules

### A. Rules Enforced by the Database

| Rule | Enforced By |
|------|-------------|
| `post_type` must be one of `'image'`, `'video'`, `'carousel'` | `post_type` enum |
| `status` must be one of `'draft'`, `'scheduled'`, `'published'`, `'archived'`, `'removed'`; defaults to `'published'` | `post_status` enum, `DEFAULT 'published'` |
| A scheduled post has a `scheduled_at`; a published or archived post has a `published_at` | `chk_posts_scheduled_at`, `chk_posts_published_at` (V151, validated in V164) |
| Alt text is at most 500 characters | `chk_post_media_alt_text_length` (V152, validated in V164) |
| A media item holds at most `max_post_tags_per_media` tags, and a tag position lies between 0 and 1 | a limit trigger on `post_user_tags` and a check constraint (V160) |
| A post has at most `max_post_collaborators` live co-author rows (`invited` plus `accepted`), and the owner is never their own co-author | `trg_post_collaborators_limit` (V159), under a lock on the post row |
| A place is identified by its normalised name, which is unique | `uq_places_normalized_name` on `normalized_name`, which holds `fn_normalise_place_name(name)` (V153) |
| `like_count`, `comment_count`, `save_count`, `view_count` are non-negative | `CHECK (column >= 0)` |
| `post_media.position` must be unique within a post (no two media items at same carousel position) | `UNIQUE (post_id, position)` |
| `post_likes` allows at most one like per (user, post) pair | Compound `PRIMARY KEY (user_id, post_id)` |
| `post_saves` allows at most one save per (user, post) pair | Compound `PRIMARY KEY (user_id, post_id)` |
| `post_user_tags` allows one tag per person per media item | `uq_post_user_tags_media_user` on `(post_media_id, tagged_user_id)` (V160) |
| Deleting a post cascades to `post_media`, `post_likes`, `post_saves`, `post_user_tags`, `post_collaborators`, `post_mentions`, `comments` | `ON DELETE CASCADE` on all FK references |
| `post_media` references `media_assets`; media asset must exist | `REFERENCES media_assets(id)` (no cascade — asset deletion is independent) |

### B. Rules Enforced by Application Code

| Rule | Service / Component |
|------|---------------------|
| A `carousel` post must have more than one `post_media` row | Enforced by `PostServiceImpl.validateMediaCardinality` — rejects fewer than 2 media items with `BAD_REQUEST`. |
| A `carousel` post must have at most `max_post_media_items` media rows, default 10 | Enforced by `PostServiceImpl.validateMediaCardinality` — rejects more than the setting with `BAD_REQUEST`. |
| An `image` post accepts exactly one asset whose `media_type` is `image`; a `video` post accepts exactly one asset whose `media_type` is `video` | Enforced by `PostServiceImpl.validateMediaCardinality`. |
| A `carousel` post may mix `image` and `video` assets in one post | Deliberate. See "Mixed-media carousels" below. |
| Self-like is permitted. There is no constraint preventing a user from liking their own post. | No constraint in schema |
| Only the post owner may update or soft-delete their post | Enforced by `PostServiceImpl` — `updateCaption`, `transitionStatus`, `deletePost`. |
| A soft-deleted post must set `deleted_at = NOW()` and `status = 'removed'`; do not hard-delete | Implemented in `PostServiceImpl.softDelete`. |
| A moderation removal performs exactly the side effects an owner removal performs: `deleted_at = NOW()`, `status = 'removed'`, hashtag associations detached, search-index delete enqueued | Implemented in `PostServiceImpl.applyModerationRemoval`, the single entry point the `admin` module calls. The two removal paths cannot diverge because there is only one of them. |
| A moderation removal records the status the post held in `status_before_moderation`, and restore returns the post to it rather than publishing it | Implemented in `PostServiceImpl.applyModerationRemoval` / `applyModerationRestore`. The prior status is deliberately not read from `admin_actions.metadata`: an append-only audit log must not become load-bearing for application behaviour. A post removed before the column existed has NULL there and comes back `published`, which is what restore did for every post at that time. |
| Restore re-derives hashtags from the caption and enqueues a search-index upsert only when the resulting status is `published` | Implemented in `PostServiceImpl.applyModerationRestore`. A draft or archived post belongs in neither `post_hashtags` nor the index, and the owner path keeps both out of both. |
| Posts from blocked users must be excluded from feeds | Enforced by `PostVisibilityServiceImpl.isVisibleTo`. |
| Posts from private accounts are only visible to accepted followers | Enforced by `PostVisibilityServiceImpl.isVisibleTo`. |
| `posts.view_count` is updated by a background job, not a trigger. It may lag real-time activity. See `GLOBAL_RULES.md` — Counter Policy Exception. | `[NOT YET IMPLEMENTED]` — no job exists; `view_count` is never written anywhere in the codebase today. `PostViewServiceImpl.recordView` deliberately does not touch it, only enqueues the behavioral event. |
| A view is accepted but not recorded when the viewer is the post's own owner, so self-views can never inflate any downstream signal | `PostViewServiceImpl.recordView` |
| Hashtags in `caption` are parsed and written to `post_hashtags` at publish time | Implemented in `PostServiceImpl.upsertCaptionHashtags`, called from `createPost` (when initially published) and `updateCaption` (when the post is already published). |
| A caption naming a banned hashtag is refused with `422 POST_BANNED_HASHTAG`, before any mutation | Implemented in `PostServiceImpl.rejectBannedHashtags`, called from `createPost` on both branches, from `updateCaption` before the edit-history row, and from `transitionStatus` on the publish arm. The response body carries the offending names under `data.bannedTags`, normalized, so a client can highlight them in the caption |
| A moderation restore strips banned hashtags rather than refusing | Implemented in `PostServiceImpl.applyModerationRestore` via `HashtagService.upsertHashtagsForPostSkippingBanned`; the stripped names travel back on `PostModerationResult` and `AdminServiceImpl` records them in the audit row's `metadata.strippedHashtags` |
| A post response lists the hashtags it is associated with, omitting any the administrator has deleted | Implemented in `PostResponseAssembler`, one batched query per page through `HashtagService.getVisibleHashtagsForPosts`. The `post_hashtags` row is left in place, so nothing is lost if the tag is restored, and the caption keeps its literal `#tag` text either way. A banned hashtag is still listed |
| Caption mentions are stored by user id and notify the mentioned account once, when the post is published | `PostMentionServiceImpl`; see "Post creation" below |
| Marking a post not interested stores one `post_not_interested` row and enqueues `post.not-interested.v1` with the post and author ids, in the same transaction | `PostFeedbackServiceImpl.markNotInterested` |
| The insert is a single `ON CONFLICT DO NOTHING` statement, so a repeat or a concurrent duplicate changes nothing and enqueues no second event | `PostNotInterestedRepository.insertIfAbsent` |
| A viewer cannot mark their own post, which answers `400 POST_FEEDBACK_SELF` | `PostFeedbackServiceImpl.markNotInterested` |
| A post that is missing, unpublished (another user's), hidden by a block in either direction, or owned by an unfollowed private or deleted account answers `404 POST_NOT_FOUND` | `PostFeedbackServiceImpl.markNotInterested` via `PostVisibilityService.isVisibleTo` |
| Removing the mark is idempotent, does not check the post's current visibility, and enqueues `post.not-interested-undone.v1` only when a row was deleted | `PostFeedbackServiceImpl.undoNotInterested`, `PostNotInterestedRepository.deleteByUserAndPost` |
| The post author is never told: neither event is bound to a notification queue, and no response of the author's carries the mark | `PostRabbitBindingConfig`, `NotificationRabbitBindingConfig` |

#### The banned-hashtag boundary

Five write paths could put a hashtag into `post_hashtags`. Four of them refuse a banned tag and one strips it.

| Path | On a banned tag |
|------|-----------------|
| `createPost`, text branch | `422`, before the post row is written |
| `createPost`, media branch | `422`, before the media assets are even looked up |
| `updateCaption` | `422`, before the `post_edit_history` row is written |
| `transitionStatus`, target `published` | `422`, covering `draft -> published` and `archived -> published` |
| `applyModerationRestore` | the banned associations are not created, the rest are, and the names are recorded |

The publish arm is the one a check on the create routes alone would miss.
A post drafted or archived before the ban still carries the tag in its caption, and publishing is when that tag would reach `post_hashtags` and the search index for the first time since.

Creating a draft is refused as well, not only publishing it.
A draft naming a banned tag could never be published, so refusing it at the point it is written tells the author while the caption is still in front of them.
Every check reads the caption being submitted, never the stored one, so removing the offending tag and retrying always succeeds; no author is trapped in a post it cannot edit.

`HashtagService.upsertHashtagsForPost` refuses a banned name too, but that check is the last line rather than the first.
By the time it runs the post has been persisted and flushed, so it cannot produce the `422` body and would depend on transaction rollback for correctness.
It exists so a future caller that forgets the check fails loudly instead of silently associating a banned tag.

Restore is the single exception, and it is deliberate.
A moderator restoring a post it removed by mistake is correcting its own error.
Blocking that on an administrator's unrelated decision, which the moderator has no power to reverse, would leave the post removed with no in-role way back.

**None of this touches which posts are visible.**
Banning a hashtag changes what the hashtag surfaces show and what new writes accept.
It does not change the feed, the profile listing, post search, or any other query that filters posts, and a post carrying a banned tag keeps appearing exactly where it did before.
See `hashtag/DATA_RULES.md` section D for the full statement of that boundary.

#### Mixed-media carousels

A carousel may hold images and video in the same post.
The media type restriction applies only to single-asset posts: an `image` post must carry an image asset and a `video` post must carry a video asset, and a carousel is subject to neither.

This is a deliberate exemption, not a validation path that was never extended.
The `PostService.createPost` contract states the two rules separately and attaches the type requirement only to the single-asset case, and `validateMediaCardinality` matches that contract exactly by returning from the carousel branch once the item count is checked.
The type check it returns past is written against a single asset and could not be applied to a list without being rewritten.
The behaviour also matches the product being modelled, where a carousel is explicitly a mixed gallery.

Do not close this as a gap.
`PostControllerIT.createPost_carouselMixingImageAndVideo_returnsCreated` pins the allowance and `PostControllerIT.createPost_imagePostWithVideoAsset_returnsBadRequest` pins the fact that the exemption stops at carousels.
A change that made carousels type-homogeneous would break a client feature built on this.
| Every caption update appends one `post_edit_history` row recording the pre-edit caption and the editor | `PostServiceImpl` |
| Edit history is readable by the post owner only | `PostServiceImpl` |

#### Interaction preferences in the feeds

`PostService.getFeed(viewerId, cursor, size, surface)` serves the chronological feed for a `FeedSurface`.
The Following tab passes `FOLLOWING`, and the ranked feed passes `FOR_YOU_FALLBACK` when it falls back, for the first page and for every continuation page.
The cursor carries no surface, so the caller names it on each page.
`PostServiceImpl.getFeed` subtracts the authors returned by `SocialService.findFeedSuppressedAuthors` from the followed author list before it queries posts.
Muted authors are subtracted on every surface, and show-fewer authors are subtracted on `FOR_YOU_FALLBACK` only.
`PostRepository.findFirstFeedPosts` and `findFeedPostsBefore` take the viewer and exclude posts with a `post_not_interested` row for that viewer, on every surface.
The exclusion is an anti-join on the mark's primary key.
For a single author the `idx_posts_user_created_id` keyset index still drives the scan.
With several authors the planner may order by another posts index, so the query plan test pins only that there is no sequential scan, the anti-join and the primary key probe.
`PostFeedbackService.findNotInterested(viewerId, postIds)` returns the marked subset of a candidate set in one indexed query, for the ranked feed.
Profile posts, post search, hashtag posts and the saved list do not read these preferences.
A block exclusion is unchanged, and the preference filters never replace it.

#### Post creation

Drafts, scheduled posts, alt text, places, people tags, collaboration and mentions share one publication routine.

| Rule | Service / Component |
|------|---------------------|
| `PostPublicationService.publish` is the only code that moves a post to `published`; it runs under the post row lock and validates that the owner is active, every media asset still exists and belongs to the owner, no caption hashtag is banned and the place is active | `PostPublicationServiceImpl`, `PostPublicationException` carrying a `Reason` |
| The first publication re-decides mentions, tags and invitations against the interaction policy, enqueues `post.index.upsert.v1` and the deferred events, and nothing is notified before that point; unarchiving only re-indexes | `PostPublicationServiceImpl` |
| A draft or scheduled post is created, replaced or rescheduled under `SELECT ... FOR UPDATE` on the post row, so the publisher and an owner edit serialise | `PostOwnerServiceImpl`, `PostRepository.findByIdForUpdate` |
| The scheduled publisher claims due posts with `FOR UPDATE SKIP LOCKED`, publishes each in its own `REQUIRES_NEW` transaction, and returns a post that can no longer be published to `draft` with a `post.schedule-failed.v1` event; it runs only while `app.post.scheduled-publish.enabled` is true | `ScheduledPostPublisher`, `ScheduledPostPublisherJob` |
| A scheduled time is floored to the minute and must be between one minute and `scheduled_post_horizon_days` (30) from now; an account holds at most `max_drafts_and_scheduled_posts` (50) drafts and scheduled posts together | `PostSchedulePolicy` |
| Like, save, view, not-interested, comment and report writes refuse an unpublished post, for its owner as well, so no behavioural signal can exist for a post the application hides | `PostLikeServiceImpl`, `PostSaveServiceImpl`, `PostViewServiceImpl`, `PostFeedbackServiceImpl`, `CommentServiceImpl`, `ReportTargetRepositoryImpl` |
| Every ordering of posts (Following feed, profile grid, hashtag and location pages, search index, Gorse item timestamp) uses `(published_at, id)` | `PostRepository`, `PostDocument` |
| Alt text is edited by the owner on a post in any retained status and is returned per media item; a reader who gets none sees a label the client builds | `PostOwnerServiceImpl.updateAltText` |
| A place is created or found by normalised name; a banned or deleted place is refused with `POST_PLACE_UNAVAILABLE` and is never recreated, and a post whose place was removed after tagging simply stops returning it | `PlaceServiceImpl`, `PostResponseAssembler` |
| Banning or deleting a place never takes a post down; a moderation restore of a post whose place is no longer active strips the place and records `strippedPlace` in the audit metadata | `AdminServiceImpl`, `PostModerationResult` |
| A tag is decided by the `TAG` interaction rule: a block answers as not found, `nobody` and `following` refuse with `POST_TAG_NOT_ALLOWED`, manual approval makes the tag `pending` and notifies, and a limit on interactions makes it `pending` without a notification | `TagPermissionRule`, `PostTagServiceImpl` |
| Only the tagged person approves a tag; the owner and the tagged person remove one at any time; a pending tag is returned only to those two | `PostTagServiceImpl`, `PostResponseAssembler` |
| A co-author is invited by the owner (a block refuses the invitation), appears on the post and in its authors list only after accepting, may leave, and never edits or deletes; a post counts toward the co-author's `post_count` and appears on their profile | `PostCollaborationServiceImpl`, `PostVisibilityServiceImpl` |
| A block between a co-author and anyone who could see the post hides the post from that viewer exactly as a block with the owner does, and a new block withdraws pending invitations and tags between the pair | `PostVisibilityServiceImpl`, `PostBlockCleanupListener` |
| A caption mention is stored only for an active account the policy allows; a `DENY` stores nothing, a hidden outcome stores the row without a notification, and editing a published caption re-parses mentions without notifying again | `PostMentionServiceImpl`, `MentionParser` |

#### Places registry

A place is a name people typed, shared by everyone who types the same name, with no coordinates.

| Rule | Component |
|------|-----------|
| `places` holds `name` (as first written), `normalized_name` (unique), `status` (`active`, `banned`, `deleted`), `status_note`, `status_at`, `status_by`, `created_by` and the trigger-owned `post_count`; `deleted` is a state, never a row removal, so posts keep their foreign key | V153, `Place` |
| The key is `fn_normalise_place_name` in SQL and `TextMatchNormalizer.normalizeName` in Java, and the frontend mirrors it: decompose to NFD, drop combining marks, map the stroked d to d, lower-case, keep runs of letters and digits joined by one space, and never map digits to letters, which would merge "B4 Cafe" with "Ba Cafe". `PlaceNameNormalisationParityIT` and the shared vectors `place-name-vectors.json` pin the three together | V153, `TextMatchNormalizer` |
| `POST /places` creates a place or returns the existing one for the normalised name and refuses an unavailable one; `GET /places/search` is served from the `places` Elasticsearch index behind the `elasticsearchSearch` circuit breaker with a PostgreSQL trigram fallback; `GET /places/{id}` and `GET /places/{id}/posts` answer 404 for a banned or deleted place | `PlaceController`, `PlaceSearchServiceImpl`, `PostByPlaceServiceImpl` |
| Only an active place that a published post uses is indexed; the consumer reads name, `post_count` and status from the row, so a ban reuses the upsert event and turns it into a delete | `PlaceIndexSyncConsumer`, `PlaceIndexEventPublisher` |
| The location page lists published posts ordered by `(published_at, id)`, filtered for the viewer like the hashtag page | `PostByPlaceServiceImpl` |
| An administrator moves a place between statuses with `PATCH /admin/places/{id}` and a note that bans and deletions require; the change writes a `ban_place`, `unban_place` or `delete_place` audit row, resolves a linked place report in the same transaction, and unbanning also restores a deleted place | `AdminPlaceServiceImpl`, `PlaceLifecycleServiceImpl` |
| A place name is reportable (`report_type` `place`); the administrator who acts on the report does so from the registry, which passes the report id | `ReportTargetRepositoryImpl`, `AdminReportTargetRepository` |

#### Event types

`PostEventTypes` names the events the post creation features add: `post.published.v1`, `post.scheduled.v1`, `post.schedule-failed.v1`, `post.draft-saved.v1`, `post.archived.v1`, `post.unarchived.v1`, `post.tag.created.v1`, `post.tag.approved.v1`, `post.tag.removed.v1`, `post.collaboration.invited.v1`, `post.collaboration.accepted.v1`, `post.collaboration.declined.v1`, `post.collaboration.left.v1` and `post.mention.created.v1`.
The tag, invitation, mention and schedule-failure events are also bound to `post.notification.queue`; every event is bound to the recommendation import queue, which records it as a behavioural signal.

### C. Scope Simplifications

- `posts.view_count` is a denormalized counter updated by a **background job**, not a trigger. This diverges from the trigger pattern used for other counters and means `view_count` may lag behind real-time activity.
- Places have a name and nothing else: no coordinates, no map and no geospatial query; place search is by name only.
- Deleting a draft does not delete its uploaded objects or `media_assets` rows, because no media cleanup exists.
- Existing comments are not back-filled into `comment_mentions`, because resolving old text against today's usernames could attach a mention to the wrong person.
- Tags and invitations added to an archived post are stored but are announced only at the first publication, so the tagged person or invitee is not notified when the post returns; they see the pending state on the post and in their waiting lists.
- A co-author keeps seeing a post after the owner blocks them, because a co-author is an author of it; every other viewer is hidden from it by a block with any author.

---

## Section 4: Inter-Module Dependencies

| Dependency | Direction | Nature |
|------------|-----------|--------|
| `users` | inbound | Every post belongs to a `user_id`; author identity comes from `users` |
| `media` | outbound | `post_media` references `media_assets` for each attached media item |
| `hashtag` | outbound | Hashtags extracted from `caption` are written to `post_hashtags` + `hashtags`. Also read for the banned-name check on every write path and for the hashtag list on a post response |
| `comment` | inbound | Comments reference `posts.id`; `comment_count` trigger fires on comment table |
| `social` | inbound | Follow/block state governs post visibility; no direct FK dependency |
| `social` | outbound | `SocialService.findFeedSuppressedAuthors` supplies the muted and show-fewer authors the feed drops, per `FeedSurface` |
| `notification` | outbound | `post.liked.v1` and `post.unliked.v1` drive `like_post` notifications through `PostNotificationConsumer` on `post.notification.queue` (DLQ `post.notification.dlq`), enabled by `app.post.notification-consumer.enabled`; an unlike retracts the liker from the group. Caption mentions still produce nothing. |
| `notification` | inbound | `PostPreviewService` gives the notification feed each post's availability to the viewer and its first media as a thumbnail, in one query per page |
| `recommendation` | inbound | `post_categories` references `posts.id`; ClickHouse `user_events` rows carry post ids in `entity_id` with no foreign key |
| `recommendation` | outbound | `post.not-interested.v1` and `post.not-interested-undone.v1` are consumed by `RecommendationFeedbackConsumer`, which sends the mark to Gorse as `not_interested` negative feedback and takes it back on the undo; it reads `post_not_interested` through `PostFeedbackService.findNotInterested` to keep Gorse in step when the two events arrive out of order |
| `report` | inbound | Reports can target a post via polymorphic `entity_id`; only a published post can be reported |
| `place` (same module, `post/place/`) | outbound | A post names its place by `place_id`; the registry, its Elasticsearch index and the location page are described under "Places registry" in Section 3B |
| `social` | outbound | `InteractionPolicyService` decides every tag, collaboration invitation and caption mention; `UserBlockedEvent` withdraws pending involvement between a blocked pair |
| `message` | inbound | Messages can share a post via `shared_post_id` (SET NULL on post delete) |
