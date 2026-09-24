# Story Module — Data Rules

**Implementation status**: Fully implemented with story creation, expiry-gated reads, a grouped feed tray, deduplicated view tracking, like/unlike (self-like permitted), owner-only soft delete, `story_view` notifications, and a scheduled cleanup job, with unit/integration coverage. Story replies (via the `message` module) remain out of scope — see Section C.

---

## Section 1: Canonical Data

| Table | Key Columns | Notes |
|-------|-------------|-------|
| `stories` | `id`, `user_id`, `media_asset_id`, `story_type`, `caption`, `expires_at`, `deleted_at` | Core story entity. 24-hour ephemeral by default (`expires_at = NOW() + INTERVAL '24 hours'`). Soft-deleted via `deleted_at`. |
| `story_views` | `story_id`, `viewer_id`, `viewed_at` | Deduplicated view records — one row per (story, viewer) pair. Canonical viewer list. |
| `story_likes` | `user_id`, `story_id`, `created_at` | Like records — one row per (user, story) pair. Canonical liker list. Self-like permitted. |

These tables cannot be rebuilt from any other source if lost.

---

## Section 2: Derived Data / Cache / Projection

| Data | Location | Rebuilt From | Rebuild Trigger |
|------|----------|--------------|-----------------|
| `stories.view_count` | `stories` table | `COUNT(*)` from `story_views` where `story_id = story.id` | Trigger `trg_story_view_count` (V16) |
| `stories.like_count` | `stories` table | `COUNT(*)` from `story_likes` where `story_id = story.id` | Trigger `trg_story_like_count` (V49) |
| Active stories view | `active_stories` (DB view, V17) | `stories` where `deleted_at IS NULL` and `expires_at > NOW()` | Query-time |
| Story feed cache | Redis | Rebuild from `stories` joined with `follows` | `[DEFERRED]` — the feed tray is served directly from three indexed queries (`StoryServiceImpl.getStoryFeed`); a Redis cache was not needed at current scale |

---

## Section 3: Business Rules

### A. Rules Enforced by the Database

| Rule | Enforced By |
|------|-------------|
| `story_type` must be one of `'image'`, `'video'` | `story_type` enum |
| `view_count` is non-negative | `CHECK (view_count >= 0)` |
| `like_count` is non-negative | `CHECK (like_count >= 0)` |
| `story_views` allows at most one view record per (story, viewer) pair | Compound `PRIMARY KEY (story_id, viewer_id)` |
| `story_likes` allows at most one like record per (user, story) pair | Compound `PRIMARY KEY (user_id, story_id)` |
| `expires_at` defaults to 24 hours after creation | `DEFAULT (NOW() + INTERVAL '24 hours')` |
| `stories.media_asset_id` references an existing media asset | `REFERENCES media_assets(id)` (no cascade) |
| Deleting a user cascades to their stories | `ON DELETE CASCADE` on `stories.user_id` |
| Deleting a story cascades to its `story_views` rows | `ON DELETE CASCADE` on `story_views.story_id` |

### B. Rules Enforced by Application Code

| Rule | Service / Component |
|------|---------------------|
| Stories must not be returned if `expires_at <= NOW()` or `deleted_at IS NOT NULL` | `StoryRepository.findActiveById` / `findActiveByUser` / `findActiveByAuthors` apply an explicit `expiresAt > :now` predicate; the entity's `@SQLRestriction("deleted_at IS NULL")` supplies the soft-delete filter on every JPQL read |
| When the story owner views their own story, a row must NOT be inserted into `story_views` | `StoryViewServiceImpl.recordView` short-circuits before the insert when the viewer equals the story owner |
| Only the story owner may soft-delete their story | `StoryServiceImpl.deleteStory` — throws `STORY_FORBIDDEN` for a visible non-owner, `STORY_NOT_FOUND` for a non-visible one |
| Viewing a story inserts into `story_views`; duplicate inserts (same viewer) must be ignored | `StoryViewRepository.insertIgnoringDuplicate` (native `INSERT ... ON CONFLICT DO NOTHING`), called from `StoryViewServiceImpl.recordView` |
| Stories from private accounts are only visible to accepted followers | `StoryVisibilityServiceImpl.isVisibleTo` — delegates to `SocialService.hasAcceptedFollow` for a private owner |
| Stories from blocked accounts must be excluded from the viewer's feed | `StoryVisibilityServiceImpl.isVisibleTo` (block check via `SocialService.isBlockedBetween`) for single-story reads; `StoryServiceImpl.getStoryFeed` sources authors from `SocialService.getAcceptedFollowingExcludingBlocks` for the tray |
| Viewing a story generates a `story_view` notification for the story owner | `StoryViewServiceImpl.recordView` enqueues `story.viewed.v1` on first view only; `StoryNotificationConsumer.dispatch` creates the `STORY_VIEW` notification via `NotificationService.create` |
| A background cleanup job removes rows that have already been soft-deleted (`deleted_at IS NOT NULL`) AND have passed their expiry time (`expires_at < NOW()`). Stories that are expired but not yet soft-deleted are NOT targets for the cleanup job. | `StoryCleanupScheduler.purgeSoftDeletedExpiredStories`, backed by the native `StoryRepository.purgeSoftDeletedExpired` query |
| `user_settings.allow_story_replies` governs whether viewers can reply to a story | `[NOT YET IMPLEMENTED]` — blocked on the `message` module, which has no reply-send path yet; see Section C |
| Liking an active, visible story inserts into `story_likes`; self-like is permitted; liking an already-liked story is a conflict | `StoryLikeServiceImpl.likeStory` — `STORY_ALREADY_LIKED` on a duplicate, `STORY_NOT_FOUND` when missing/expired/not visible |
| Unliking removes the caller's row; unliking a story that is not liked is not-found | `StoryLikeServiceImpl.unlikeStory` |

### C. Scope Simplifications

- No story highlights (saving stories beyond 24 hours).
- No story polls, questions, or interactive stickers.
- Story replies are direct messages (`message_type = 'story_share'`); the `message` module has no implementation yet, so this rule and `user_settings.allow_story_replies` stay unenforced until that module lands.
- Administrative story takedown exists and this list no longer covers it. V75 added `remove_story` and `restore_story` to `admin_action_type`, V95 added `stories.admin_removed_at` as a moderation tombstone independent of the owner's own `deleted_at`, and `PATCH /api/v1/admin/stories/{storyId}/remove` and `/restore` are live routes. A restore clears only `admin_removed_at`, never the owner's deletion, and expiry keeps deciding visibility independently of both, so a story that expired while removed does not return to a feed when restored.

---

## Section 4: Inter-Module Dependencies

| Dependency | Direction | Nature |
|------------|-----------|--------|
| `users` | inbound | Every story belongs to a `user_id`; viewer identity tracked via `story_views.viewer_id` |
| `media` | outbound | Each story references exactly one `media_asset_id` |
| `social` | inbound | Follow/block state governs story visibility |
| `notification` | outbound | Story view events trigger a `story_view` notification for the story owner; the views of one story aggregate into one group |
| `notification` | inbound | `StoryPreviewService` gives the notification feed each story's availability, expiry and thumbnail |
| `message` | inbound | Stories can be shared into conversations via `messages.shared_story_id` |
| `report` | inbound | Reports can target a story via polymorphic `entity_id` |
