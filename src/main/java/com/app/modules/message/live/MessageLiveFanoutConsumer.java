package com.app.modules.message.live;

import java.util.Collection;
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
 * <p>A conversation event goes to the thread topic with its full data, and, when the producer named
 * an inbox audience, a slim content-free frame goes to each recipient's {@code
 * /topic/inbox.{userId}} topic so the conversation list can refresh.
 *
 * <p>Delivery is best-effort: the SimpleBroker routes each message to the sessions subscribed to
 * the conversation's destination on this instance. Failures are logged and dropped; client
 * reconnect plus REST resync (message history) recovers any missed events.
 */
@Component
@ConditionalOnProperty(prefix = "app.message.live", name = "enabled", havingValue = "true")
public class MessageLiveFanoutConsumer {

    private static final Logger log = LoggerFactory.getLogger(MessageLiveFanoutConsumer.class);

    private static final String INBOX_RECIPIENTS_KEY = "inboxRecipientIds";
    private static final String FOLDER_KEY = "folder";

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
            UUID thread = UUID.fromString(conversationId.toString());
            // The audience and folder are for the inbox frames only. The requester is subscribed
            // to the thread, so leaving the folder in would tell them a message went to the
            // hidden requests folder.
            Map<String, Object> threadData = new HashMap<>(event.data());
            Object recipients = threadData.remove(INBOX_RECIPIENTS_KEY);
            Object folder = threadData.remove(FOLDER_KEY);
            Map<String, Object> payload = new HashMap<>();
            payload.put("eventType", event.eventType());
            payload.put("data", threadData);
            messagingTemplate.convertAndSend(
                    "/topic/conversations." + thread + ".messages", (Object) payload);
            pushToInboxes(event.eventType(), thread, recipients, folder);
        } catch (RuntimeException ex) {
            log.warn(
                    "Failed to push live message event on server {}: {}",
                    serverQueueInitializer.getServerId(),
                    ex.getMessage());
        }
    }

    // The inbox frame is a prompt to refetch the conversation list, so it carries the event type,
    // the conversation id and at most the folder, and never any message content. Each push is
    // independent: one unusable id must not stop the other recipients being told.
    private void pushToInboxes(
            String eventType, UUID conversationId, Object recipients, Object folder) {
        if (!(recipients instanceof Collection<?> ids)) {
            return;
        }
        Map<String, Object> frame = new HashMap<>();
        frame.put("eventType", eventType);
        frame.put("conversationId", conversationId.toString());
        if (folder != null) {
            frame.put(FOLDER_KEY, folder);
        }
        for (Object id : ids) {
            try {
                messagingTemplate.convertAndSend(
                        "/topic/inbox." + UUID.fromString(id.toString()), (Object) frame);
            } catch (RuntimeException ex) {
                log.warn(
                        "Failed to push inbox event on server {}: {}",
                        serverQueueInitializer.getServerId(),
                        ex.getMessage());
            }
        }
    }
}
