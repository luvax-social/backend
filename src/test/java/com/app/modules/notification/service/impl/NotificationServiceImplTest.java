package com.app.modules.notification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.common.pagination.Cursor;
import com.app.common.pagination.CursorCodec;
import com.app.common.pagination.CursorScope;
import com.app.common.pagination.TimeCursors;
import com.app.common.response.CursorPageResponse;
import com.app.modules.notification.dto.response.NotificationResponse;
import com.app.modules.notification.entity.Notification;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.mapper.NotificationMapper;
import com.app.modules.notification.messaging.NotificationEventTypes;
import com.app.modules.notification.repository.NotificationRepository;
import com.app.modules.social.repository.BlockRepository;
import com.app.modules.users.entity.UserSettings;
import com.app.modules.users.repository.UserSettingsRepository;
import com.app.modules.users.service.UserSummaryService;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private UserSettingsRepository userSettingsRepository;
    @Mock private BlockRepository blockRepository;
    @Mock private NotificationMapper notificationMapper;
    @Mock private OutboxService outboxService;
    @Mock private UserSummaryService userSummaryService;

    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service =
                new NotificationServiceImpl(
                        notificationRepository,
                        userSettingsRepository,
                        blockRepository,
                        notificationMapper,
                        outboxService,
                        userSummaryService);
        lenient().when(userSettingsRepository.findById(any())).thenReturn(Optional.empty());
        lenient().when(blockRepository.existsBetween(any(), any())).thenReturn(false);
        lenient().when(userSummaryService.loadSummaries(any())).thenReturn(Map.of());
        // The live-push path reads the persisted row's generated id immediately after save(); a
        // freshly built (unsaved) entity has none, so every create()-path test needs a saved
        // return value with an id, exactly as JPA would assign one on a real insert.
        lenient()
                .when(notificationRepository.save(any()))
                .thenReturn(Notification.builder().id(UUID.randomUUID()).build());
    }

    @Test
    void create_selfNotification_skips() {
        UUID userId = UUID.randomUUID();

        service.create(userId, userId, NotificationType.LIKE_POST, "post", UUID.randomUUID(), null);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void create_notifyFollowsDisabled_skips() {
        UUID actorId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UserSettings settings =
                UserSettings.builder().userId(recipientId).notifyFollows(false).build();
        when(userSettingsRepository.findById(recipientId)).thenReturn(Optional.of(settings));

        service.create(actorId, recipientId, NotificationType.FOLLOW, null, null, null);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void create_notifyFollowRequestsDisabled_skips() {
        UUID actorId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UserSettings settings =
                UserSettings.builder().userId(recipientId).notifyFollows(false).build();
        when(userSettingsRepository.findById(recipientId)).thenReturn(Optional.of(settings));

        service.create(actorId, recipientId, NotificationType.FOLLOW_REQUEST, null, null, null);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void create_actorBlockedByRecipient_skips() {
        UUID actorId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        when(blockRepository.existsBetween(actorId, recipientId)).thenReturn(true);

        service.create(
                actorId, recipientId, NotificationType.LIKE_POST, "post", UUID.randomUUID(), null);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void create_allGuardsPass_savesNotification() {
        UUID actorId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();

        service.create(actorId, recipientId, NotificationType.LIKE_POST, "post", entityId, null);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(1)).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getActorId()).isEqualTo(actorId);
        assertThat(saved.getRecipientId()).isEqualTo(recipientId);
        assertThat(saved.getType()).isEqualTo(NotificationType.LIKE_POST);
    }

    @Test
    void create_postIdProvided_savedOnNotification() {
        UUID actorId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();

        service.create(
                actorId, recipientId, NotificationType.COMMENT_POST, "comment", entityId, postId);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getPostId()).isEqualTo(postId);
    }

    @Test
    void create_allGuardsPass_enqueuesOutboxEvent() {
        UUID actorId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();

        service.create(actorId, recipientId, NotificationType.LIKE_POST, "post", entityId, null);

        verify(outboxService)
                .enqueue(
                        eq(NotificationEventTypes.NOTIFICATION_CREATED_V1),
                        eq(NotificationEventTypes.NOTIFICATION_CREATED_V1),
                        eq("notification"),
                        any(UUID.class),
                        eq(actorId),
                        argThat(data -> recipientId.toString().equals(data.get("recipientId"))));
    }

    @Test
    void create_selfNotification_doesNotEnqueueOutboxEvent() {
        UUID userId = UUID.randomUUID();

        service.create(userId, userId, NotificationType.LIKE_POST, "post", UUID.randomUUID(), null);

        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), any());
    }

    @Test
    void create_noUserSettings_guardPassesThrough() {
        UUID actorId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        when(userSettingsRepository.findById(recipientId)).thenReturn(Optional.empty());

        service.create(actorId, recipientId, NotificationType.FOLLOW, null, null, null);

        verify(notificationRepository, times(1)).save(any());
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
    void create_notifyLikesDisabled_skips() {
        UUID actorId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UserSettings settings =
                UserSettings.builder().userId(recipientId).notifyLikes(false).build();
        when(userSettingsRepository.findById(recipientId)).thenReturn(Optional.of(settings));

        service.create(
                actorId, recipientId, NotificationType.LIKE_POST, "post", UUID.randomUUID(), null);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void create_notifyCommentsDisabled_skips() {
        UUID actorId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UserSettings settings =
                UserSettings.builder().userId(recipientId).notifyComments(false).build();
        when(userSettingsRepository.findById(recipientId)).thenReturn(Optional.of(settings));

        service.create(
                actorId,
                recipientId,
                NotificationType.COMMENT_POST,
                "post",
                UUID.randomUUID(),
                null);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void create_storyViewType_isNeverPreferenceSuppressed() {
        UUID actorId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UserSettings settings =
                UserSettings.builder()
                        .userId(recipientId)
                        .notifyFollows(false)
                        .notifyLikes(false)
                        .notifyComments(false)
                        .notifyMentions(false)
                        .notifyMessages(false)
                        .build();
        when(userSettingsRepository.findById(recipientId)).thenReturn(Optional.of(settings));

        service.create(
                actorId,
                recipientId,
                NotificationType.STORY_VIEW,
                "story",
                UUID.randomUUID(),
                null);

        verify(notificationRepository, times(1)).save(any());
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
