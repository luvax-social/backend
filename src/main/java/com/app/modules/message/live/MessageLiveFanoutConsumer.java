package com.app.modules.message.live;

import java.util.HashMap;
import java.util.Map;
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
import com.app.modules.message.messaging.MessageEventTypes;

/**
 * Pushes live message events received on this instance's fanout queue to local STOMP subscribers.
 *
 * <p>Delivery is best-effort: the SimpleBroker routes each message to the sessions subscribed to
 * the conversation's destination on this instance. Failures are logged and dropped; client
 * reconnect plus REST resync (message history) recovers any missed events.
 */
@Component
@ConditionalOnProperty(prefix = "app.message.live", name = "enabled", havingValue = "true")
public class MessageLiveFanoutConsumer {

    private static final Logger log = LoggerFactory.getLogger(MessageLiveFanoutConsumer.class);

    private final DomainEventMessageParser parser;
    private final SimpMessagingTemplate messagingTemplate;
    private final MessageLiveServerQueueInitializer serverQueueInitializer;

    public MessageLiveFanoutConsumer(
            DomainEventMessageParser parser,
            SimpMessagingTemplate messagingTemplate,
            MessageLiveServerQueueInitializer serverQueueInitializer) {
        this.parser = parser;
        this.messagingTemplate = messagingTemplate;
        this.serverQueueInitializer = serverQueueInitializer;
    }

    @RabbitListener(queues = "#{messageLiveServerQueueInitializer.queueName}", ackMode = "NONE")
    public void consume(Message message) {
        try {
            DomainEventEnvelope event = parser.parse(message);
            // A decision about a request is analytics only. The requester is subscribed to this
            // thread, so pushing it would tell them the request was declined.
            if (MessageEventTypes.MESSAGE_REQUEST_RESOLVED_V1.equals(event.eventType())) {
                return;
            }
            Object conversationId = event.data().get("conversationId");
            if (conversationId == null) {
                return;
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("eventType", event.eventType());
            payload.put("data", event.data());
            messagingTemplate.convertAndSend(
                    "/topic/conversations."
                            + UUID.fromString(conversationId.toString())
                            + ".messages",
                    (Object) payload);
        } catch (RuntimeException ex) {
            log.warn(
                    "Failed to push live message event on server {}: {}",
                    serverQueueInitializer.getServerId(),
                    ex.getMessage());
        }
    }
}
