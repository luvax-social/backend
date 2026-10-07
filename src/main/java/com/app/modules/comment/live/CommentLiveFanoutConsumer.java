package com.app.modules.comment.live;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.app.common.messaging.DomainEventMessageParser;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.modules.comment.observability.CommentMetrics;
import com.app.modules.social.repository.BlockRepository;

import io.micrometer.core.instrument.Timer;

/**
 * Pushes live comment events received on this instance's fanout queue to local STOMP subscribers.
 *
 * <p>Delivery is best-effort: the SimpleBroker routes each message to the sessions subscribed to
 * the post's destination on this instance. Failures are logged and dropped; client reconnect plus
 * REST resync recovers any missed events.
 *
 * <p>Stealth block enforcement: the event's {@code commentOwnerId} is resolved to its full
 * block-counterparty set in one query, attached to the outbound message as {@link
 * CommentLiveBlockFilterInterceptor#BLOCKED_COUNTERPARTIES_HEADER}, and dropped per recipient
 * session by that interceptor on {@code clientOutboundChannel}. One query per event regardless of
 * subscriber count; no viewer-keyed cache is introduced.
 */
@Component
@ConditionalOnProperty(prefix = "app.comment.live", name = "enabled", havingValue = "true")
public class CommentLiveFanoutConsumer {

    private static final Logger log = LoggerFactory.getLogger(CommentLiveFanoutConsumer.class);

    private final DomainEventMessageParser parser;
    private final SimpMessagingTemplate messagingTemplate;
    private final CommentMetrics metrics;
    private final CommentLiveServerQueueInitializer serverQueueInitializer;
    private final BlockRepository blockRepository;

    public CommentLiveFanoutConsumer(
            DomainEventMessageParser parser,
            SimpMessagingTemplate messagingTemplate,
            CommentMetrics metrics,
            CommentLiveServerQueueInitializer serverQueueInitializer,
            BlockRepository blockRepository) {
        this.parser = parser;
        this.messagingTemplate = messagingTemplate;
        this.metrics = metrics;
        this.serverQueueInitializer = serverQueueInitializer;
        this.blockRepository = blockRepository;
    }

    @RabbitListener(queues = "#{commentLiveServerQueueInitializer.queueName}", ackMode = "NONE")
    public void consume(Message message) {
        Timer.Sample sample = Timer.start();
        MDC.put("serverId", serverQueueInitializer.getServerId());
        try {
            DomainEventEnvelope event = parser.parse(message);
            MDC.put("eventId", String.valueOf(event.eventId()));
            Object postId = event.data().get("postId");
            if (postId == null) {
                return;
            }
            MDC.put("postId", postId.toString());
            Map<String, Object> payload = new HashMap<>();
            payload.put("eventType", event.eventType());
            payload.put("data", event.data());
            // A plain Map passed as convertAndSend's headers argument is treated as intended for
            // the outgoing STOMP frame and stringified into nativeHeaders. Building the
            // MessageHeaders via SimpMessageHeaderAccessor.setHeader instead keeps the Set<UUID>
            // as a live Java object, retrievable as-is by the outbound interceptor.
            SimpMessageHeaderAccessor accessor =
                    SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            accessor.setHeader(
                    CommentLiveBlockFilterInterceptor.BLOCKED_COUNTERPARTIES_HEADER,
                    resolveBlockedCounterparties(event));
            accessor.setLeaveMutable(true);
            messagingTemplate.convertAndSend(
                    "/topic/comments." + UUID.fromString(postId.toString()) + ".events",
                    payload,
                    accessor.getMessageHeaders());
        } catch (RuntimeException ex) {
            metrics.wsPushFailure();
            log.warn("Failed to push live comment event: {}", ex.getMessage());
        } finally {
            sample.stop(metrics.fanoutLatency());
            MDC.remove("eventId");
            MDC.remove("postId");
            MDC.remove("serverId");
        }
    }

    private Set<UUID> resolveBlockedCounterparties(DomainEventEnvelope event) {
        Object ownerId = event.data().get("commentOwnerId");
        if (ownerId == null) {
            return Set.of();
        }
        return new HashSet<>(
                blockRepository.findBlockedCounterpartyIds(UUID.fromString(ownerId.toString())));
    }
}
