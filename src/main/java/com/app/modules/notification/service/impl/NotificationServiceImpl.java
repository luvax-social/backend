package com.app.modules.notification.service.impl;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.common.pagination.Cursor;
import com.app.common.pagination.CursorCodec;
import com.app.common.pagination.CursorScope;
import com.app.common.pagination.TimeCursors;
import com.app.common.response.CursorPageResponse;
import com.app.common.response.UserSummaryResponse;
import com.app.modules.notification.dto.response.NotificationResponse;
import com.app.modules.notification.entity.Notification;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.mapper.NotificationMapper;
import com.app.modules.notification.messaging.NotificationEventTypes;
import com.app.modules.notification.repository.NotificationRepository;
import com.app.modules.notification.service.NotificationService;
import com.app.modules.social.repository.BlockRepository;
import com.app.modules.users.entity.UserSettings;
import com.app.modules.users.repository.UserSettingsRepository;
import com.app.modules.users.service.UserSummaryService;

@Service
public class NotificationServiceImpl implements NotificationService {

    private static final String AGGREGATE_TYPE = "notification";

    private final NotificationRepository notificationRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final BlockRepository blockRepository;
    private final NotificationMapper notificationMapper;
    private final OutboxService outboxService;
    private final UserSummaryService userSummaryService;

    public NotificationServiceImpl(
            NotificationRepository notificationRepository,
            UserSettingsRepository userSettingsRepository,
            BlockRepository blockRepository,
            NotificationMapper notificationMapper,
            OutboxService outboxService,
            UserSummaryService userSummaryService) {
        this.notificationRepository = notificationRepository;
        this.userSettingsRepository = userSettingsRepository;
        this.blockRepository = blockRepository;
        this.notificationMapper = notificationMapper;
        this.outboxService = outboxService;
        this.userSummaryService = userSummaryService;
    }

    @Override
    @Transactional
    public void create(
            UUID actorId,
            UUID recipientId,
            NotificationType type,
            String entityType,
            UUID entityId,
            UUID postId) {
        create(actorId, recipientId, type, entityType, entityId, postId, null);
    }

    @Override
    @Transactional
    public void create(
            UUID actorId,
            UUID recipientId,
            NotificationType type,
            String entityType,
            UUID entityId,
            UUID postId,
            String message) {
        if (actorId != null && actorId.equals(recipientId)) {
            return;
        }

        UserSettings settings = userSettingsRepository.findById(recipientId).orElse(null);
        if (settings != null && !isTypeEnabled(type, settings)) {
            return;
        }

        if (actorId != null && blockRepository.existsBetween(actorId, recipientId)) {
            return;
        }

        Notification notification =
                Notification.builder()
                        .recipientId(recipientId)
                        .actorId(actorId)
                        .type(type)
                        .category(type.category())
                        .entityType(entityType)
                        .entityId(entityId)
                        .postId(postId)
                        .message(message)
                        .build();
        Notification saved = notificationRepository.save(notification);

        // The live tier reads the row back at push time to get createdAt (database-defaulted, so
        // still null on `saved` here) and the full response shape; the outbox payload therefore
        // carries only the identifiers a listener needs to find it, not the entity itself.
        Map<String, Object> data = new HashMap<>();
        data.put("recipientId", recipientId.toString());
        outboxService.enqueue(
                NotificationEventTypes.NOTIFICATION_CREATED_V1,
                NotificationEventTypes.NOTIFICATION_CREATED_V1,
                AGGREGATE_TYPE,
                saved.getId(),
                actorId,
                data);
    }

    @Override
    @Transactional
    public void markAsRead(UUID notificationId, UUID recipientId) {
        Notification notification =
                notificationRepository
                        .findByIdAndRecipientId(notificationId, recipientId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.FORBIDDEN));
        if (notification.getReadAt() == null) {
            notification.setReadAt(OffsetDateTime.now(ZoneOffset.UTC));
            notificationRepository.save(notification);
        }
    }

    @Override
    @Transactional
    public void markAllAsRead(UUID recipientId) {
        notificationRepository.markAllAsRead(recipientId, OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Override
    @Transactional(readOnly = true)
    public long getUnreadCount(UUID recipientId) {
        return notificationRepository.countByRecipientIdAndIsReadFalse(recipientId);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<NotificationResponse> listNotifications(
            UUID recipientId, String cursor, int limit) {
        Cursor decoded = decodeCursor(cursor);
        List<Notification> rows =
                decoded == null
                        ? notificationRepository.findFirstByRecipient(
                                recipientId, PageRequest.of(0, limit + 1))
                        : notificationRepository.findByRecipientBefore(
                                recipientId,
                                TimeCursors.fromMicros(decoded.sortValueMicros()),
                                decoded.id(),
                                PageRequest.of(0, limit + 1));
        boolean hasNextPage = rows.size() > limit;
        if (hasNextPage) {
            rows = rows.subList(0, limit);
        }
        // One batched actor lookup for the whole page instead of one profile fetch per row.
        Map<UUID, UserSummaryResponse> actors =
                userSummaryService.loadSummaries(
                        rows.stream()
                                .map(Notification::getActorId)
                                .filter(Objects::nonNull)
                                .toList());
        List<NotificationResponse> content =
                rows.stream()
                        .map(
                                n ->
                                        notificationMapper.toResponse(
                                                n,
                                                n.getActorId() == null
                                                        ? null
                                                        : actors.get(n.getActorId())))
                        .toList();
        Notification first = rows.isEmpty() ? null : rows.get(0);
        Notification last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
        String startCursor = first == null ? null : encodeCursor(first);
        String endCursor = last == null ? null : encodeCursor(last);
        return CursorPageResponse.of(content, hasNextPage, startCursor, endCursor, cursor != null);
    }

    private String encodeCursor(Notification notification) {
        return CursorCodec.encode(
                new Cursor(TimeCursors.toMicros(notification.getCreatedAt()), notification.getId()),
                CursorScope.NOTIFICATIONS);
    }

    private Cursor decodeCursor(String cursor) {
        return CursorCodec.decode(cursor, CursorScope.NOTIFICATIONS);
    }

    private boolean isTypeEnabled(NotificationType type, UserSettings settings) {
        return switch (type) {
            case FOLLOW, FOLLOW_REQUEST -> settings.isNotifyFollows();
            case LIKE_POST, LIKE_COMMENT -> settings.isNotifyLikes();
            case COMMENT_POST, REPLY_COMMENT -> settings.isNotifyComments();
            case MENTION_POST, MENTION_COMMENT -> settings.isNotifyMentions();
            case MESSAGE -> settings.isNotifyMessages();
            // STORY_VIEW has no user_settings toggle; it is never preference-suppressed.
            case STORY_VIEW -> true;
            // WARNING has no toggle either, and deliberately never gets one. An account that could
            // switch off moderation warnings would be disciplined without being told, and the
            // strike that follows three of them would arrive unexplained.
            // SUPPORT_TICKET_UPDATE joins them for the same reason: an account that could switch
            // it off would ask a question and never be told it had been answered.
            // The three content-removal types join them on the same ground: each is an
            // enforcement notice carrying the appeal link for the decision behind it, so an
            // account that could switch them off would lose its only in-product route to
            // contest a removal.
            case WARNING,
                            POST_REMOVED,
                            COMMENT_REMOVED,
                            STORY_REMOVED,
                            MESSAGE_REMOVED,
                            REPORT_POST_REMOVED,
                            POST_RESTORED,
                            REPORT_DISMISSED,
                            SUPPORT_TICKET_UPDATE ->
                    true;
        };
    }
}
