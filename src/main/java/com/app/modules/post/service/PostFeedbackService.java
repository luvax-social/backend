package com.app.modules.post.service;

import java.util.Collection;
import java.util.Set;
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

    /**
     * The posts, among {@code postIds}, that the viewer marked not interested.
     *
     * <p>One indexed read on the mark's primary key serves the whole candidate set, so a feed round
     * pays one query however many candidates it holds. The mark removes a post from every feed the
     * viewer reads, whichever surface it is on.
     *
     * @param userId the viewer whose marks are read
     * @param postIds candidate posts a feed is about to show; may contain duplicates
     * @return the marked subset; empty when there are no candidates or no mark
     */
    Set<UUID> findNotInterested(UUID userId, Collection<UUID> postIds);
}
