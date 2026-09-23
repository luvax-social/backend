package com.app.modules.notification.live;

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
import com.app.modules.notification.service.NotificationService;

/**
 * Pushes live notification events received on this instance's fanout queue to local STOMP
 * subscribers, mirroring {@code CommentLiveFanoutConsumer}.
 *
 * <p>Delivery is best-effort: the SimpleBroker routes each message to the sessions subscribed to
 * the recipient's destination on this instance. Failures are logged and dropped; the REST list
 * stays authoritative and a missed push is recovered on the next {@code GET /notifications}.
 *
 * <p>Reads the notification row back at push time rather than carrying it in the outbox payload:
 * {@code created_at} is database-defaulted and still null on the entity at the point {@code
 * NotificationServiceImpl.create} enqueues the event, and the outbox payload contract is
 * identifiers only, not the full response shape.
 *
 * <p>Stealth block enforcement: unlike the comment fan-out, a notification has exactly one
 * subscriber (the recipient), so a single {@code existsBetween} check before publishing is
 * sufficient - no per-session interceptor is needed. Most notification types cannot be generated
 * across a block in the first place because the actor could never see the recipient's content to
 * act on it; the check here is a backstop for the one path that can, an unrelated-post mention.
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
            Object recipientIdValue = event.data().get("recipientId");
            if (recipientIdValue == null) {
                return;
            }
            UUID recipientId = UUID.fromString(recipientIdValue.toString());
            notificationService
                    .findItem(recipientId, event.aggregateId())
                    .ifPresent(
                            item ->
                                    messagingTemplate.convertAndSend(
                                            "/topic/notifications." + recipientId, item));
        } catch (RuntimeException ex) {
            log.warn("Failed to push live notification event: {}", ex.getMessage());
        }
    }
}
