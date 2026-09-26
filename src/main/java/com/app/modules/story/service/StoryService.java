package com.app.modules.story.service;

import java.util.List;
import java.util.UUID;

import com.app.modules.story.dto.request.CreateStoryRequest;
import com.app.modules.story.dto.response.StoryFeedItemResponse;
import com.app.modules.story.dto.response.StoryResponse;

/** Story lifecycle operations: creation, reads gated by visibility and expiry, and deletion. */
public interface StoryService {

    /**
     * Creates a story backed by a media asset the author owns.
     *
     * <p>The story type is derived from the asset's media type and the expiry is computed from the
     * {@code story_duration_hours} system setting.
     *
     * @param authorId the authenticated creator
     * @param request the media reference and optional caption
     * @return the created story
     * @throws com.app.common.exception.AppException NOT_FOUND when the asset does not exist;
     *     STORY_FORBIDDEN when the asset belongs to another user
     */
    StoryResponse createStory(UUID authorId, CreateStoryRequest request);

    /**
     * Returns a single active story visible to the viewer.
     *
     * <p>Expired or soft-deleted stories are indistinguishable from missing ones.
     *
     * @param viewerId the requesting user
     * @param storyId the story to fetch
     * @return the story, with view count for the owner or the seen flag for other viewers
     * @throws com.app.common.exception.AppException STORY_NOT_FOUND when missing, deleted, or
     *     expired; STORY_FORBIDDEN when not visible to the viewer
     */
    StoryResponse getStoryById(UUID viewerId, UUID storyId);

    /**
     * Lists a user's active stories in playback order (oldest first).
     *
     * @param viewerId the requesting user
     * @param targetUserId the story owner
     * @return active stories of the target user
     * @throws com.app.common.exception.AppException NOT_FOUND when the target user does not exist,
     *     or on a block in either direction - the stealth block model requires the two to be
     *     indistinguishable; STORY_FORBIDDEN for a private account without an accepted follow
     */
    List<StoryResponse> listUserStories(UUID viewerId, UUID targetUserId);

    /**
     * Builds the story feed tray: the viewer plus followed authors with active stories.
     *
     * <p>The viewer's own entry is pinned first; remaining authors are ordered unseen-first, then
     * by newest story. Blocked relationships are excluded via the follow-graph source query.
     *
     * @param viewerId the requesting user
     * @return tray entries grouped by author; empty when nobody has active stories
     */
    List<StoryFeedItemResponse> getStoryFeed(UUID viewerId);

    /**
     * Active stories from suggested accounts the viewer does not follow.
     *
     * <p>Widens discovery, never visibility. A private account never appears, and every exclusion
     * {@code listUserStories} applies to a direct profile visit applies here too: a block in either
     * direction, a dismissed suggestion, an account that opted out of being suggested, and both of
     * the story's own tombstones.
     *
     * <p>Ordered by suggestion rank rather than recency, because the reason an account appears here
     * is that it was suggested.
     *
     * @param viewerId the account discovering
     * @param limit maximum authors, bounded by the implementation
     * @return tray entries in suggestion rank order, empty when nothing qualifies
     */
    List<StoryFeedItemResponse> discoverStories(UUID viewerId, int limit);

    /**
     * Soft-deletes a story owned by the requester.
     *
     * <p>Expired-but-live stories remain deletable so they can become cleanup-job targets.
     *
     * @param requesterId the authenticated user
     * @param storyId the story to delete
     * @throws com.app.common.exception.AppException STORY_NOT_FOUND when missing or already
     *     deleted, or when the story is not visible to the requester; STORY_FORBIDDEN when visible
     *     but not owned
     */
    void deleteStory(UUID requesterId, UUID storyId);
}
