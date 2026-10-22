package com.app.modules.post.service;

import java.util.UUID;

import com.app.common.response.CursorPageResponse;
import com.app.modules.post.dto.request.ReplaceCollaboratorsRequest;
import com.app.modules.post.dto.request.ReplacePostTagsRequest;
import com.app.modules.post.dto.request.SetPostPlaceRequest;
import com.app.modules.post.dto.request.UpdateAltTextRequest;
import com.app.modules.post.dto.request.UpdatePostContentRequest;
import com.app.modules.post.dto.response.OwnerPostCountsResponse;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.enums.PostStatus;

/**
 * What a post owner does to their own posts outside the public surfaces: reading their drafts,
 * scheduled and archived posts, replacing the content of an unpublished post, and editing the
 * place, tags, co-authors and alt text of a post in any retained status.
 *
 * <p>Every method answers a requester who is not the owner of an unpublished post exactly as if the
 * post did not exist.
 */
public interface PostOwnerService {

    /**
     * Replaces the whole content of a draft or scheduled post.
     *
     * <p>A replacement, not a patch: what the request leaves out is cleared. The status and the
     * scheduled time do not change; they move through the status transition. A post that is
     * published or archived cannot be replaced; its caption, place, tags and alt text have their
     * own edits.
     *
     * @param ownerId the post owner
     * @param postId the post
     * @param request the complete new content
     * @return the post after the replacement
     * @throws com.app.common.exception.AppException with {@code POST_STATE_CONFLICT} when the post
     *     is not a draft or scheduled post
     */
    PostResponse replaceContent(UUID ownerId, UUID postId, UpdatePostContentRequest request);

    /**
     * Lists the owner's posts in one unpublished state.
     *
     * <p>Drafts and archived posts are ordered by last change, newest first; scheduled posts by due
     * time, soonest first. The status is bound into the cursor.
     *
     * @param ownerId the owner
     * @param status {@code draft}, {@code scheduled} or {@code archived}
     * @param cursor opaque cursor from the previous page, or null
     * @param size requested page size, normalised to 1 to 100 with a default of 20
     * @return a cursor page of the owner's posts
     * @throws com.app.common.exception.AppException with {@code BAD_REQUEST} for any other status
     */
    CursorPageResponse<PostResponse> listOwnerPosts(
            UUID ownerId, PostStatus status, String cursor, int size);

    /**
     * Counts the owner's unpublished posts and states the cap.
     *
     * @param ownerId the owner
     * @return the counts and the limit on drafts and scheduled posts together
     */
    OwnerPostCountsResponse counts(UUID ownerId);

    /**
     * Sets or clears the place of a post in any retained status.
     *
     * @param ownerId the post owner
     * @param postId the post
     * @param request the place by id or by typed name; neither clears it
     * @return the post after the change
     * @throws com.app.common.exception.AppException with {@code POST_PLACE_UNAVAILABLE} when the
     *     place was banned or deleted
     */
    PostResponse setPlace(UUID ownerId, UUID postId, SetPostPlaceRequest request);

    /**
     * Replaces the people tagged on a post in any retained status.
     *
     * @param ownerId the post owner
     * @param postId the post
     * @param request the complete tag set
     * @return the post after the change
     */
    PostResponse replaceTags(UUID ownerId, UUID postId, ReplacePostTagsRequest request);

    /**
     * Replaces the accounts invited to co-author a post in any retained status.
     *
     * @param ownerId the post owner
     * @param postId the post
     * @param request the complete set of invitees
     * @return the post after the change
     */
    PostResponse replaceCollaborators(
            UUID ownerId, UUID postId, ReplaceCollaboratorsRequest request);

    /**
     * Changes the alt text of one media item of a post in any retained status.
     *
     * @param ownerId the post owner
     * @param postId the post
     * @param postMediaId the media item
     * @param request the new text; null or blank clears it
     * @return the post after the change
     * @throws com.app.common.exception.AppException with {@code NOT_FOUND} when the item does not
     *     belong to the post
     */
    PostResponse updateAltText(
            UUID ownerId, UUID postId, UUID postMediaId, UpdateAltTextRequest request);
}
