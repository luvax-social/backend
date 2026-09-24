# Post Module — Data Rules

**Implementation status**: Core CRUD, lifecycle (including admin-moderated removal and restore), likes, saves, visibility, Elasticsearch search, caption edit history, carousel media-count validation, and publish-time hashtag extraction are all implemented.
Two rules remain outstanding: a background job to update `posts.view_count`, and mention parsing in `caption` to generate `mention_post` notifications; see Section 3B.

---

## Section 1: Canonical Data

| Table | Key Columns | Notes |
|-------|-------------|-------|
| `posts` | `id`, `user_id`, `caption`, `post_type`, `status`, `location_name`, `latitude`, `longitude`, `deleted_at` | Core post entity. Soft-deleted via `deleted_at`. Status lifecycle: `draft → published → archived / removed`. |
| `post_media` | `id`, `post_id`, `media_asset_id`, `position`, `alt_text` | Ordered media items attached to a post. Supports carousel (multiple images/videos). |
| `post_user_tags` | `post_id`, `tagged_user_id`, `media_asset_id`, `x_position`, `y_position` | Users tagged within a post image, with optional pixel-percentage coordinates. |
| `post_likes` | `user_id`, `post_id`, `created_at` | One row per (user, post) pair; compound PK prevents duplicate likes. Canonical like signal. |
| `post_saves` | `user_id`, `post_id`, `created_at` | One row per (user, post) pair; compound PK prevents duplicate saves. Canonical bookmark signal. |
| `post_edit_history` | `id`, `post_id`, `editor_id`, `previous_caption`, `edited_at` | Append-only caption edit audit (V22). Rows are never updated or soft-deleted; retention is permanent until post hard-delete (FK cascade). |

These tables cannot be rebuilt from any other source if lost.

---

## Section 2: Derived Data / Cache / Projection

| Data | Location | Rebuilt From | Rebuild Trigger |
|------|----------|--------------|-----------------|
| `posts.like_count` | `posts` table | `COUNT(*)` from `post_likes` where `post_id = post.id` | Trigger `trg_post_like_count` (V16) |
| `posts.comment_count` | `posts` table | `COUNT(*)` from `comments` where `post_id = post.id` and `deleted_at IS NULL` | Trigger `trg_post_comment_count` (V16) |
| `posts.save_count` | `posts` table | `COUNT(*)` from `post_saves` where `post_id = post.id` | Trigger `trg_post_save_count` (V16) |
| `posts.view_count` | `posts` table | No trigger; intended to be updated by a background job | `[NOT YET IMPLEMENTED]` — `POST /api/v1/posts/{postId}/view` records a `post.viewed.v1` event (consumed into `user_events` as `post_view`), but no job yet aggregates it back into this counter, so `view_count` still never changes from its default |
| `posts.updated_at` | `posts` table | Auto-maintained | Trigger `trg_posts_updated_at` (V16) |
| `users.post_count` | `users` table | `COUNT(*)` from `posts` where `user_id` matches, `status='published'`, `deleted_at IS NULL` | Trigger `trg_post_count` (V16) |
| `post_interaction_scores` | `post_interaction_scores` table | Computed from `post_likes`, `comments`, `post_saves`, `user_events` by background scheduler | Scheduled background job |

---

## Section 3: Business Rules

### A. Rules Enforced by the Database

| Rule | Enforced By |
|------|-------------|
| `post_type` must be one of `'image'`, `'video'`, `'carousel'` | `post_type` enum |
| `status` must be one of `'draft'`, `'published'`, `'archived'`, `'removed'`; defaults to `'published'` | `post_status` enum, `DEFAULT 'published'` |
| `like_count`, `comment_count`, `save_count`, `view_count` are non-negative | `CHECK (column >= 0)` |
| `post_media.position` must be unique within a post (no two media items at same carousel position) | `UNIQUE (post_id, position)` |
| `post_likes` allows at most one like per (user, post) pair | Compound `PRIMARY KEY (user_id, post_id)` |
| `post_saves` allows at most one save per (user, post) pair | Compound `PRIMARY KEY (user_id, post_id)` |
| `post_user_tags` allows at most one tag per (post, user) pair | Compound `PRIMARY KEY (post_id, tagged_user_id)` |
| Deleting a post cascades to `post_media`, `post_likes`, `post_saves`, `post_user_tags`, `comments` | `ON DELETE CASCADE` on all FK references |
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
| User mentions in `caption` generate `mention_post` notifications | `[NOT YET IMPLEMENTED]` — no mention parsing exists in the post module |

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

### C. Scope Simplifications

- `posts.view_count` is a denormalized counter updated by a **background job**, not a trigger. This diverges from the trigger pattern used for other counters and means `view_count` may lag behind real-time activity.
- No post scheduling (publish at a future time); `status = 'draft'` is the only unpublished state.
- Location data (`latitude`, `longitude`) is stored but no geospatial query support is implemented.

---

## Section 4: Inter-Module Dependencies

| Dependency | Direction | Nature |
|------------|-----------|--------|
| `users` | inbound | Every post belongs to a `user_id`; author identity comes from `users` |
| `media` | outbound | `post_media` references `media_assets` for each attached media item |
| `hashtag` | outbound | Hashtags extracted from `caption` are written to `post_hashtags` + `hashtags`. Also read for the banned-name check on every write path and for the hashtag list on a post response |
| `comment` | inbound | Comments reference `posts.id`; `comment_count` trigger fires on comment table |
| `social` | inbound | Follow/block state governs post visibility; no direct FK dependency |
| `notification` | outbound | `post.liked.v1` and `post.unliked.v1` drive `like_post` notifications through `PostNotificationConsumer` on `post.notification.queue` (DLQ `post.notification.dlq`), enabled by `app.post.notification-consumer.enabled`; an unlike retracts the liker from the group. Caption mentions still produce nothing. |
| `notification` | inbound | `PostPreviewService` gives the notification feed each post's availability to the viewer and its first media as a thumbnail, in one query per page |
| `recommendation` | inbound | `post_categories` and `post_interaction_scores` reference `posts.id` |
| `report` | inbound | Reports can target a post via polymorphic `entity_id` |
| `message` | inbound | Messages can share a post via `shared_post_id` (SET NULL on post delete) |
