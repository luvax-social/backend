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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
import com.app.modules.notification.dto.response.NotificationResponse;
import com.app.modules.notification.entity.Notification;
import com.app.modules.notification.entity.enums.NotificationCategory;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.mapper.NotificationMapper;
import com.app.modules.notification.messaging.NotificationEventTypes;
import com.app.modules.notification.repository.NotificationAggregationRepository;
import com.app.modules.notification.repository.NotificationAggregationRepository.GroupWrite;
import com.app.modules.notification.repository.NotificationAggregationRepository.Removal;
import com.app.modules.notification.repository.NotificationRepository;
import com.app.modules.notification.service.NotificationDraft;
import com.app.modules.notification.service.NotificationService;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.dto.response.NotificationPreferencesResponse;
import com.app.modules.users.service.UserNotificationPreferencesService;
import com.app.modules.users.service.UserSummaryService;

@Service
public class NotificationServiceImpl implements NotificationService {

    private static final String AGGREGATE_TYPE = "notification";

    private final NotificationRepository notificationRepository;
    private final NotificationAggregationRepository aggregationRepository;
    private final NotificationTypePolicy typePolicy;
    private final NotificationProperties properties;
    private final NotificationMapper notificationMapper;
    private final OutboxService outboxService;
    private final UserSummaryService userSummaryService;
    private final UserNotificationPreferencesService preferencesService;
    private final SocialService socialService;
    private final TransactionTemplate transactionTemplate;

