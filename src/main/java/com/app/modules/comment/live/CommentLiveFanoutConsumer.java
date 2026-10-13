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
import com.app.modules.comment.messaging.CommentEventTypes;
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
    private static final String VISIBILITY_HIDDEN = "hidden";
    private static final String VISIBILITY_VISIBLE = "visible";

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
            LiveFrame frame = frameFor(event);
            if (frame == null) {
                return;
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("eventType", frame.eventType());
            payload.put("data", frame.data());
            // A plain Map passed as convertAndSend's headers argument is treated as intended for
            // the outgoing STOMP frame and stringified into nativeHeaders. Building the
            // MessageHeaders via SimpMessageHeaderAccessor.setHeader instead keeps the Set<UUID>
            // as a live Java object, retrievable as-is by the outbound interceptor.
            SimpMessageHeaderAccessor accessor =
                    SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            accessor.setHeader(
                    CommentLiveBlockFilterInterceptor.BLOCKED_COUNTERPARTIES_HEADER,
                    resolveDroppedViewers(event, frame));
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

    // A hidden comment belongs to its author alone, so this decides what viewers are told about it.
    // Returns null when no frame may be sent at all.
    private static LiveFrame frameFor(DomainEventEnvelope event) {
        Map<String, Object> data = event.data();
        boolean hidden = VISIBILITY_HIDDEN.equals(data.get("visibility"));
        switch (event.eventType()) {
            case CommentEventTypes.COMMENT_DELETED_V1 -> {
                // The delete event carries the full subtree count for the deleter's own response.
                // The frame carries the visible count only, so viewers cannot learn how many
                // hidden comments were removed. An event without the field comes from before it
                // existed; reporting zero is the choice that cannot leak a hidden count.
                Object visibleCount = data.get("visibleDeletedCount");
                if (!(visibleCount instanceof Number count) || count.intValue() == 0) {
                    // No viewer held anything this delete removed, so the frame must not say who
                    // wrote the comment or where it sat.
                    return new LiveFrame(event.eventType(), noViewerRemoval(data), false);
                }
                Map<String, Object> frameData = new HashMap<>(data);
                frameData.remove("visibleDeletedCount");
                frameData.put("deletedCommentCount", count);
                return new LiveFrame(event.eventType(), frameData, false);
            }
            case CommentEventTypes.COMMENT_EDITED_V1 -> {
                if (!hidden) {
                    return new LiveFrame(event.eventType(), data, false);
                }
                // An edit can newly hide a comment viewers already hold. They get a removal in the
                // deleted frame's shape for that one comment and never the text; the author is
                // excluded because their own view must not change. A comment hidden before the
                // edit was never on a viewer's screen, so it removes nothing. An event without
                // previousVisibility predates the field, and claiming a removal there could name
                // a comment no viewer held, so only an explicit "visible" counts as one.
                if (!VISIBILITY_VISIBLE.equals(data.get("previousVisibility"))) {
                    return new LiveFrame(
                            CommentEventTypes.COMMENT_DELETED_V1, noViewerRemoval(data), true);
                }
                Map<String, Object> removal = new HashMap<>();
                removal.put("postId", data.get("postId"));
                removal.put("commentId", data.get("commentId"));
                removal.put("commentOwnerId", data.get("commentOwnerId"));
                if (data.get("rootId") != null) {
                    removal.put("rootId", data.get("rootId"));
                }
                removal.put("deletedCommentCount", 1);
                return new LiveFrame(CommentEventTypes.COMMENT_DELETED_V1, removal, true);
            }
            case CommentEventTypes.COMMENT_UNHIDDEN_V1 -> {
                // A released comment is new to every viewer, so it goes out whole, exactly as a
                // created comment does and under the same block filter on its author. Its payload
                // carries no visibility marker: nothing releases a comment that is still hidden.
                return new LiveFrame(event.eventType(), data, false);
            }
            default -> {
                return hidden ? null : new LiveFrame(event.eventType(), data, false);
            }
        }
    }

    // The only fields a removal of something nobody saw may carry: the post it belongs to and an
    // opaque id. Author, thread and parent would each identify the hidden comment's author.
    private static Map<String, Object> noViewerRemoval(Map<String, Object> data) {
        Map<String, Object> removal = new HashMap<>();
        removal.put("postId", data.get("postId"));
        removal.put("commentId", data.get("commentId"));
        removal.put("deletedCommentCount", 0);
        return removal;
    }

    private Set<UUID> resolveDroppedViewers(DomainEventEnvelope event, LiveFrame frame) {
        Object ownerId = event.data().get("commentOwnerId");
        if (ownerId == null) {
            return Set.of();
        }
        UUID owner = UUID.fromString(ownerId.toString());
        Set<UUID> dropped = new HashSet<>(blockRepository.findBlockedCounterpartyIds(owner));
        if (frame.excludesOwner()) {
            dropped.add(owner);
        }
        return dropped;
    }

    private record LiveFrame(String eventType, Map<String, Object> data, boolean excludesOwner) {}
}
