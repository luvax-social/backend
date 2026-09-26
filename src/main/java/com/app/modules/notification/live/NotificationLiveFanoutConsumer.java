package com.app.modules.notification.live;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.app.common.messaging.DomainEventMessageParser;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.modules.notification.dto.response.NotificationItemResponse;
import com.app.modules.notification.dto.response.NotificationKeyResponse;
import com.app.modules.notification.dto.response.NotificationLiveEnvelope;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.messaging.NotificationEventTypes;
import com.app.modules.notification.service.NotificationService;

/**
 * Turns notification outbox events received on this instance's fanout queue into typed {@link
 * NotificationLiveEnvelope}s for the recipient's STOMP topic, mirroring {@code
 * CommentLiveFanoutConsumer}.
 *
 * <p>Delivery is best effort: the SimpleBroker routes each envelope to the sessions subscribed to
 * the recipient's destination on this instance. Failures are logged and dropped; the REST feed
 * stays authoritative and a client refetches after a reconnect.
 *
 * <p>An upserted row is hydrated at push time through {@link NotificationService#findItem}, the
 * same pipeline a page uses, so a push can never show more than a page would: a row hidden by a
 * block or an account's status, or deleted meanwhile, sends nothing. Every envelope carries the
 * state after the event, recomputed with one bounded count, so the badge is replaced from the push
 * and never adjusted client-side.
 */
@Component
@ConditionalOnProperty(prefix = "app.notification.live", name = "enabled", havingValue = "true")
public class NotificationLiveFanoutConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationLiveFanoutConsumer.class);

    private final DomainEventMessageParser parser;
    private final NotificationService notificationService;
    private final SimpMessagingTemplate messagingTemplate;

    public NotificationLiveFanoutConsumer(
            DomainEventMessageParser parser,
            NotificationService notificationService,
            SimpMessagingTemplate messagingTemplate) {
        this.parser = parser;
        this.notificationService = notificationService;
        this.messagingTemplate = messagingTemplate;
    }

    @RabbitListener(
            queues = "#{notificationLiveServerQueueInitializer.queueName}",
            ackMode = "NONE")
    public void consume(Message message) {
        try {
            DomainEventEnvelope event = parser.parse(message);
            Map<String, Object> data = event.data() == null ? Map.of() : event.data();
            Object recipientValue = data.get("recipientId");
            if (recipientValue == null) {
                return;
            }
            UUID recipientId = UUID.fromString(recipientValue.toString());
            toEnvelope(event, data, recipientId)
                    .ifPresent(
                            envelope ->
                                    messagingTemplate.convertAndSend(
                                            "/topic/notifications." + recipientId, envelope));
        } catch (RuntimeException ex) {
            log.warn("Failed to push live notification event: {}", ex.getMessage());
        }
    }

    private Optional<NotificationLiveEnvelope> toEnvelope(
            DomainEventEnvelope event, Map<String, Object> data, UUID recipientId) {
        return switch (event.eventType()) {
            case NotificationEventTypes.NOTIFICATION_UPSERTED_V1 ->
                    notificationService
                            .findItem(recipientId, event.aggregateId())
                            .map(item -> upserted(item, recipientId));
            case NotificationEventTypes.NOTIFICATION_READ_STATE_CHANGED_V1 ->
                    Optional.of(
                            new NotificationLiveEnvelope(
                                    NotificationLiveEnvelope.READ_STATE,
                                    null,
                                    ids(data),
                                    upTo(data),
                                    readAt(data),
                                    notificationService.getState(recipientId)));
            case NotificationEventTypes.NOTIFICATION_DELETED_V1 ->
                    Optional.of(
                            new NotificationLiveEnvelope(
                                    NotificationLiveEnvelope.DELETED,
                                    null,
                                    ids(data),
                                    null,
                                    null,
                                    notificationService.getState(recipientId)));
            case NotificationEventTypes.NOTIFICATION_SEEN_V1 ->
                    Optional.of(
                            new NotificationLiveEnvelope(
                                    NotificationLiveEnvelope.SEEN,
                                    null,
                                    null,
                                    null,
                                    null,
                                    notificationService.getState(recipientId)));
            default -> Optional.empty();
        };
    }

    // A pending follow request is never a feed row, so its arrival is a state change only: the
    // badge and the pinned entry move, and no list may insert it.
    private NotificationLiveEnvelope upserted(NotificationItemResponse item, UUID recipientId) {
        boolean request = item.type() == NotificationType.FOLLOW_REQUEST;
        return new NotificationLiveEnvelope(
                request ? NotificationLiveEnvelope.REQUESTS : NotificationLiveEnvelope.UPSERTED,
                request ? null : item,
                null,
                null,
                null,
                notificationService.getState(recipientId));
    }

    private static List<UUID> ids(Map<String, Object> data) {
        if (!(data.get("ids") instanceof List<?> values)) {
            return null;
        }
        return values.stream().map(value -> UUID.fromString(value.toString())).toList();
    }

    private static NotificationKeyResponse upTo(Map<String, Object> data) {
        if (!(data.get("upTo") instanceof Map<?, ?> bound)) {
            return null;
        }
        return new NotificationKeyResponse(
                OffsetDateTime.parse(bound.get("activityAt").toString()),
                UUID.fromString(bound.get("id").toString()));
    }

    private static OffsetDateTime readAt(Map<String, Object> data) {
        Object value = data.get("readAt");
        return value == null ? null : OffsetDateTime.parse(value.toString());
    }
}
