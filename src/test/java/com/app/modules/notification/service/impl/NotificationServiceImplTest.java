package com.app.modules.notification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.common.pagination.Cursor;
import com.app.common.pagination.CursorCodec;
import com.app.common.pagination.CursorScope;
import com.app.common.pagination.TimeCursors;
import com.app.common.response.UserSummaryResponse;
import com.app.modules.notification.config.NotificationProperties;
import com.app.modules.notification.dto.request.AdvanceSeenRequest;
import com.app.modules.notification.dto.request.NotificationPositionRequest;
import com.app.modules.notification.dto.response.FollowRequestSummaryResponse;
import com.app.modules.notification.dto.response.NotificationKeyResponse;
import com.app.modules.notification.dto.response.NotificationPageResponse;
import com.app.modules.notification.dto.response.NotificationReadStateResponse;
import com.app.modules.notification.dto.response.NotificationStateResponse;
import com.app.modules.notification.dto.response.UnseenCountResponse;
import com.app.modules.notification.entity.enums.NotificationCategory;
import com.app.modules.notification.entity.enums.NotificationFilter;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.messaging.NotificationEventTypes;
import com.app.modules.notification.repository.NotificationAggregationRepository;
import com.app.modules.notification.repository.NotificationAggregationRepository.GroupWrite;
import com.app.modules.notification.repository.NotificationAggregationRepository.Removal;
import com.app.modules.notification.repository.NotificationFeedRepository;
import com.app.modules.notification.repository.NotificationFeedRepository.FeedRow;
import com.app.modules.notification.repository.NotificationFeedRepository.Key;
import com.app.modules.notification.repository.NotificationSeenStateRepository;
import com.app.modules.notification.repository.NotificationSeenStateRepository.SeenState;
import com.app.modules.notification.service.NotificationDraft;
import com.app.modules.social.dto.response.PendingFollowRequestSummary;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.dto.response.NotificationPreferencesResponse;
import com.app.modules.users.service.UserNotificationPreferencesService;
import com.app.modules.users.service.UserSummaryService;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    private static final Duration WINDOW = Duration.ofHours(24);

    @Mock private NotificationAggregationRepository aggregationRepository;
    @Mock private NotificationFeedRepository feedRepository;
    @Mock private NotificationSeenStateRepository seenStateRepository;
    @Mock private NotificationItemAssembler itemAssembler;
    @Mock private NotificationTypePolicy typePolicy;
    @Mock private OutboxService outboxService;
    @Mock private UserSummaryService userSummaryService;
    @Mock private UserNotificationPreferencesService preferencesService;
    @Mock private SocialService socialService;
    @Mock private PlatformTransactionManager transactionManager;

    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service =
                new NotificationServiceImpl(
                        aggregationRepository,
                        feedRepository,
                        seenStateRepository,
                        itemAssembler,
                        typePolicy,
                        new NotificationProperties(WINDOW, Duration.ofMinutes(30), 2),
                        outboxService,
                        userSummaryService,
                        preferencesService,
                        socialService,
                        transactionManager);
        lenient().when(typePolicy.isEnabled(any())).thenReturn(true);
        lenient()
                .when(preferencesService.findNotificationPreferences(any()))
                .thenReturn(NotificationPreferencesResponse.ALL_ENABLED);
        lenient().when(socialService.isBlockedBetween(any(), any())).thenReturn(false);
        lenient().when(userSummaryService.loadSummaries(any())).thenReturn(Map.of());
        lenient()
                .when(aggregationRepository.insertSingle(any(), any(), anyBoolean()))
                .thenReturn(UUID.randomUUID());
        lenient()
                .when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void create_selfNotification_writesNothing() {
        UUID userId = UUID.randomUUID();

        boolean written =
                service.create(
                        userId,
                        userId,
                        NotificationType.LIKE_POST,
                        "post",
                        UUID.randomUUID(),
                        null);

        assertThat(written).isFalse();
        verifyNoInteractions(aggregationRepository, outboxService);
    }

    @Test
    void create_typeDisabledByOperator_writesNothing() {
        when(typePolicy.isEnabled(NotificationType.COMMENT_POST)).thenReturn(false);

        boolean written =
                service.create(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        NotificationType.COMMENT_POST,
                        "comment",
                        UUID.randomUUID(),
                        UUID.randomUUID());

        assertThat(written).isFalse();
        verifyNoInteractions(aggregationRepository, outboxService);
    }

    @Test
    void create_notifyFollowsOff_writesNeitherFollowNorRequest() {
        UUID recipientId = UUID.randomUUID();
        when(preferencesService.findNotificationPreferences(recipientId))
                .thenReturn(new NotificationPreferencesResponse(true, true, false, true, true));

        service.create(UUID.randomUUID(), recipientId, NotificationType.FOLLOW, null, null, null);
        service.create(
                UUID.randomUUID(), recipientId, NotificationType.FOLLOW_REQUEST, null, null, null);

        verifyNoInteractions(aggregationRepository, outboxService);
    }

    @Test
    void create_notifyLikesOff_writesNoLike() {
        UUID recipientId = UUID.randomUUID();
        when(preferencesService.findNotificationPreferences(recipientId))
                .thenReturn(new NotificationPreferencesResponse(false, true, true, true, true));

        service.create(
                UUID.randomUUID(),
                recipientId,
                NotificationType.LIKE_POST,
                "post",
                UUID.randomUUID(),
                null);

        verifyNoInteractions(aggregationRepository, outboxService);
    }

    @Test
    void create_notifyCommentsOff_writesNoComment() {
        UUID recipientId = UUID.randomUUID();
        when(preferencesService.findNotificationPreferences(recipientId))
                .thenReturn(new NotificationPreferencesResponse(true, false, true, true, true));

        service.create(
                UUID.randomUUID(),
                recipientId,
                NotificationType.COMMENT_POST,
                "comment",
                UUID.randomUUID(),
                UUID.randomUUID());

        verifyNoInteractions(aggregationRepository, outboxService);
    }

    @Test
    void create_storyView_isNeverPreferenceSuppressed() {
        UUID recipientId = UUID.randomUUID();
        lenient()
                .when(preferencesService.findNotificationPreferences(recipientId))
                .thenReturn(new NotificationPreferencesResponse(false, false, false, false, false));
        when(aggregationRepository.upsertGroup(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new GroupWrite(UUID.randomUUID(), true, true));

        boolean written =
                service.create(
                        UUID.randomUUID(),
                        recipientId,
                        NotificationType.STORY_VIEW,
                        "story",
                        UUID.randomUUID(),
                        null);

        assertThat(written).isTrue();
    }

    @Test
    void create_actorAndRecipientInABlock_writesNothing() {
        UUID actorId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        when(socialService.isBlockedBetween(actorId, recipientId)).thenReturn(true);

        boolean written =
                service.create(
                        actorId,
                        recipientId,
                        NotificationType.COMMENT_POST,
                        "comment",
                        UUID.randomUUID(),
                        UUID.randomUUID());

        assertThat(written).isFalse();
        verifyNoInteractions(aggregationRepository, outboxService);
    }

    @Test
    void create_singleRow_insertsAddsMemberPublishesAndStampsActivityLast() {
        UUID actorId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();
        when(aggregationRepository.insertSingle(any(), any(), anyBoolean()))
                .thenReturn(notificationId);
        when(userSummaryService.loadSummaries(List.of(actorId)))
                .thenReturn(Map.of(actorId, summary(actorId, true)));

        boolean written =
                service.create(
                        actorId,
                        recipientId,
                        NotificationType.COMMENT_POST,
                        "comment",
                        UUID.randomUUID(),
                        postId);

        assertThat(written).isTrue();
        InOrder order = inOrder(aggregationRepository, outboxService);
        order.verify(aggregationRepository)
                .insertSingle(
                        argThat(d -> d.postId().equals(postId) && d.actorId().equals(actorId)),
                        eq(NotificationCategory.COMMENT),
                        eq(true));
        order.verify(aggregationRepository).addMember(notificationId, actorId);
        order.verify(outboxService)
                .enqueue(
                        eq(NotificationEventTypes.NOTIFICATION_UPSERTED_V1),
                        eq(NotificationEventTypes.NOTIFICATION_UPSERTED_V1),
                        eq("notification"),
                        eq(notificationId),
                        eq(actorId),
                        argThat(data -> recipientId.toString().equals(data.get("recipientId"))));
        order.verify(aggregationRepository).touchActivity(notificationId);
    }

    @Test
    void create_systemNotice_hasNoMemberAndCarriesItsAuditRow() {
        UUID recipientId = UUID.randomUUID();
        UUID auditId = UUID.randomUUID();

        boolean written =
                service.create(
                        NotificationDraft.systemNotice(
                                recipientId,
                                NotificationType.COMMENT_REMOVED,
                                "admin_action",
                                auditId,
                                null,
                                "spam",
                                auditId));

        assertThat(written).isTrue();
        verify(aggregationRepository)
                .insertSingle(
                        argThat(
                                d ->
                                        auditId.equals(d.adminActionId())
                                                && "spam".equals(d.message())),
                        eq(NotificationCategory.SYSTEM),
                        eq(false));
        verify(aggregationRepository, never()).addMember(any(), any());
        verifyNoInteractions(preferencesService, socialService);
    }

    @Test
    void create_aggregatableType_joinsTheGroupForItsTarget() {
        UUID actorId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        when(aggregationRepository.upsertGroup(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new GroupWrite(groupId, false, true));

        boolean written =
                service.create(
                        actorId,
                        UUID.randomUUID(),
                        NotificationType.LIKE_POST,
                        "post",
                        postId,
                        postId);

        assertThat(written).isTrue();
        verify(aggregationRepository)
                .upsertGroup(
                        any(),
                        eq(NotificationCategory.LIKE),
                        eq("like_post:" + postId),
                        eq(WINDOW),
                        eq(false));
        verify(aggregationRepository, never()).insertSingle(any(), any(), anyBoolean());
        verify(aggregationRepository).touchActivity(groupId);
    }

    @Test
    void create_actorAlreadyInTheGroup_changesNothingAndPublishesNothing() {
        when(aggregationRepository.upsertGroup(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new GroupWrite(UUID.randomUUID(), false, false));

        boolean written =
                service.create(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        NotificationType.FOLLOW,
                        null,
                        null,
                        null);

        assertThat(written).isFalse();
        verifyNoInteractions(outboxService);
        verify(aggregationRepository, never()).touchActivity(any());
    }

    @Test
    void retract_likeLeavingOtherActors_publishesUpsertAndResyncsTheVerifiedFlag() {
        UUID liker = UUID.randomUUID();
        UUID remaining = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        when(aggregationRepository.removeMemberFromGroups(
                        recipientId, liker, "like_post:" + postId))
                .thenReturn(List.of(new Removal(groupId, false, remaining)));
        when(userSummaryService.loadSummaries(List.of(remaining)))
                .thenReturn(Map.of(remaining, summary(remaining, true)));

        service.retract(liker, recipientId, NotificationType.LIKE_POST, postId);

        verify(aggregationRepository).setActorVerified(groupId, true);
        verify(outboxService)
                .enqueue(
                        eq(NotificationEventTypes.NOTIFICATION_UPSERTED_V1),
                        any(),
                        any(),
                        eq(groupId),
                        eq(liker),
                        any());
        verify(aggregationRepository, never()).touchActivity(any());
    }

    @Test
    void retract_lastActor_publishesDeleted() {
        UUID liker = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        when(aggregationRepository.removeMemberFromGroups(
                        recipientId, liker, "like_comment:" + commentId))
                .thenReturn(List.of(new Removal(groupId, true, liker)));

        service.retract(liker, recipientId, NotificationType.LIKE_COMMENT, commentId);

        verify(outboxService)
                .enqueue(
                        eq(NotificationEventTypes.NOTIFICATION_DELETED_V1),
                        any(),
                        any(),
                        eq(groupId),
                        eq(liker),
                        argThat(data -> List.of(groupId.toString()).equals(data.get("ids"))));
        verify(aggregationRepository, never()).setActorVerified(any(), anyBoolean());
    }

    @Test
    void retract_follow_removesTheActorFromEveryFollowRow() {
        UUID follower = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();

        service.retract(follower, recipientId, NotificationType.FOLLOW, null);

        verify(aggregationRepository).removeMemberFromFollowRows(recipientId, follower);
    }

    @Test
    void retract_typeThatNeverRetracts_isRejected() {
        assertThatThrownBy(
                        () ->
                                service.retract(
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        NotificationType.COMMENT_POST,
                                        UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveFollowRequest_approved_convertsTheRequestInPlace() {
        UUID requester = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        when(aggregationRepository.findLiveFollowRequest(approver, requester))
                .thenReturn(Optional.of(requestId));
        when(aggregationRepository.convertRequestToFollow(requestId)).thenReturn(true);

        service.resolveFollowRequest(requester, approver, true);

        verify(outboxService)
                .enqueue(
                        eq(NotificationEventTypes.NOTIFICATION_UPSERTED_V1),
                        any(),
                        any(),
                        eq(requestId),
                        eq(requester),
                        argThat(data -> approver.toString().equals(data.get("recipientId"))));
        verify(aggregationRepository, never()).touchActivity(any());
    }

    @Test
    void resolveFollowRequest_approvedWithoutAStoredRequest_doesNothing() {
        UUID requester = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        when(aggregationRepository.findLiveFollowRequest(approver, requester))
                .thenReturn(Optional.empty());

        service.resolveFollowRequest(requester, approver, true);

        verifyNoInteractions(outboxService);
    }

    @Test
    void resolveFollowRequest_rejected_withdrawsTheRequest() {
        UUID requester = UUID.randomUUID();
        UUID approver = UUID.randomUUID();

        service.resolveFollowRequest(requester, approver, false);

        verify(aggregationRepository).removeMemberFromFollowRows(approver, requester);
    }

    @Test
    void onBlock_removesEachUserFromTheOthersGroups() {
        UUID blocker = UUID.randomUUID();
        UUID blocked = UUID.randomUUID();

        service.onBlock(blocker, blocked);

        verify(aggregationRepository).removeMemberOnBlock(blocker, blocked);
        verify(aggregationRepository).removeMemberOnBlock(blocked, blocker);
    }

    @Test
    void resyncActorVerified_rewritesInBatchesUntilAShortOne() {
        UUID actorId = UUID.randomUUID();
        when(userSummaryService.loadSummaries(List.of(actorId)))
                .thenReturn(Map.of(actorId, summary(actorId, true)));
        when(aggregationRepository.resyncActorVerified(eq(actorId), eq(true), anyInt()))
                .thenReturn(2, 2, 1);

        int rewritten = service.resyncActorVerified(actorId);

        assertThat(rewritten).isEqualTo(5);
        verify(aggregationRepository, times(3)).resyncActorVerified(actorId, true, 2);
        verify(transactionManager, times(3)).commit(any());
    }

    @Test
    void getState_neverOpened_countsEveryVisibleRowAndHasNoWatermarks() {
        UUID userId = UUID.randomUUID();
        when(seenStateRepository.find(userId)).thenReturn(Optional.empty());
        when(feedRepository.countUnseen(userId, null)).thenReturn(100L);
        when(socialService.summarizePendingFollowRequests(userId, 2, 100))
                .thenReturn(new PendingFollowRequestSummary(0, List.of()));

        NotificationStateResponse state = service.getState(userId);

        assertThat(state.unseen().count()).isEqualTo(99);
        assertThat(state.unseen().capped()).isTrue();
        assertThat(state.seen()).isNull();
        assertThat(state.previous()).isNull();
        assertThat(state.followRequests()).isEqualTo(FollowRequestSummaryResponse.NONE);
    }

    @Test
    void getState_pendingRequests_summarisesCountAndNewestRequesters() {
        UUID userId = UUID.randomUUID();
        UUID newest = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        Key seen = new Key(OffsetDateTime.now(ZoneOffset.UTC), UUID.randomUUID());
        when(seenStateRepository.find(userId)).thenReturn(Optional.of(new SeenState(seen, seen)));
        when(feedRepository.countUnseen(userId, seen)).thenReturn(3L);
        when(socialService.summarizePendingFollowRequests(userId, 2, 100))
                .thenReturn(new PendingFollowRequestSummary(5, List.of(newest, next)));
        when(userSummaryService.loadSummaries(List.of(newest, next)))
                .thenReturn(Map.of(newest, summary(newest, false), next, summary(next, true)));

        NotificationStateResponse state = service.getState(userId);

        assertThat(state.unseen()).isEqualTo(new UnseenCountResponse(3, false));
        assertThat(state.seen())
                .isEqualTo(new NotificationKeyResponse(seen.activityAt(), seen.id()));
        assertThat(state.followRequests().count()).isEqualTo(5);
        assertThat(state.followRequests().recent())
                .extracting(UserSummaryResponse::id)
                .containsExactly(newest, next);
    }

    @Test
    void advanceSeen_rowOfTheCaller_advancesAndPublishesTheSeenEvent() {
        UUID userId = UUID.randomUUID();
        Key key = new Key(OffsetDateTime.now(ZoneOffset.UTC), UUID.randomUUID());
        when(seenStateRepository.advance(
                        userId, key.activityAt(), key.id(), Duration.ofMinutes(30)))
                .thenReturn(Optional.of(new SeenState(key, null)));
        when(feedRepository.countUnseen(userId, key)).thenReturn(0L);
        when(socialService.summarizePendingFollowRequests(any(), anyInt(), anyInt()))
                .thenReturn(new PendingFollowRequestSummary(0, List.of()));

        NotificationStateResponse state =
                service.advanceSeen(userId, new AdvanceSeenRequest(key.activityAt(), key.id()));

        assertThat(state.unseen().count()).isZero();
        verify(outboxService)
                .enqueue(
                        eq(NotificationEventTypes.NOTIFICATION_SEEN_V1),
                        eq(NotificationEventTypes.NOTIFICATION_SEEN_V1),
                        eq("notification"),
                        eq(userId),
                        eq(userId),
                        argThat(data -> userId.toString().equals(data.get("recipientId"))));
    }

    @Test
    void advanceSeen_rowOfAnotherAccount_isForbiddenAndPublishesNothing() {
        UUID userId = UUID.randomUUID();
        when(seenStateRepository.advance(any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.advanceSeen(
                                        userId,
                                        new AdvanceSeenRequest(
                                                OffsetDateTime.now(ZoneOffset.UTC),
                                                UUID.randomUUID())))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.FORBIDDEN);
        verifyNoInteractions(outboxService);
    }

    private static UserSummaryResponse summary(UUID id, boolean verified) {
        return new UserSummaryResponse(id, "user", "User", null, verified, null);
    }

    @Test
    void markRead_unreadRow_setsReadAtAndPublishesTheReadState() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(aggregationRepository.markRead(userId, id)).thenReturn(Optional.of(now));

        NotificationReadStateResponse state = service.markRead(userId, id);

        assertThat(state.readAt()).isEqualTo(now);
        verify(outboxService)
                .enqueue(
                        eq(NotificationEventTypes.NOTIFICATION_READ_STATE_CHANGED_V1),
                        any(),
                        any(),
                        eq(id),
                        eq(userId),
                        argThat(data -> List.of(id.toString()).equals(data.get("ids"))));
    }

    @Test
    void markRead_alreadyRead_keepsTheOriginalTimeAndPublishesNothing() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        OffsetDateTime earlier = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        when(aggregationRepository.markRead(userId, id)).thenReturn(Optional.empty());
        when(aggregationRepository.findLiveReadState(userId, id))
                .thenReturn(Optional.of(Optional.of(earlier)));

        assertThat(service.markRead(userId, id).readAt()).isEqualTo(earlier);
        verifyNoInteractions(outboxService);
    }

    @Test
    void markRead_rowOfAnotherAccountOrDeleted_isForbidden() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(aggregationRepository.markRead(userId, id)).thenReturn(Optional.empty());
        when(aggregationRepository.findLiveReadState(userId, id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead(userId, id))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.FORBIDDEN);
    }

    @Test
    void markUnread_readRow_clearsReadAtAndPublishes() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(aggregationRepository.markUnread(userId, id)).thenReturn(true);

        assertThat(service.markUnread(userId, id).readAt()).isNull();
        verify(outboxService)
                .enqueue(
                        eq(NotificationEventTypes.NOTIFICATION_READ_STATE_CHANGED_V1),
                        any(),
                        any(),
                        eq(id),
                        eq(userId),
                        argThat(data -> !data.containsKey("readAt")));
    }

    @Test
    void markUnread_rowOfAnotherAccount_isForbidden() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(aggregationRepository.markUnread(userId, id)).thenReturn(false);
        when(aggregationRepository.findLiveReadState(userId, id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markUnread(userId, id)).isInstanceOf(AppException.class);
    }

    @Test
    void markReadUpTo_publishesOneEventCarryingTheBound() {
        UUID userId = UUID.randomUUID();
        NotificationPositionRequest upTo =
                new NotificationPositionRequest(
                        OffsetDateTime.now(ZoneOffset.UTC), UUID.randomUUID());
        when(aggregationRepository.markReadUpTo(userId, upTo.activityAt(), upTo.id()))
                .thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(service.markReadUpTo(userId, upTo).updated()).isEqualTo(2);
        verify(outboxService, times(1))
                .enqueue(
                        eq(NotificationEventTypes.NOTIFICATION_READ_STATE_CHANGED_V1),
                        any(),
                        any(),
                        eq(userId),
                        eq(userId),
                        argThat(data -> data.get("upTo") != null && data.get("readAt") != null));
    }

    @Test
    void markReadUpTo_nothingToRead_publishesNothing() {
        UUID userId = UUID.randomUUID();
        NotificationPositionRequest upTo =
                new NotificationPositionRequest(
                        OffsetDateTime.now(ZoneOffset.UTC), UUID.randomUUID());
        when(aggregationRepository.markReadUpTo(any(), any(), any())).thenReturn(List.of());

        assertThat(service.markReadUpTo(userId, upTo).updated()).isZero();
        verifyNoInteractions(outboxService);
    }

    @Test
    void delete_ownRow_softDeletesAndPublishes_andASecondDeleteIsANoOp() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(aggregationRepository.softDelete(userId, id)).thenReturn(true, false);
        when(aggregationRepository.isOwnedBy(userId, id)).thenReturn(true);

        service.delete(userId, id);
        service.delete(userId, id);

        verify(outboxService, times(1))
                .enqueue(
                        eq(NotificationEventTypes.NOTIFICATION_DELETED_V1),
                        any(),
                        any(),
                        eq(id),
                        eq(userId),
                        any());
    }

    @Test
    void delete_rowOfAnotherAccount_isForbidden() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(aggregationRepository.softDelete(userId, id)).thenReturn(false);
        when(aggregationRepository.isOwnedBy(userId, id)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(userId, id)).isInstanceOf(AppException.class);
    }

    @Test
    void listFeed_firstPage_overFetchesOne_andCarriesTheHead() {
        UUID userId = UUID.randomUUID();
        List<FeedRow> rows = List.of(feedRow(), feedRow(), feedRow());
        Key head = new Key(rows.get(0).activityAt(), rows.get(0).id());
        when(feedRepository.findPage(userId, NotificationFilter.ALL, null, 3)).thenReturn(rows);
        when(seenStateRepository.find(userId)).thenReturn(Optional.empty());
        when(itemAssembler.assemble(eq(userId), any(), eq(null))).thenReturn(List.of());
        when(feedRepository.findHead(userId)).thenReturn(Optional.of(head));

        NotificationPageResponse page = service.listFeed(userId, NotificationFilter.ALL, null, 2);

        assertThat(page.pageInfo().isHasNextPage()).isTrue();
        assertThat(page.head())
                .isEqualTo(new NotificationKeyResponse(head.activityAt(), head.id()));
        verify(itemAssembler).assemble(userId, rows.subList(0, 2), null);
    }

    @Test
    void listFeed_laterPage_seeksFromTheCursorAndHasNoHead() {
        UUID userId = UUID.randomUUID();
        UUID cursorId = UUID.randomUUID();
        OffsetDateTime cursorTime = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        String cursor =
                CursorCodec.encode(
                        new Cursor(TimeCursors.toMicros(cursorTime), cursorId),
                        CursorScope.NOTIFICATIONS);
        Key expected = new Key(TimeCursors.fromMicros(TimeCursors.toMicros(cursorTime)), cursorId);
        when(feedRepository.findPage(userId, NotificationFilter.UNREAD, expected, 21))
                .thenReturn(List.of());
        when(seenStateRepository.find(userId)).thenReturn(Optional.empty());
        when(itemAssembler.assemble(any(), any(), any())).thenReturn(List.of());

        NotificationPageResponse page =
                service.listFeed(userId, NotificationFilter.UNREAD, cursor, 20);

        assertThat(page.head()).isNull();
        assertThat(page.pageInfo().isHasPreviousPage()).isTrue();
        verify(feedRepository, never()).findHead(any());
    }

    @Test
    void listFeed_malformedCursor_isInvalidCursor() {
        assertThatThrownBy(
                        () ->
                                service.listFeed(
                                        UUID.randomUUID(), NotificationFilter.ALL, "!!!", 20))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.INVALID_CURSOR);
    }

    private static FeedRow feedRow() {
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
        return new FeedRow(
                UUID.randomUUID(),
                NotificationType.FOLLOW,
                NotificationType.FOLLOW.category(),
                null,
                null,
                null,
                null,
                null,
                null,
                at,
                at,
                1);
    }
}
