package com.app.modules.message.live;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    private DomainEventEnvelope event(String eventType) {
        return new DomainEventEnvelope(
                UUID.randomUUID(),
                eventType,
                OffsetDateTime.now(ZoneOffset.UTC),
                UUID.randomUUID(),
                "message",
                conversationId,
                Map.of("conversationId", conversationId.toString()));
    }
}
