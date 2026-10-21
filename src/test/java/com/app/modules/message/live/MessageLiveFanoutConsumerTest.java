package com.app.modules.message.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.app.common.messaging.DomainEventMessageParser;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.modules.message.messaging.MessageEventTypes;

@ExtendWith(MockitoExtension.class)
class MessageLiveFanoutConsumerTest {

    @Mock private DomainEventMessageParser parser;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private MessageLiveServerQueueInitializer serverQueueInitializer;
    @Mock private Message amqpMessage;

    private MessageLiveFanoutConsumer consumer;
    private final UUID conversationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        consumer = new MessageLiveFanoutConsumer(parser, messagingTemplate, serverQueueInitializer);
    }

    @Test
    void consume_aSentMessage_isPushedToTheThreadTopic() {
        when(parser.parse(amqpMessage)).thenReturn(event(MessageEventTypes.MESSAGE_SENT_V1));

        consumer.consume(amqpMessage);

        verify(messagingTemplate)
                .convertAndSend(
                        eq("/topic/conversations." + conversationId + ".messages"),
                        any(Object.class));
    }

    @Test
    void consume_aRequestResolution_isNeverPushedBecauseTheSenderIsSubscribedToTheThread() {
        when(parser.parse(amqpMessage))
                .thenReturn(event(MessageEventTypes.MESSAGE_REQUEST_RESOLVED_V1));

        consumer.consume(amqpMessage);

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void consume_aSentMessageWithRecipients_pushesASlimFrameToEachInboxTopic() {
        UUID recipient = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Map<String, Object> data = new HashMap<>();
        data.put("conversationId", conversationId.toString());
        data.put("message", Map.of("content", "secret words"));
        data.put("inboxRecipientIds", List.of(recipient.toString(), second.toString()));
        data.put("folder", "hidden_requests");
        when(parser.parse(amqpMessage)).thenReturn(event(MessageEventTypes.MESSAGE_SENT_V1, data));

        consumer.consume(amqpMessage);

        ArgumentCaptor<Object> frame = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/inbox." + recipient), frame.capture());
        verify(messagingTemplate).convertAndSend(eq("/topic/inbox." + second), any(Object.class));
        assertThat(frame.getValue())
                .isEqualTo(
                        Map.of(
                                "eventType",
                                MessageEventTypes.MESSAGE_SENT_V1,
                                "conversationId",
                                conversationId.toString(),
                                "folder",
                                "hidden_requests"));
    }

    @Test
    void consume_aSentMessageWithRecipients_keepsTheAudienceAndFolderOffTheThreadFrame() {
        Map<String, Object> data = new HashMap<>();
        data.put("conversationId", conversationId.toString());
        data.put("inboxRecipientIds", List.of(UUID.randomUUID().toString()));
        data.put("folder", "hidden_requests");
        when(parser.parse(amqpMessage)).thenReturn(event(MessageEventTypes.MESSAGE_SENT_V1, data));

        consumer.consume(amqpMessage);

        ArgumentCaptor<Object> frame = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate)
                .convertAndSend(
                        eq("/topic/conversations." + conversationId + ".messages"),
                        frame.capture());
        assertThat(frame.getValue().toString())
                .doesNotContain("inboxRecipientIds")
                .doesNotContain("hidden_requests");
    }

    @Test
    void consume_aReactionWithRecipients_pushesAFrameWithoutFolderOrReactionDetail() {
        UUID recipient = UUID.randomUUID();
        Map<String, Object> data = new HashMap<>();
        data.put("conversationId", conversationId.toString());
        data.put("emoji", "x");
        data.put("inboxRecipientIds", List.of(recipient.toString()));
        when(parser.parse(amqpMessage))
                .thenReturn(event(MessageEventTypes.MESSAGE_REACTION_CHANGED_V1, data));

        consumer.consume(amqpMessage);

        ArgumentCaptor<Object> frame = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/inbox." + recipient), frame.capture());
        assertThat(frame.getValue())
                .isEqualTo(
                        Map.of(
                                "eventType",
                                MessageEventTypes.MESSAGE_REACTION_CHANGED_V1,
                                "conversationId",
                                conversationId.toString()));
    }

    @Test
    void consume_aSentMessageWithoutRecipients_pushesNothingToAnyInboxTopic() {
        when(parser.parse(amqpMessage)).thenReturn(event(MessageEventTypes.MESSAGE_SENT_V1));

        consumer.consume(amqpMessage);

        verify(messagingTemplate, never())
                .convertAndSend(startsWith("/topic/inbox."), any(Object.class));
    }

    @Test
    void consume_aMalformedRecipientId_isSkippedAndTheRestStillReceiveTheFrame() {
        UUID recipient = UUID.randomUUID();
        Map<String, Object> data = new HashMap<>();
        data.put("conversationId", conversationId.toString());
        data.put("inboxRecipientIds", List.of("not-a-uuid", recipient.toString()));
        data.put("folder", "requests");
        when(parser.parse(amqpMessage)).thenReturn(event(MessageEventTypes.MESSAGE_SENT_V1, data));

        consumer.consume(amqpMessage);

        verify(messagingTemplate)
                .convertAndSend(eq("/topic/inbox." + recipient), any(Object.class));
    }

    private DomainEventEnvelope event(String eventType) {
        return event(eventType, Map.of("conversationId", conversationId.toString()));
    }

    private DomainEventEnvelope event(String eventType, Map<String, Object> data) {
        return new DomainEventEnvelope(
                UUID.randomUUID(),
                eventType,
                OffsetDateTime.now(ZoneOffset.UTC),
                UUID.randomUUID(),
                "message",
                conversationId,
                data);
    }
}
