package com.app.modules.social.service.impl;

import static com.app.modules.social.messaging.SocialEventTypes.USER_BLOCKED_V1;
import static com.app.modules.social.messaging.SocialEventTypes.USER_FOLLOWED_V1;
import static com.app.modules.social.messaging.SocialEventTypes.USER_FOLLOW_REQUESTED_V1;
import static com.app.modules.social.messaging.SocialEventTypes.USER_FOLLOW_REQUEST_APPROVED_V1;
import static com.app.modules.social.messaging.SocialEventTypes.USER_FOLLOW_REQUEST_REJECTED_V1;
import static com.app.modules.social.messaging.SocialEventTypes.USER_UNFOLLOWED_V1;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import com.app.common.outbox.service.OutboxService;
import com.app.modules.social.entity.Follow;
import com.app.modules.social.enums.FollowStatus;
import com.app.modules.social.service.SocialEventService;

@Service
public class SocialEventServiceImpl implements SocialEventService {

    private static final String AGGREGATE_TYPE_USER = "user";

    private final OutboxService outboxService;

    public SocialEventServiceImpl(OutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @Override
    public void publishFollowCreated(Follow follow) {
        Assert.notNull(follow, "follow must not be null");
        Assert.notNull(follow.getId(), "follow.id must not be null");
        Assert.notNull(follow.getStatus(), "follow.status must not be null");

        UUID followerId = follow.getId().getFollowerId();
        UUID followingId = follow.getId().getFollowingId();
        Assert.notNull(followerId, "follow.followerId must not be null");
        Assert.notNull(followingId, "follow.followingId must not be null");

        String eventType = eventTypeFor(follow.getStatus());
        outboxService.enqueue(
                eventType,
                eventType,
                AGGREGATE_TYPE_USER,
                followingId,
                followerId,
                Map.of(
                        "followerId",
                        followerId.toString(),
                        "followingId",
                        followingId.toString(),
                        "status",
                        follow.getStatus()));
    }

    @Override
    public void publishUnfollowed(UUID followerId, UUID followingId, FollowStatus previousStatus) {
        Assert.notNull(followerId, "followerId must not be null");
        Assert.notNull(followingId, "followingId must not be null");
        Assert.notNull(previousStatus, "previousStatus must not be null");
        outboxService.enqueue(
                USER_UNFOLLOWED_V1,
                USER_UNFOLLOWED_V1,
                AGGREGATE_TYPE_USER,
                followingId,
                followerId,
                Map.of(
                        "followerId",
                        followerId.toString(),
                        "followingId",
                        followingId.toString(),
                        "previousStatus",
                        previousStatus));
    }

    @Override
    public void publishFollowRequestResolved(UUID requesterId, UUID approverId, boolean approved) {
        Assert.notNull(requesterId, "requesterId must not be null");
        Assert.notNull(approverId, "approverId must not be null");
        String eventType =
                approved ? USER_FOLLOW_REQUEST_APPROVED_V1 : USER_FOLLOW_REQUEST_REJECTED_V1;
        outboxService.enqueue(
                eventType,
                eventType,
                AGGREGATE_TYPE_USER,
                approverId,
                approverId,
                Map.of("requesterId", requesterId.toString(), "approverId", approverId.toString()));
    }

    @Override
    public void publishBlocked(UUID blockerId, UUID blockedId) {
        Assert.notNull(blockerId, "blockerId must not be null");
        Assert.notNull(blockedId, "blockedId must not be null");
        outboxService.enqueue(
                USER_BLOCKED_V1,
                USER_BLOCKED_V1,
                AGGREGATE_TYPE_USER,
                blockedId,
                blockerId,
                Map.of("blockerId", blockerId.toString(), "blockedId", blockedId.toString()));
    }

    private static String eventTypeFor(FollowStatus status) {
        return switch (status) {
            case ACCEPTED -> USER_FOLLOWED_V1;
            case PENDING -> USER_FOLLOW_REQUESTED_V1;
        };
    }
}
