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
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.common.pagination.Cursor;
import com.app.common.pagination.CursorCodec;
import com.app.common.pagination.CursorScope;
import com.app.common.pagination.TimeCursors;
import com.app.common.response.CursorPageResponse;
import com.app.common.response.UserSummaryResponse;
import com.app.modules.notification.config.NotificationProperties;
import com.app.modules.notification.dto.request.AdvanceSeenRequest;
import com.app.modules.notification.dto.response.FollowRequestSummaryResponse;
import com.app.modules.notification.dto.response.NotificationKeyResponse;
import com.app.modules.notification.dto.response.NotificationResponse;
import com.app.modules.notification.dto.response.NotificationStateResponse;
import com.app.modules.notification.dto.response.UnseenCountResponse;
import com.app.modules.notification.entity.Notification;
import com.app.modules.notification.entity.enums.NotificationCategory;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.mapper.NotificationMapper;
import com.app.modules.notification.messaging.NotificationEventTypes;
import com.app.modules.notification.repository.NotificationAggregationRepository;
import com.app.modules.notification.repository.NotificationAggregationRepository.GroupWrite;
import com.app.modules.notification.repository.NotificationAggregationRepository.Removal;
import com.app.modules.notification.repository.NotificationFeedRepository;
import com.app.modules.notification.repository.NotificationFeedRepository.Key;
import com.app.modules.notification.repository.NotificationRepository;
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

    @Mock private NotificationRepository notificationRepository;
    @Mock private NotificationAggregationRepository aggregationRepository;
    @Mock private NotificationFeedRepository feedRepository;
    @Mock private NotificationSeenStateRepository seenStateRepository;
    @Mock private NotificationTypePolicy typePolicy;
    @Mock private NotificationMapper notificationMapper;
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
                        notificationRepository,
                        aggregationRepository,
                        feedRepository,
                        seenStateRepository,
                        typePolicy,
                        new NotificationProperties(WINDOW, Duration.ofMinutes(30), 2),
                        notificationMapper,
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
    void markAsRead_notOwner_throws403() {
        UUID notificationId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        when(notificationRepository.findByIdAndRecipientId(notificationId, recipientId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markAsRead(notificationId, recipientId))
                .isInstanceOf(AppException.class)
                .satisfies(
                        ex ->
                                assertThat(((AppException) ex).getHttpStatus().value())
                                        .isEqualTo(403));
    }

    @Test
    void markAsRead_alreadyRead_noUpdate() {
        UUID notificationId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        Notification notification =
                Notification.builder()
                        .id(notificationId)
                        .recipientId(recipientId)
                        .type(NotificationType.LIKE_POST)
                        .category(NotificationType.LIKE_POST.category())
                        .readAt(OffsetDateTime.now())
                        .build();
        when(notificationRepository.findByIdAndRecipientId(notificationId, recipientId))
                .thenReturn(Optional.of(notification));

        service.markAsRead(notificationId, recipientId);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void markAsRead_unread_setsReadFields() {
        UUID notificationId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        Notification notification =
                Notification.builder()
                        .id(notificationId)
                        .recipientId(recipientId)
                        .type(NotificationType.LIKE_POST)
                        .category(NotificationType.LIKE_POST.category())
                        .build();
        when(notificationRepository.findByIdAndRecipientId(notificationId, recipientId))
                .thenReturn(Optional.of(notification));

        service.markAsRead(notificationId, recipientId);

        verify(notificationRepository, times(1)).save(notification);
        assertThat(notification.getReadAt()).isNotNull();
    }

    @Test
    void markAllAsRead_called_delegatesToRepository() {
        UUID recipientId = UUID.randomUUID();

        service.markAllAsRead(recipientId);

        verify(notificationRepository).markAllAsRead(eq(recipientId), any(OffsetDateTime.class));
    }

    @Test
    void getUnreadCount_called_returnsRepositoryValue() {
        UUID recipientId = UUID.randomUUID();
        when(notificationRepository.countByRecipientIdAndIsReadFalse(recipientId)).thenReturn(7L);

        assertThat(service.getUnreadCount(recipientId)).isEqualTo(7L);
    }

    @Test
    void getUnreadCount_noUnreadNotifications_returnsZero() {
        UUID recipientId = UUID.randomUUID();
        when(notificationRepository.countByRecipientIdAndIsReadFalse(recipientId)).thenReturn(0L);

        assertThat(service.getUnreadCount(recipientId)).isEqualTo(0L);
    }

    @Test
    void listNotifications_firstPage_noCursor_returnsContent() {
        UUID recipientId = UUID.randomUUID();
        Notification row = mockNotification();
        when(notificationRepository.findFirstByRecipient(eq(recipientId), any(PageRequest.class)))
                .thenReturn(List.of(row));
        when(notificationMapper.toResponse(any(), any())).thenReturn(mockResponse());

        CursorPageResponse<NotificationResponse> result =
                service.listNotifications(recipientId, null, 20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getPageInfo().isHasNextPage()).isFalse();
        verify(notificationRepository, never()).findById(any());
    }

    @Test
    void listNotifications_overFetchReturnsExtraRow_hasNextPage() {
        UUID recipientId = UUID.randomUUID();
        int limit = 2;
        // The service over-fetches limit + 1 rows; the extra row proves a further page exists and
        // is
        // trimmed off before the content is returned.
        List<Notification> rows =
                List.of(mockNotification(), mockNotification(), mockNotification());
        when(notificationRepository.findFirstByRecipient(eq(recipientId), any(PageRequest.class)))
                .thenReturn(rows);
        when(notificationMapper.toResponse(any(), any())).thenReturn(mockResponse());

        CursorPageResponse<NotificationResponse> result =
                service.listNotifications(recipientId, null, limit);

        assertThat(result.getPageInfo().isHasNextPage()).isTrue();
        assertThat(result.getContent()).hasSize(2);
    }

    @Test
    void listNotifications_fewerThanLimit_hasNoNextPage() {
        UUID recipientId = UUID.randomUUID();
        int limit = 5;
        when(notificationRepository.findFirstByRecipient(eq(recipientId), any(PageRequest.class)))
                .thenReturn(List.of(mockNotification()));
        when(notificationMapper.toResponse(any(), any())).thenReturn(mockResponse());

        CursorPageResponse<NotificationResponse> result =
                service.listNotifications(recipientId, null, limit);

        assertThat(result.getPageInfo().isHasNextPage()).isFalse();
    }

    @Test
    void listNotifications_malformedCursor_throwsInvalidCursor() {
        UUID recipientId = UUID.randomUUID();

        assertThatThrownBy(() -> service.listNotifications(recipientId, "!!!not-a-cursor!!!", 20))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.INVALID_CURSOR);
    }

    @Test
    void listNotifications_validCursor_pagesFromCursorPosition() {
        UUID recipientId = UUID.randomUUID();
        UUID cursorId = UUID.randomUUID();
        OffsetDateTime cursorTime = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        String cursor =
                CursorCodec.encode(
                        new Cursor(TimeCursors.toMicros(cursorTime), cursorId),
                        CursorScope.NOTIFICATIONS);
        // The codec carries microsecond precision; the decoded time round-trips through micros.
        OffsetDateTime expectedTime = TimeCursors.fromMicros(TimeCursors.toMicros(cursorTime));
        when(notificationRepository.findByRecipientBefore(
                        eq(recipientId), eq(expectedTime), eq(cursorId), any(PageRequest.class)))
                .thenReturn(List.of(mockNotification()));
        when(notificationMapper.toResponse(any(), any())).thenReturn(mockResponse());

        CursorPageResponse<NotificationResponse> result =
                service.listNotifications(recipientId, cursor, 20);

        assertThat(result.getContent()).hasSize(1);
        verify(notificationRepository)
                .findByRecipientBefore(
                        eq(recipientId), eq(expectedTime), eq(cursorId), any(PageRequest.class));
    }

    @Test
    void listNotifications_cursorIsAPositionNotAnIdLookup() {
        UUID recipientId = UUID.randomUUID();
        UUID cursorId = UUID.randomUUID();
        OffsetDateTime cursorTime = OffsetDateTime.now(ZoneOffset.UTC);
        String cursor =
                CursorCodec.encode(
                        new Cursor(TimeCursors.toMicros(cursorTime), cursorId),
                        CursorScope.NOTIFICATIONS);
        when(notificationRepository.findByRecipientBefore(
                        eq(recipientId), any(), eq(cursorId), any(PageRequest.class)))
                .thenReturn(List.of());

        service.listNotifications(recipientId, cursor, 20);

        // The opaque cursor is a keyset position, not a notification id, so no ownership or
        // existence lookup occurs - there is no cross-tenant oracle to leak.
        verify(notificationRepository, never()).findByIdAndRecipientId(any(), any());
    }

    private static Notification mockNotification() {
        return Notification.builder()
                .id(UUID.randomUUID())
                .recipientId(UUID.randomUUID())
                .type(NotificationType.FOLLOW)
                .category(NotificationType.FOLLOW.category())
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private static NotificationResponse mockResponse() {
        return new NotificationResponse(
                UUID.randomUUID(),
                null,
                NotificationType.FOLLOW,
                null,
                null,
                null,
                null,
                false,
                null,
                OffsetDateTime.now(ZoneOffset.UTC));
    }
}
