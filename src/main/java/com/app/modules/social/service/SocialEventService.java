package com.app.modules.social.service;

import java.util.UUID;

import com.app.modules.social.entity.Follow;
import com.app.modules.social.enums.FollowStatus;

/** Publishes social-domain side-effect events through the transactional outbox. */
public interface SocialEventService {

    /**
     * Records the business event produced by a newly-created follow row.
     *
     * @param follow persisted follow relationship
     */
    void publishFollowCreated(Follow follow);

    /**
     * Records that a follower removed a follow edge: an unfollow, or the cancellation of a pending
     * request.
     *
     * @param followerId the user who followed or requested
     * @param followingId the user who was followed
     * @param previousStatus the status the removed edge had
     */
    void publishUnfollowed(UUID followerId, UUID followingId, FollowStatus previousStatus);

    /**
     * Records the target's answer to a pending follow request.
     *
     * @param requesterId the user who asked to follow
     * @param approverId the private account that answered
     * @param approved true for an approval, false for a rejection
     */
    void publishFollowRequestResolved(UUID requesterId, UUID approverId, boolean approved);

    /**
     * Records a new block.
     *
     * @param blockerId the user who blocked
     * @param blockedId the user who was blocked
     */
    void publishBlocked(UUID blockerId, UUID blockedId);
}
