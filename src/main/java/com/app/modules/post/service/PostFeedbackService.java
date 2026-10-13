package com.app.modules.post.service;

import java.util.UUID;

/**
 * Explicit negative feedback a viewer gives about a post.
 *
 * <p>The mark is stored in PostgreSQL and announced through the transactional outbox in the same
 * transaction. The author is never told, and nothing in this service filters a feed: reading the
 * mark back is the feed's concern.
 */
public interface PostFeedbackService {

    /**
     * Marks a post as not interesting for the viewer.
     *
     * <p>Idempotent. Marking an already-marked post changes nothing and enqueues no second {@code
     * post.not-interested.v1}.
     *
     * @param userId the viewer giving the feedback
     * @param postId the post being marked
     * @throws com.app.common.exception.AppException with {@code POST_FEEDBACK_SELF} when the post
     *     is the viewer's own, or {@code POST_NOT_FOUND} when it does not exist or is not visible
     *     to the viewer
     */
    void markNotInterested(UUID userId, UUID postId);

    /**
     * Takes back a not-interested mark.
     *
     * <p>Idempotent and independent of the post's current visibility, so a viewer can always clear
     * their own mark. {@code post.not-interested-undone.v1} is enqueued only when a mark existed.
     *
     * @param userId the viewer who gave the feedback
     * @param postId the post whose mark is removed
     */
    void undoNotInterested(UUID userId, UUID postId);
}