    public NotificationServiceImpl(
            NotificationRepository notificationRepository,
            NotificationAggregationRepository aggregationRepository,
            NotificationTypePolicy typePolicy,
            NotificationProperties properties,
            NotificationMapper notificationMapper,
            OutboxService outboxService,
            UserSummaryService userSummaryService,
            UserNotificationPreferencesService preferencesService,
            SocialService socialService,
            PlatformTransactionManager transactionManager) {
        this.notificationRepository = notificationRepository;
        this.aggregationRepository = aggregationRepository;
        this.typePolicy = typePolicy;
        this.properties = properties;
        this.notificationMapper = notificationMapper;
        this.outboxService = outboxService;
        this.userSummaryService = userSummaryService;
        this.preferencesService = preferencesService;
        this.socialService = socialService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // REQUIRES_NEW so each verified-resync batch commits on its own even when the caller (the
        // consumer's inbox transaction) is already transactional; joining it would turn the bounded
        // batches back into one long rewrite. The rewrite is idempotent, so a batch committed
        // before a later failure needs no rollback.
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional
    public boolean create(
            UUID actorId,
            UUID recipientId,
            NotificationType type,
            String entityType,
            UUID entityId,
            UUID postId) {
        return create(
                NotificationDraft.of(actorId, recipientId, type, entityType, entityId, postId));
    }

    @Override
    @Transactional
    public boolean create(NotificationDraft draft) {
        UUID actorId = draft.actorId();
        UUID recipientId = draft.recipientId();
        NotificationType type = draft.type();
        if (actorId != null && actorId.equals(recipientId)) {
            return false;
        }
        if (!typePolicy.isEnabled(type) || !recipientAllows(recipientId, type)) {
            return false;
        }
        if (actorId != null && socialService.isBlockedBetween(actorId, recipientId)) {
            return false;
        }

        boolean actorVerified = actorId != null && isVerified(actorId);
        NotificationCategory category = type.category();
        UUID notificationId;
        if (type.isAggregatable() && actorId != null) {
            GroupWrite write =
                    aggregationRepository.upsertGroup(
                            draft,
                            category,
                            type.aggregationKey(draft.entityId()),
                            properties.aggregationWindow(),
                            actorVerified);
            if (!write.joined()) {
                return false;
            }
            notificationId = write.id();
        } else {
            notificationId = aggregationRepository.insertSingle(draft, category, actorVerified);
            if (actorId != null) {
                aggregationRepository.addMember(notificationId, actorId);
            }
        }
        enqueue(
                NotificationEventTypes.NOTIFICATION_UPSERTED_V1,
                notificationId,
                actorId,
                recipientData(recipientId));
        // Last statement before commit, after the outbox insert, so the row's feed position is the
        // last clock read of the transaction; see DATA_RULES on the watermark race.
        aggregationRepository.touchActivity(notificationId);
        return true;
    }

    @Override
    @Transactional
    public void retract(UUID actorId, UUID recipientId, NotificationType type, UUID targetId) {
        List<Removal> removals =
                switch (type) {
                    case LIKE_POST, LIKE_COMMENT, STORY_VIEW ->
                            aggregationRepository.removeMemberFromGroups(
                                    recipientId, actorId, type.aggregationKey(targetId));
                    case FOLLOW, FOLLOW_REQUEST ->
                            aggregationRepository.removeMemberFromFollowRows(recipientId, actorId);
                    default ->
                            throw new IllegalArgumentException(
                                    "Notification type " + type + " cannot be retracted");
                };
        publishRemovals(recipientId, actorId, removals);
    }

    @Override
    @Transactional
    public void resolveFollowRequest(UUID requesterId, UUID approverId, boolean approved) {
        if (!approved) {
            retract(requesterId, approverId, NotificationType.FOLLOW_REQUEST, null);
            return;
        }
        aggregationRepository
                .findLiveFollowRequest(approverId, requesterId)
                .filter(aggregationRepository::convertRequestToFollow)
                .ifPresent(
                        id ->
                                enqueue(
                                        NotificationEventTypes.NOTIFICATION_UPSERTED_V1,
                                        id,
                                        requesterId,
                                        recipientData(approverId)));
    }

    @Override
    @Transactional
    public void onBlock(UUID blockerId, UUID blockedId) {
        publishRemovals(
                blockerId,
                blockedId,
                aggregationRepository.removeMemberOnBlock(blockerId, blockedId));
        publishRemovals(
                blockedId,
                blockerId,
                aggregationRepository.removeMemberOnBlock(blockedId, blockerId));
    }

    @Override
    public int resyncActorVerified(UUID actorId) {
        boolean verified = isVerified(actorId);
        int batchSize = properties.verifiedResyncBatchSize();
        int total = 0;
        int rewritten;
        // One transaction per batch, deliberately not one for the whole rewrite: each commit
        // releases the row locks it took, so a prolific account never blocks writes to its own
        // notifications for the length of the full rewrite.
        do {
            Integer batch =
                    transactionTemplate.execute(
                            status ->
                                    aggregationRepository.resyncActorVerified(
                                            actorId, verified, batchSize));
            rewritten = batch == null ? 0 : batch;
            total += rewritten;
        } while (rewritten == batchSize);
        return total;
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

    private void publishRemovals(UUID recipientId, UUID removedActorId, List<Removal> removals) {
        for (Removal removal : removals) {
            if (removal.emptied()) {
                Map<String, Object> data = recipientData(recipientId);
                data.put("ids", List.of(removal.id().toString()));
                enqueue(
                        NotificationEventTypes.NOTIFICATION_DELETED_V1,
                        removal.id(),
                        removedActorId,
                        data);
                continue;
            }
            if (removal.actorId() != null) {
                aggregationRepository.setActorVerified(removal.id(), isVerified(removal.actorId()));
            }
            enqueue(
                    NotificationEventTypes.NOTIFICATION_UPSERTED_V1,
                    removal.id(),
                    removedActorId,
                    recipientData(recipientId));
        }
    }

    private boolean recipientAllows(UUID recipientId, NotificationType type) {
        return switch (type) {
            case FOLLOW, FOLLOW_REQUEST -> preferences(recipientId).follows();
            case LIKE_POST, LIKE_COMMENT -> preferences(recipientId).likes();
            case COMMENT_POST, REPLY_COMMENT -> preferences(recipientId).comments();
            case MENTION_POST, MENTION_COMMENT -> preferences(recipientId).mentions();
            case MESSAGE -> preferences(recipientId).messages();
            // STORY_VIEW has no user_settings toggle; it is never preference-suppressed.
            case STORY_VIEW -> true;
            // WARNING has no toggle either, and deliberately never gets one. An account that could
            // switch off moderation warnings would be disciplined without being told, and the
            // strike that follows three of them would arrive unexplained.
            // SUPPORT_TICKET_UPDATE joins them for the same reason: an account that could switch
            // it off would ask a question and never be told it had been answered.
            // The content-removal and report outcome types join them on the same ground: each is
            // an enforcement notice carrying the appeal route for the decision behind it.
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

    private NotificationPreferencesResponse preferences(UUID recipientId) {
        return preferencesService.findNotificationPreferences(recipientId);
    }

    private boolean isVerified(UUID userId) {
        UserSummaryResponse summary = userSummaryService.loadSummaries(List.of(userId)).get(userId);
        return summary != null && summary.isVerified();
    }

    private static Map<String, Object> recipientData(UUID recipientId) {
        Map<String, Object> data = new HashMap<>();
        data.put("recipientId", recipientId.toString());
        return data;
    }

    private void enqueue(
            String eventType, UUID aggregateId, UUID actorId, Map<String, Object> data) {
        outboxService.enqueue(eventType, eventType, AGGREGATE_TYPE, aggregateId, actorId, data);
    }

    private String encodeCursor(Notification notification) {
        return CursorCodec.encode(
                new Cursor(TimeCursors.toMicros(notification.getCreatedAt()), notification.getId()),
                CursorScope.NOTIFICATIONS);
    }

    private Cursor decodeCursor(String cursor) {
        return CursorCodec.decode(cursor, CursorScope.NOTIFICATIONS);
    }
}
