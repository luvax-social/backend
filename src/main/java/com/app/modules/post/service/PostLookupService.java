package com.app.modules.post.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.app.modules.post.dto.response.FeedPostResponse;
import com.app.modules.post.entity.Post;

/**
 * Read-only lookup and hydration port for other modules that rank or select posts by id.
 *
 * <p>Keeps cross-module access on the service-interface layer; callers must not reach into the post
 * module's repositories or assembler directly.
 */
public interface PostLookupService {

    /**
     * Loads non-deleted posts for the given ids in no particular order.
     *
     * <p>Soft-deleted posts are absent from the result. Status filtering (for example {@code
     * PUBLISHED} only) and per-viewer visibility remain the caller's responsibility.
     *
     * @param ids post ids to load; may contain ids that no longer exist
     * @return matching posts, at most one per requested id
     */
    List<Post> findActiveByIds(Collection<UUID> ids);

    /**
     * Returns the accepted co-authors of each post, owner excluded.
     *
     * <p>For callers that apply a viewer's follow, mute or show-fewer preferences to every author
     * of a post rather than to its owner alone.
     *
     * @param postIds posts to look up
     * @return accepted co-author ids keyed by post id; absent for a post with none
     */
    Map<UUID, List<UUID>> findAcceptedCoAuthors(Collection<UUID> postIds);

    /**
     * Batch-assembles feed responses with author, media, and viewer-state hydration.
     *
     * @param viewerId the requesting viewer, whose like/save state is batch-resolved
     * @param posts posts to assemble; must not be empty
     * @return feed responses in the same order as the input list, with null ranking score
     */
    List<FeedPostResponse> assembleFeed(UUID viewerId, List<Post> posts);
}
