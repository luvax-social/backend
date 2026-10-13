package com.app.modules.post.service;

import java.util.UUID;

import com.app.common.response.CursorPageResponse;
import com.app.modules.post.dto.request.CreatePostRequest;
import com.app.modules.post.dto.request.PostStatusTransitionRequest;
import com.app.modules.post.dto.request.UpdatePostCaptionRequest;
import com.app.modules.post.dto.response.FeedPostResponse;
import com.app.modules.post.dto.response.PostEditHistoryResponse;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.validation.PostTypeFilter;

/** Domain API for post creation, retrieval, lifecycle, and caption edit history. */
public interface PostService {

    /**
     * Creates a post owned by the author with its ordered media items.
     *
     * <p>Every media asset must exist and be owned by the author. Carousel posts require at least
     * two and at most {@code max_post_media_items} assets; image and video posts require exactly
     * one asset of the matching media type. When the effective status is published, hashtags are
     * extracted from the caption and associated synchronously. Counters are never written —
     * triggers maintain them.
     *
     * @param authorId authenticated author
     * @param request post payload
     * @return the created post
     */
    PostResponse createPost(UUID authorId, CreatePostRequest request);

    /**
     * Returns a single post visible to the viewer.
     *
     * <p>Non-owners can only retrieve published posts; unpublished posts surface as not-found to
     * avoid leaking their existence. Account-level visibility (blocks, private accounts) is
     * enforced.
     *
     * @param viewerId authenticated viewer
     * @param postId post identifier
     * @return the post
     */
    PostResponse getPostById(UUID viewerId, UUID postId);

    /**
     * Replaces the caption of an owned post.
     *
     * <p>Appends one append-only {@code post_edit_history} row recording the pre-edit caption
     * before mutating. When the post is published, its hashtag associations are refreshed from the
     * new caption.
     *
     * @param requesterId authenticated requester; must be the post owner
     * @param postId post identifier
     * @param request new caption payload
     * @return the updated post
     */
    PostResponse updateCaption(UUID requesterId, UUID postId, UpdatePostCaptionRequest request);

    /**
     * Transitions the post lifecycle status.
     *
     * <p>Valid transitions: draft to published, published to archived, archived to published (owner
     * only), and any status to removed (owner or admin — equivalent to soft delete). Publishing
     * extracts caption hashtags; archiving and removal delete the hashtag associations.
     *
     * @param requesterId authenticated requester
     * @param postId post identifier
     * @param request target status payload
     * @return the post after the transition
     */
    PostResponse transitionStatus(
            UUID requesterId, UUID postId, PostStatusTransitionRequest request);

    /**
     * Soft-deletes a post: sets {@code deleted_at}, flips status to removed, and removes hashtag
     * associations.
     *
     * <p>Allowed for the post owner or an admin. Rows are never hard-deleted.
     *
     * @param requesterId authenticated requester
     * @param postId post identifier
     */
    void deletePost(UUID requesterId, UUID postId);

    /**
     * Removes a post by moderation, performing every side effect owner removal performs.
     *
     * <p>The one entry point for a moderation removal. Before it existed the administrative path
     * wrote the row itself and left the hashtag associations and the search-index document behind,
     * so a removed post kept contributing to trending counts and kept answering searches.
     *
     * <p>Records the status the post held, so {@link #applyModerationRestore} returns it there
     * rather than publishing it; detaches the hashtag associations; enqueues a search-index delete.
     * Does not check whether the post is already removed: that is the caller's transition guard.
     *
     * <p>Joins the caller's transaction. The index event goes through the outbox, whose enqueue is
     * MANDATORY, so there must already be one.
     *
     * @param postId post to remove
     * @return the post's author and its resulting status
     * @throws com.app.common.exception.AppException with {@code POST_NOT_FOUND} when no row holds
     *     that id
     */
    PostModerationResult applyModerationRemoval(UUID postId);

    /**
     * Returns a moderation-removed post to the status it held before the removal.
     *
     * <p>Requires a recorded prior status and no owner deletion timestamp.
     * Legacy ambiguous tombstones remain hidden rather than risking an owner deletion being undone.
     *
     * <p>Hashtag associations are re-derived and a search-index upsert is enqueued only when the
     * resulting status is published. A draft or an archived post belongs in neither, and the owner
     * path keeps both out of both as well.
     *
     * <p>A caption naming a banned hashtag does not block the restore. The banned association is
     * simply not created and its name comes back on the result, so the caller can record it and
     * tell the moderator. This is the only write path that tolerates a banned tag: every other one
     * refuses with {@code POST_BANNED_HASHTAG}. A moderator restoring a post it removed by mistake
     * is correcting its own error, and blocking it on an administrator decision it cannot reverse
     * would leave the post removed with no in-role way back.
     *
     * @param postId post to restore
     * @return the post's author, the status it now holds, which is not necessarily published, and
     *     any banned hashtag names the restore left unassociated
     * @throws com.app.common.exception.AppException with {@code POST_NOT_FOUND} when no row holds
     *     that id
     */
    PostModerationResult applyModerationRestore(UUID postId);

    /**
     * Cursor-paginated published posts of a user, newest first.
     *
     * <p>Throws when a block exists between viewer and target, or when the target account is
     * private and the viewer holds no accepted follow. Drafts and archived posts are never listed,
     * including for the owner.
     *
     * <p>The type filter is bound into the cursor scope, so a cursor issued under one filter is
     * rejected by another. Without that binding a replay would succeed and silently omit every row
     * the other filter excludes that sits before the cursor position, because the ordering does not
     * depend on the filter.
     *
     * @param viewerId authenticated viewer
     * @param targetUserId profile owner
     * @param typeFilter normalized post types to include; empty means no filter
     * @param cursor opaque base64 cursor from the previous page; null or blank for the first page
     * @param size requested page size, normalized to 1–100 with a default of 20
     * @return cursor page of posts
     */
    CursorPageResponse<PostResponse> listUserPosts(
            UUID viewerId, UUID targetUserId, PostTypeFilter typeFilter, String cursor, int size);

    /**
     * Cursor-paginated chronological feed of published posts from accounts the viewer follows.
     *
     * <p>Only accepted follow relationships contribute to the feed; pending follow requests are
     * excluded. Posts from any account involved in a block relationship with the viewer, in either
     * direction, are excluded. Returns an empty page without querying posts when the viewer follows
     * nobody or when all followed accounts are blocked. Posts are ordered by creation time, newest
     * first.
     *
     * @param viewerId authenticated viewer
     * @param cursor opaque base64 cursor from the previous page; null or blank for the first page
     * @param size requested page size, normalized to 1–100 with a default of 20
     * @return cursor page of feed posts ordered by creation time, newest first
     */
    CursorPageResponse<FeedPostResponse> getFeed(UUID viewerId, String cursor, int size);

    /**
     * Cursor-paginated caption edit history of an owned post, newest first.
     *
     * <p>Owner-only: requesters other than the post owner are rejected.
     *
     * @param requesterId authenticated requester; must be the post owner
     * @param postId post identifier
     * @param cursor opaque base64 cursor from the previous page; null or blank for the first page
     * @param size requested page size, normalized to 1–100 with a default of 20
     * @return cursor page of edit history entries
     */
    CursorPageResponse<PostEditHistoryResponse> listEditHistory(
            UUID requesterId, UUID postId, String cursor, int size);
}
