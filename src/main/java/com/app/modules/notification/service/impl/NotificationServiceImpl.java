package com.app.modules.notification.service.impl;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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
import com.app.modules.notification.dto.request.AdvanceSeenRequest;
import com.app.modules.notification.dto.request.NotificationPositionRequest;
import com.app.modules.notification.dto.response.FollowRequestSummaryResponse;
import com.app.modules.notification.dto.response.NotificationItemResponse;
import com.app.modules.notification.dto.response.NotificationKeyResponse;
import com.app.modules.notification.dto.response.NotificationPageResponse;
import com.app.modules.notification.dto.response.NotificationReadStateResponse;
import com.app.modules.notification.dto.response.NotificationStateResponse;
import com.app.modules.notification.dto.response.ReadAllResponse;
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
import com.app.modules.notification.service.NotificationService;
import com.app.modules.social.dto.response.PendingFollowRequestSummary;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.dto.response.NotificationPreferencesResponse;
import com.app.modules.users.service.UserNotificationPreferencesService;
import com.app.modules.users.service.UserSummaryService;

@Service
public class NotificationServiceImpl implements NotificationService {

    private static final String AGGREGATE_TYPE = "notification";
    private static final int RECENT_REQUESTERS = 2;

    private final NotificationAggregationRepository aggregationRepository;
    private final NotificationFeedRepository feedRepository;
    private final NotificationSeenStateRepository seenStateRepository;
    private final NotificationItemAssembler itemAssembler;
    private final NotificationTypePolicy typePolicy;
    private final NotificationProperties properties;
    private final OutboxService outboxService;
    private final UserSummaryService userSummaryService;
    private final UserNotificationPreferencesService preferencesService;
    private final SocialService socialService;
    private final TransactionTemplate transactionTemplate;

