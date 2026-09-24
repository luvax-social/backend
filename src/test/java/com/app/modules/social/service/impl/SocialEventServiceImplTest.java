package com.app.modules.social.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.outbox.service.OutboxService;
import com.app.modules.social.entity.Follow;
import com.app.modules.social.entity.FollowId;
import com.app.modules.social.enums.FollowStatus;
import com.app.modules.social.messaging.SocialEventTypes;

@ExtendWith(MockitoExtension.class)
class SocialEventServiceImplTest {

    @Mock private OutboxService outboxService;

    private SocialEventServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SocialEventServiceImpl(outboxService);
    }

    @Test
    void publishFollowCreated_acceptedFollow_recordsFollowedEvent() {
        UUID followerId = UUID.randomUUID();
        UUID followingId = UUID.randomUUID();
        Follow follow = follow(followerId, followingId, FollowStatus.ACCEPTED);

        service.publishFollowCreated(follow);

        verify(outboxService)
                .enqueue(
                        SocialEventTypes.USER_FOLLOWED_V1,
                        SocialEventTypes.USER_FOLLOWED_V1,
                        "user",
                        followingId,
                        followerId,
                        Map.of(
                                "followerId",
                                followerId.toString(),
                                "followingId",
                                followingId.toString(),
                                "status",
                                FollowStatus.ACCEPTED));
    }

    @Test
    void publishFollowCreated_pendingFollow_recordsFollowRequestedEvent() {
        UUID followerId = UUID.randomUUID();
        UUID followingId = UUID.randomUUID();
        Follow follow = follow(followerId, followingId, FollowStatus.PENDING);

        service.publishFollowCreated(follow);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(outboxService)
                .enqueue(
                        org.mockito.ArgumentMatchers.eq(SocialEventTypes.USER_FOLLOW_REQUESTED_V1),
                        org.mockito.ArgumentMatchers.eq(SocialEventTypes.USER_FOLLOW_REQUESTED_V1),
                        org.mockito.ArgumentMatchers.eq("user"),
                        org.mockito.ArgumentMatchers.eq(followingId),
                        org.mockito.ArgumentMatchers.eq(followerId),
                        dataCaptor.capture());
        assertThat(dataCaptor.getValue())
                .containsEntry("followerId", followerId.toString())
                .containsEntry("followingId", followingId.toString())
                .containsEntry("status", FollowStatus.PENDING);
    }

    private static Follow follow(UUID followerId, UUID followingId, FollowStatus status) {
        return Follow.builder().id(new FollowId(followerId, followingId)).status(status).build();
    }

    @Test
    void publishUnfollowed_recordsFollowerFollowingAndPreviousStatus() {
        UUID follower = UUID.randomUUID();
        UUID following = UUID.randomUUID();

        service.publishUnfollowed(follower, following, FollowStatus.PENDING);

        verify(outboxService)
                .enqueue(
                        SocialEventTypes.USER_UNFOLLOWED_V1,
                        SocialEventTypes.USER_UNFOLLOWED_V1,
                        "user",
                        following,
                        follower,
                        Map.of(
                                "followerId",
                                follower.toString(),
                                "followingId",
                                following.toString(),
                                "previousStatus",
                                FollowStatus.PENDING));
    }

    @Test
    void publishFollowRequestResolved_approvalAndRejection_useTheirOwnEventTypes() {
        UUID requester = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        Map<String, Object> data =
                Map.of("requesterId", requester.toString(), "approverId", approver.toString());

        service.publishFollowRequestResolved(requester, approver, true);
        service.publishFollowRequestResolved(requester, approver, false);

        verify(outboxService)
                .enqueue(
                        SocialEventTypes.USER_FOLLOW_REQUEST_APPROVED_V1,
                        SocialEventTypes.USER_FOLLOW_REQUEST_APPROVED_V1,
                        "user",
                        approver,
                        approver,
                        data);
        verify(outboxService)
                .enqueue(
                        SocialEventTypes.USER_FOLLOW_REQUEST_REJECTED_V1,
                        SocialEventTypes.USER_FOLLOW_REQUEST_REJECTED_V1,
                        "user",
                        approver,
                        approver,
                        data);
    }

    @Test
    void publishBlocked_recordsBothUsers() {
        UUID blocker = UUID.randomUUID();
        UUID blocked = UUID.randomUUID();

        service.publishBlocked(blocker, blocked);

        verify(outboxService)
                .enqueue(
                        SocialEventTypes.USER_BLOCKED_V1,
                        SocialEventTypes.USER_BLOCKED_V1,
                        "user",
                        blocked,
                        blocker,
                        Map.of("blockerId", blocker.toString(), "blockedId", blocked.toString()));
    }
}
