package com.app.modules.post.service;

import java.util.UUID;

import com.app.common.response.CursorPageResponse;
import com.app.modules.post.dto.request.CreatePostRequest;
import com.app.modules.post.dto.request.PostStatusTransitionRequest;
import com.app.modules.post.dto.request.UpdatePostCaptionRequest;
import com.app.modules.post.dto.response.FeedPostResponse;
import com.app.modules.post.dto.response.PostEditHistoryResponse;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.enums.FeedSurface;
import com.app.modules.post.validation.PostTypeFilter;

/** Domain API for post creation, retrieval, lifecycle, and caption edit history. */
public interface PostService {

    /**
     * Creates a post owned by the author with its ordered media items.
     *
     * <p>Every media asset must exist and be owned by the author. Carousel posts require at least
     * two and at most {@code max_post_media_items} assets; image and video posts require exactly
     * one asset of the matching media type. The status may be draft, scheduled or published. A
     * draft and a scheduled post count against the owner's cap on unpublished posts, and a
     * scheduled post needs a due time between one minute and thirty days ahead. A post created as
     * published goes through the publication routine, which associates the caption hashtags and
     * tells the people the post involves. Counters are never written — triggers maintain them.
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
     * <p>Valid transitions: draft to published, draft to scheduled, scheduled to draft, scheduled
     * to scheduled (reschedule), scheduled to published (publish now), published to archived,
     * archived to published (owner only), and any status to removed (owner or admin — equivalent to
     * soft delete). Scheduling needs {@code scheduledAt}; no other target accepts it. Publishing
     * runs the publication routine; archiving and removal delete the hashtag associations. A post
     * that was ever published can never return to draft.
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
     * <p>A post removed before that status was recorded comes back published, which is what restore
     * did for every post at the time, so nothing about those rows changes.
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
     * private and the viewer holds no accepted follow. A user's posts are the ones they own and the
     * ones they co-author after accepting, newest publication first. Drafts, scheduled and archived
     * posts are never listed, including for the owner; the owner reads those through the owner
     * listing.
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
     * Cursor-paginated chronological feed of published posts from accounts the viewer follows, with
     * the viewer's own interaction preferences applied for the given surface.
     *
     * <p>Only accepted follow relationships contribute to the feed; pending follow requests are
     * excluded. Posts from any account involved in a block relationship with the viewer, in either
     * direction, are excluded. Returns an empty page without querying posts when the viewer follows
     * nobody or when all followed accounts are blocked. A post belongs to the feed when its owner
     * or an accepted co-author is followed, appears once, and is hidden when any of its authors is
     * one the viewer muted, asked for fewer posts from, or is blocked with. Posts are ordered by
     * publication time, newest first.
     *
     * <p>Posts the viewer marked not interested are never returned, and neither are posts by
     * accounts whose posts the viewer muted. Accounts the viewer asked to see fewer posts from are
     * also left out on {@link FeedSurface#FOR_YOU_FALLBACK}, the chronological feed standing in for
     * the ranked one, and are kept on {@link FeedSurface#FOLLOWING}. The cursor carries no surface,
     * so the caller names it on every page: the ranked feed passes {@code FOR_YOU_FALLBACK} for the
     * first fallback page and for each continuation page, which arrive at its own endpoint.
     *
     * <p>There is deliberately no overload that omits the surface: a caller that does not name one
     * would silently inherit the Following rules.
     *
     * @param viewerId authenticated viewer
     * @param cursor opaque base64 cursor from the previous page; null or blank for the first page
     * @param size requested page size, normalized to 1–100 with a default of 20
     * @param surface the feed the page is served for
     * @return cursor page of feed posts ordered by publication time, newest first
     */
    CursorPageResponse<FeedPostResponse> getFeed(
            UUID viewerId, String cursor, int size, FeedSurface surface);

    /**
     * Cursor-paginated published posts in which the target holds an approved tag, newest first.
     *
     * <p>Applies the profile gate of {@link #listUserPosts}: a block in either direction reads as
     * an unknown user, and a private account needs an accepted follow unless the viewer is the
     * account itself. Every post is also checked against the viewer's own visibility, so a post by
     * an account the viewer is blocked with is left out. A pending tag lists nothing.
     *
     * @param viewerId authenticated viewer
     * @param targetUserId the tagged person whose tab is read
     * @param cursor opaque base64 cursor from the previous page; null or blank for the first page
     * @param size requested page size, normalized to 1–100 with a default of 20
     * @return cursor page of posts ordered by publication time, newest first
     */
    CursorPageResponse<PostResponse> listTaggedPosts(
            UUID viewerId, UUID targetUserId, String cursor, int size);

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
