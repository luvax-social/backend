package com.app.modules.notification.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.app.modules.notification.dto.response.FollowRequestSummaryResponse;
import com.app.modules.notification.dto.response.NotificationItemResponse;
import com.app.modules.notification.dto.response.NotificationKeyResponse;
import com.app.modules.notification.dto.response.NotificationLiveEnvelope;
import com.app.modules.notification.dto.response.NotificationStateResponse;
import com.app.modules.notification.dto.response.UnseenCountResponse;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.messaging.NotificationEventTypes;
import com.app.modules.notification.service.NotificationService;

@ExtendWith(MockitoExtension.class)
class NotificationLiveFanoutConsumerTest {

    private static final NotificationStateResponse STATE =
            new NotificationStateResponse(
                    new UnseenCountResponse(3, false),
                    null,
                    null,
                    FollowRequestSummaryResponse.NONE);

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
        lenient().when(notificationService.getState(recipient)).thenReturn(STATE);
    }

    @Test
    void consume_upserted_pushesTheHydratedItemWithTheState() {
        receive(NotificationEventTypes.NOTIFICATION_UPSERTED_V1, Map.of());
        when(notificationService.findItem(recipient, notification)).thenReturn(Optional.of(item));
        when(item.type()).thenReturn(NotificationType.LIKE_POST);

        consumer.consume(amqpMessage);

        NotificationLiveEnvelope envelope = pushed();
        assertThat(envelope.event()).isEqualTo("upserted");
        assertThat(envelope.item()).isSameAs(item);
        assertThat(envelope.state()).isEqualTo(STATE);
    }

    @Test
    void consume_upsertedFollowRequest_isARequestsEventWithoutAnItem() {
        receive(NotificationEventTypes.NOTIFICATION_UPSERTED_V1, Map.of());
        when(notificationService.findItem(recipient, notification)).thenReturn(Optional.of(item));
        when(item.type()).thenReturn(NotificationType.FOLLOW_REQUEST);

        consumer.consume(amqpMessage);

        NotificationLiveEnvelope envelope = pushed();
        assertThat(envelope.event()).isEqualTo("requests");
        assertThat(envelope.item()).isNull();
    }

    @Test
    void consume_upsertedRowNoLongerVisible_pushesNothing() {
        receive(NotificationEventTypes.NOTIFICATION_UPSERTED_V1, Map.of());
        when(notificationService.findItem(recipient, notification)).thenReturn(Optional.empty());

        consumer.consume(amqpMessage);

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void consume_readStateForIds_carriesTheIdsAndReadTime() {
        String readAt = "2026-09-23T10:00:00Z";
        receive(
                NotificationEventTypes.NOTIFICATION_READ_STATE_CHANGED_V1,
                Map.of("ids", List.of(notification.toString()), "readAt", readAt));

        consumer.consume(amqpMessage);

        NotificationLiveEnvelope envelope = pushed();
        assertThat(envelope.event()).isEqualTo("read-state");
        assertThat(envelope.ids()).containsExactly(notification);
        assertThat(envelope.readAt()).isEqualTo(OffsetDateTime.parse(readAt));
    }

    @Test
    void consume_readStateWithoutReadTime_meansUnread() {
        receive(
                NotificationEventTypes.NOTIFICATION_READ_STATE_CHANGED_V1,
                Map.of("ids", List.of(notification.toString())));

        consumer.consume(amqpMessage);

        assertThat(pushed().readAt()).isNull();
    }

    @Test
    void consume_readAll_carriesTheBound() {
        OffsetDateTime at = OffsetDateTime.of(2026, 9, 23, 9, 0, 0, 0, ZoneOffset.UTC);
        receive(
                NotificationEventTypes.NOTIFICATION_READ_STATE_CHANGED_V1,
                Map.of(
                        "upTo",
                        Map.of("activityAt", at.toString(), "id", notification.toString()),
                        "readAt",
                        at.toString()));

        consumer.consume(amqpMessage);

        assertThat(pushed().upTo()).isEqualTo(new NotificationKeyResponse(at, notification));
    }

    @Test
    void consume_deletedAndSeen_carryTheirTypeAndTheState() {
        receive(
                NotificationEventTypes.NOTIFICATION_DELETED_V1,
                Map.of("ids", List.of(notification.toString())));
        consumer.consume(amqpMessage);
        receive(NotificationEventTypes.NOTIFICATION_SEEN_V1, Map.of());
        consumer.consume(amqpMessage);

        ArgumentCaptor<NotificationLiveEnvelope> captor =
                ArgumentCaptor.forClass(NotificationLiveEnvelope.class);
        verify(messagingTemplate, org.mockito.Mockito.times(2))
                .convertAndSend(eq("/topic/notifications." + recipient), captor.capture());
        assertThat(captor.getAllValues())
                .extracting(NotificationLiveEnvelope::event)
                .containsExactly("deleted", "seen");
        assertThat(captor.getAllValues().get(0).ids()).containsExactly(notification);
    }

    @Test
    void consume_eventWithoutRecipient_pushesNothing() {
        when(parser.parse(amqpMessage))
                .thenReturn(
                        new DomainEventEnvelope(
                                UUID.randomUUID(),
                                NotificationEventTypes.NOTIFICATION_SEEN_V1,
                                OffsetDateTime.now(ZoneOffset.UTC),
                                null,
                                "notification",
                                notification,
                                Map.of()));

        consumer.consume(amqpMessage);

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    private void receive(String type, Map<String, Object> extra) {
        Map<String, Object> data = new java.util.HashMap<>(extra);
        data.put("recipientId", recipient.toString());
        when(parser.parse(amqpMessage))
                .thenReturn(
                        new DomainEventEnvelope(
                                UUID.randomUUID(),
                                type,
                                OffsetDateTime.now(ZoneOffset.UTC),
                                null,
                                "notification",
                                notification,
                                data));
    }

    private NotificationLiveEnvelope pushed() {
        ArgumentCaptor<NotificationLiveEnvelope> captor =
                ArgumentCaptor.forClass(NotificationLiveEnvelope.class);
        verify(messagingTemplate)
                .convertAndSend(eq("/topic/notifications." + recipient), captor.capture());
        return captor.getValue();
    }
}