    public NotificationServiceImpl(
            NotificationAggregationRepository aggregationRepository,
            NotificationFeedRepository feedRepository,
            NotificationSeenStateRepository seenStateRepository,
            NotificationItemAssembler itemAssembler,
            NotificationTypePolicy typePolicy,
            NotificationProperties properties,
            OutboxService outboxService,
            UserSummaryService userSummaryService,
            UserNotificationPreferencesService preferencesService,
            SocialService socialService,
            PlatformTransactionManager transactionManager) {
        this.aggregationRepository = aggregationRepository;
        this.feedRepository = feedRepository;
        this.seenStateRepository = seenStateRepository;
        this.itemAssembler = itemAssembler;
        this.typePolicy = typePolicy;
        this.properties = properties;
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
    @Transactional(readOnly = true)
    public NotificationStateResponse getState(UUID userId) {
        return toState(userId, seenStateRepository.find(userId).orElse(SeenState.NONE));
    }

    @Override
    @Transactional
    public NotificationStateResponse advanceSeen(UUID userId, AdvanceSeenRequest request) {
        SeenState advanced =
                seenStateRepository
                        .advance(
                                userId,
                                request.activityAt(),
                                request.id(),
                                properties.seenSessionGap())
                        .orElseThrow(() -> new AppException(ApiErrorCode.FORBIDDEN));
        enqueue(NotificationEventTypes.NOTIFICATION_SEEN_V1, userId, userId, recipientData(userId));
        return toState(userId, advanced);
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationPageResponse listFeed(
            UUID userId, NotificationFilter filter, String cursor, int limit) {
        Cursor decoded = decodeCursor(cursor);
        Key before =
                decoded == null
                        ? null
                        : new Key(TimeCursors.fromMicros(decoded.sortValueMicros()), decoded.id());
        List<FeedRow> rows = feedRepository.findPage(userId, filter, before, limit + 1);
        boolean hasNextPage = rows.size() > limit;
        if (hasNextPage) {
            rows = rows.subList(0, limit);
        }
        SeenState seenState = seenStateRepository.find(userId).orElse(SeenState.NONE);
        List<NotificationItemResponse> content =
                itemAssembler.assemble(userId, rows, seenState.previous());
        CursorPageResponse.PageInfo pageInfo =
                CursorPageResponse.PageInfo.builder()
                        .hasNextPage(hasNextPage)
                        .hasPreviousPage(cursor != null)
                        .startCursor(rows.isEmpty() ? null : encodeCursor(rows.get(0)))
                        .endCursor(rows.isEmpty() ? null : encodeCursor(rows.get(rows.size() - 1)))
                        .build();
        NotificationKeyResponse head =
                cursor == null ? toKeyResponse(feedRepository.findHead(userId).orElse(null)) : null;
        return new NotificationPageResponse(content, pageInfo, false, head);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NotificationItemResponse> findItem(UUID userId, UUID notificationId) {
        return feedRepository
                .findVisible(userId, notificationId)
                .map(
                        row ->
                                itemAssembler
                                        .assemble(
                                                userId,
                                                List.of(row),
                                                seenStateRepository
                                                        .find(userId)
                                                        .orElse(SeenState.NONE)
                                                        .previous())
                                        .get(0));
    }

    private static String encodeCursor(FeedRow row) {
        return CursorCodec.encode(
                new Cursor(TimeCursors.toMicros(row.activityAt()), row.id()),
                CursorScope.NOTIFICATIONS);
    }

    private NotificationStateResponse toState(UUID userId, SeenState seenState) {
        return new NotificationStateResponse(
                UnseenCountResponse.of(feedRepository.countUnseen(userId, seenState.seen())),
                toKeyResponse(seenState.seen()),
                toKeyResponse(seenState.previous()),
                followRequests(userId));
    }

    private FollowRequestSummaryResponse followRequests(UUID userId) {
        PendingFollowRequestSummary summary =
                socialService.summarizePendingFollowRequests(
                        userId, RECENT_REQUESTERS, UnseenCountResponse.CAP + 1);
        if (summary.count() == 0) {
            return FollowRequestSummaryResponse.NONE;
        }
        Map<UUID, UserSummaryResponse> requesters =
                userSummaryService.loadSummaries(summary.recentRequesterIds());
        return new FollowRequestSummaryResponse(
                (int) Math.min(summary.count(), UnseenCountResponse.CAP),
                summary.count() > UnseenCountResponse.CAP,
                summary.recentRequesterIds().stream()
                        .map(requesters::get)
                        .filter(Objects::nonNull)
                        .toList());
    }

    static NotificationKeyResponse toKeyResponse(Key key) {
        return key == null ? null : new NotificationKeyResponse(key.activityAt(), key.id());
    }

    @Override
    @Transactional
    public NotificationReadStateResponse markRead(UUID userId, UUID notificationId) {
        Optional<OffsetDateTime> readNow = aggregationRepository.markRead(userId, notificationId);
        if (readNow.isPresent()) {
            publishReadState(userId, notificationId, readNow.get());
            return new NotificationReadStateResponse(notificationId, readNow.get());
        }
        OffsetDateTime readAt =
                aggregationRepository
                        .findLiveReadState(userId, notificationId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.FORBIDDEN))
                        .orElse(null);
        return new NotificationReadStateResponse(notificationId, readAt);
    }

    @Override
    @Transactional
    public NotificationReadStateResponse markUnread(UUID userId, UUID notificationId) {
        if (aggregationRepository.markUnread(userId, notificationId)) {
            publishReadState(userId, notificationId, null);
        } else if (aggregationRepository.findLiveReadState(userId, notificationId).isEmpty()) {
            throw new AppException(ApiErrorCode.FORBIDDEN);
        }
        return new NotificationReadStateResponse(notificationId, null);
    }

    @Override
    @Transactional
    public ReadAllResponse markReadUpTo(UUID userId, NotificationPositionRequest upTo) {
        List<UUID> changed =
                aggregationRepository.markReadUpTo(userId, upTo.activityAt(), upTo.id());
        if (!changed.isEmpty()) {
            Map<String, Object> data = recipientData(userId);
            Map<String, Object> bound = new HashMap<>();
            bound.put("activityAt", upTo.activityAt().toString());
            bound.put("id", upTo.id().toString());
            data.put("upTo", bound);
            data.put("readAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
            enqueue(
                    NotificationEventTypes.NOTIFICATION_READ_STATE_CHANGED_V1,
                    userId,
                    userId,
                    data);
        }
        return new ReadAllResponse(changed.size());
    }

    @Override
    @Transactional
    public void delete(UUID userId, UUID notificationId) {
        if (aggregationRepository.softDelete(userId, notificationId)) {
            Map<String, Object> data = recipientData(userId);
            data.put("ids", List.of(notificationId.toString()));
            enqueue(NotificationEventTypes.NOTIFICATION_DELETED_V1, notificationId, userId, data);
        } else if (!aggregationRepository.isOwnedBy(userId, notificationId)) {
            throw new AppException(ApiErrorCode.FORBIDDEN);
        }
    }

    private void publishReadState(UUID userId, UUID notificationId, OffsetDateTime readAt) {
        Map<String, Object> data = recipientData(userId);
        data.put("ids", List.of(notificationId.toString()));
        // The outbox payload holds no null values, so an unread transition is the absence of
        // readAt rather than a null one.
        if (readAt != null) {
            data.put("readAt", readAt.toString());
        }
        enqueue(
                NotificationEventTypes.NOTIFICATION_READ_STATE_CHANGED_V1,
                notificationId,
                userId,
                data);
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

    private Cursor decodeCursor(String cursor) {
        return CursorCodec.decode(cursor, CursorScope.NOTIFICATIONS);
    }
}
