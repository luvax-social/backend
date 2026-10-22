package com.app.modules.post.service;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.app.modules.post.entity.Post;

/** Account-level visibility decisions for posts. */
public interface PostVisibilityService {

    /**
     * Decides whether the viewer may see the given post based on account-level state.
     *
     * <p>The owner and every accepted co-author always see the post. Otherwise visibility follows
     * the owner: a block in either direction between the viewer and the owner, or between the
     * viewer and any accepted co-author, hides the post; a private owner account requires the
     * viewer to hold an accepted follow; a soft-deleted owner hides all of their content. A
     * co-author's own privacy does not widen or narrow the audience. Post status gating (draft,
     * archived) is the caller's concern.
     *
     * @param viewerId authenticated viewer
     * @param post the post whose owner's account state is evaluated
     * @return true when the post is visible to the viewer
     */
    boolean isVisibleTo(UUID viewerId, Post post);

    /**
     * Batch form of {@link #isVisibleTo(UUID, Post)} for many candidate post owners at once.
     *
     * <p>Applies the same account-level rules (owner, block, private-account follow, soft-delete)
     * to the owners alone, with a fixed number of queries however many ids are passed in. It knows
     * nothing about co-authors, so a caller holding posts must use {@link
     * #filterVisiblePostIds(UUID, Map)} instead.
     *
     * @param viewerId authenticated viewer
     * @param ownerIds candidate post-owner ids to evaluate; duplicates are tolerated
     * @return the subset of {@code ownerIds} whose content is visible to the viewer
     */
    Set<UUID> filterVisibleOwnerIds(UUID viewerId, Collection<UUID> ownerIds);

    /**
     * Batch form of {@link #isVisibleTo(UUID, Post)} for many candidate posts at once, co-authors
     * included, with a fixed number of queries however many posts are passed in.
     *
     * @param viewerId authenticated viewer
     * @param ownerByPostId candidate posts, each mapped to the id of its owner
     * @return the ids of the posts the viewer may see
     */
    Set<UUID> filterVisiblePostIds(UUID viewerId, Map<UUID, UUID> ownerByPostId);
}
