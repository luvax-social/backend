package com.app.modules.comment.service;

import java.util.UUID;

import com.app.modules.post.entity.Post;

/**
 * Read-visibility gate for commenting on a post.
 *
 * <p>This answers only whether the viewer can see the post at all. Interaction rules, such as the
 * post owner's block, interaction limit and Hidden Words, live in {@link
 * com.app.modules.social.policy.InteractionPolicyService} and are applied by the comment write path
 * itself.
 */
public interface CommentAccessPolicyService {

    /**
     * Asserts that the post is visible to the viewer.
     *
     * <p>Delegates to post visibility, which already enforces block relationships and private
     * follow gating. Throws {@code AppException} with {@code POST_COMMENTING_RESTRICTED} when the
     * post is not visible to the viewer. It does not evaluate the post owner's interaction
     * controls, which decide whether the comment is shown or hidden once it is written.
     *
     * @param viewerId authenticated user attempting to comment
     * @param post target post
     */
    void assertCanComment(UUID viewerId, Post post);
}
