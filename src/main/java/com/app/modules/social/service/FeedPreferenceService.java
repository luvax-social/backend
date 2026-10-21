package com.app.modules.social.service;

import java.util.UUID;

/**
 * An account's request to see less of one author in its recommendations.
 *
 * <p>The request is a ranking preference only: it hides nothing and is never shown to the author.
 * Every change enqueues its event in the same transaction as the write, and the events are never
 * bound to a notification queue.
 */
public interface FeedPreferenceService {

    /**
     * Records that the account wants to see less of an author.
     *
     * <p>Idempotent. A repeat changes nothing and enqueues no second {@code user.show-fewer.v1}.
     *
     * @param userId the account asking to see less
     * @param authorId the author it wants to see less of
     * @throws com.app.common.exception.AppException with {@code NOT_FOUND} when the author is the
     *     caller, missing, or in a block relationship with the caller in either direction; all
     *     three answer identically so the endpoint is no oracle for a block
     */
    void showFewer(UUID userId, UUID authorId);

    /**
     * Takes back a show-fewer request.
     *
     * <p>Idempotent and independent of the author's current state. {@code
     * user.show-fewer-undone.v1} is enqueued only when a request existed.
     *
     * @param userId the account that asked to see less
     * @param authorId the author it asked to see less of
     */
    void undoShowFewer(UUID userId, UUID authorId);
}
