package com.app.modules.notification.live;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
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
import com.app.modules.notification.dto.response.NotificationItemResponse;
import com.app.modules.notification.service.NotificationService;

@ExtendWith(MockitoExtension.class)
class NotificationLiveFanoutConsumerTest {

    @Mock private DomainEventMessageParser parser;
    @Mock private NotificationService notificationService;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private Message amqpMessage;
    @Mock private NotificationItemResponse item;

    private NotificationLiveFanoutConsumer consumer;
    private final UUID recipient = UUID.randomUUID();
    private final UUID notification = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        consumer =
                new NotificationLiveFanoutConsumer(parser, notificationService, messagingTemplate);
    }

    @Test
    void consume_visibleRow_pushesTheHydratedItemToTheRecipient() {
        when(parser.parse(amqpMessage))
                .thenReturn(envelope(Map.of("recipientId", recipient.toString())));
        when(notificationService.findItem(recipient, notification)).thenReturn(Optional.of(item));

        consumer.consume(amqpMessage);

        verify(messagingTemplate).convertAndSend("/topic/notifications." + recipient, item);
    }

    @Test
    void consume_rowNoLongerVisible_pushesNothing() {
        when(parser.parse(amqpMessage))
                .thenReturn(envelope(Map.of("recipientId", recipient.toString())));
        when(notificationService.findItem(recipient, notification)).thenReturn(Optional.empty());

        consumer.consume(amqpMessage);

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void consume_eventWithoutRecipient_pushesNothing() {
        when(parser.parse(amqpMessage)).thenReturn(envelope(Map.of()));

        consumer.consume(amqpMessage);

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    private DomainEventEnvelope envelope(Map<String, Object> data) {
        return new DomainEventEnvelope(
                UUID.randomUUID(),
                "notification.upserted.v1",
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                "notification",
                notification,
                data);
    }
}
