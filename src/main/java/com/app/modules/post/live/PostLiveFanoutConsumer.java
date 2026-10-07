package com.app.modules.post.live;

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
import com.app.modules.post.repository.PostRepository;
import com.app.modules.social.repository.BlockRepository;

/**
 * Pushes live post events received on this instance's fanout queue to local STOMP subscribers.
 *
 * <p>Delivery is best-effort: the SimpleBroker routes each message to the sessions subscribed to
 * the post's destination on this instance. Failures are logged and dropped; client reconnect plus
 * REST resync recovers any missed events, which is why the frame carries an absolute count rather
 * than a delta - a dropped or reordered delta is unrecoverable, an absolute count self-heals on the
 * next event.
 *
 * <p>The count is read here, at push time, rather than captured when the event was enqueued. The
 * outbox publisher runs after the liking transaction commits, so a captured count would already be
 * stale on arrival.
 *
 * <p>Stealth block enforcement: the event's {@code postOwnerId} is resolved to its full
 * block-counterparty set in one query, attached to the outbound message as {@link
 * PostLiveBlockFilterInterceptor#BLOCKED_COUNTERPARTIES_HEADER}, and dropped per recipient session
 * by that interceptor on {@code clientOutboundChannel}.
 */
@Component
@ConditionalOnProperty(prefix = "app.post.live", name = "enabled", havingValue = "true")
public class PostLiveFanoutConsumer {

    private static final Logger log = LoggerFactory.getLogger(PostLiveFanoutConsumer.class);

    private final DomainEventMessageParser parser;
    private final SimpMessagingTemplate messagingTemplate;
    private final PostLiveServerQueueInitializer serverQueueInitializer;
    private final PostRepository postRepository;
    private final BlockRepository blockRepository;

    public PostLiveFanoutConsumer(
            DomainEventMessageParser parser,
            SimpMessagingTemplate messagingTemplate,
            PostLiveServerQueueInitializer serverQueueInitializer,
            PostRepository postRepository,
            BlockRepository blockRepository) {
        this.parser = parser;
        this.messagingTemplate = messagingTemplate;
        this.serverQueueInitializer = serverQueueInitializer;
        this.postRepository = postRepository;
        this.blockRepository = blockRepository;
    }

    @RabbitListener(queues = "#{postLiveServerQueueInitializer.queueName}", ackMode = "NONE")
    public void consume(Message message) {
        MDC.put("serverId", serverQueueInitializer.getServerId());
        try {
            DomainEventEnvelope event = parser.parse(message);
            MDC.put("eventId", String.valueOf(event.eventId()));
            Object rawPostId = event.data().get("postId");
            if (rawPostId == null) {
                return;
            }
            UUID postId = UUID.fromString(rawPostId.toString());
            MDC.put("postId", postId.toString());

            Map<String, Object> data = new HashMap<>();
            data.put("postId", postId.toString());
            data.put("likeCount", postRepository.findLikeCount(postId));
            Map<String, Object> payload = new HashMap<>();
            payload.put("eventType", event.eventType());
            payload.put("data", data);

            // A plain Map passed as convertAndSend's headers argument is treated as intended for
            // the outgoing STOMP frame and stringified into nativeHeaders. Building the
            // MessageHeaders via SimpMessageHeaderAccessor.setHeader instead keeps the Set<UUID>
            // as a live Java object, retrievable as-is by the outbound interceptor.
            SimpMessageHeaderAccessor accessor =
                    SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            accessor.setHeader(
                    PostLiveBlockFilterInterceptor.BLOCKED_COUNTERPARTIES_HEADER,
                    resolveBlockedCounterparties(event));
            accessor.setLeaveMutable(true);
            messagingTemplate.convertAndSend(
                    "/topic/posts." + postId + ".events", payload, accessor.getMessageHeaders());
        } catch (RuntimeException ex) {
            log.warn("Failed to push live post event: {}", ex.getMessage());
        } finally {
            MDC.remove("eventId");
            MDC.remove("postId");
            MDC.remove("serverId");
        }
    }

    private Set<UUID> resolveBlockedCounterparties(DomainEventEnvelope event) {
        Object ownerId = event.data().get("postOwnerId");
        if (ownerId == null) {
            return Set.of();
        }
        return new HashSet<>(
                blockRepository.findBlockedCounterpartyIds(UUID.fromString(ownerId.toString())));
    }
}
