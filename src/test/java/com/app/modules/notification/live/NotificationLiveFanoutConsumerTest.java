package com.app.modules.notification.live;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.app.common.messaging.DomainEventMessageParser;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.common.response.UserSummaryResponse;
import com.app.modules.notification.dto.response.NotificationResponse;
import com.app.modules.notification.entity.Notification;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.mapper.NotificationMapper;
import com.app.modules.notification.repository.NotificationRepository;
import com.app.modules.social.repository.BlockRepository;
import com.app.modules.users.service.UserSummaryService;

@ExtendWith(MockitoExtension.class)
class NotificationLiveFanoutConsumerTest {

    @Mock private DomainEventMessageParser parser;
    @Mock private NotificationRepository notificationRepository;
    @Mock private NotificationMapper notificationMapper;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private UserSummaryService userSummaryService;
    @Mock private BlockRepository blockRepository;
    @Mock private Message amqpMessage;

    private NotificationLiveFanoutConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer =
                new NotificationLiveFanoutConsumer(
                        parser,
                        notificationRepository,
                        notificationMapper,
                        messagingTemplate,
                        userSummaryService,
                        blockRepository);
    }

    @Test
    void consume_notificationExists_pushesMappedResponseToRecipientTopic() {
        UUID notificationId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        DomainEventEnvelope event =
                new DomainEventEnvelope(
                        UUID.randomUUID(),
                        "notification.created.v1",
                        OffsetDateTime.now(ZoneOffset.UTC),
                        actorId,
                        "notification",
                        notificationId,
                        Map.of("recipientId", recipientId.toString()));
        when(parser.parse(amqpMessage)).thenReturn(event);
        Notification notification =
                Notification.builder()
                        .id(notificationId)
                        .recipientId(recipientId)
                        .actorId(actorId)
                        .type(NotificationType.FOLLOW)
                        .category(NotificationType.FOLLOW.category())
                        .build();
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        UserSummaryResponse actor =
                new UserSummaryResponse(actorId, "actor_username", "Actor", null, false);
        when(userSummaryService.loadSummaries(List.of(actorId))).thenReturn(Map.of(actorId, actor));
        NotificationResponse response =
                new NotificationResponse(
                        notificationId,
                        actor,
                        NotificationType.FOLLOW,
                        null,
                        null,
                        null,
                        null,
                        false,
                        null,
                        OffsetDateTime.now(ZoneOffset.UTC));
        when(notificationMapper.toResponse(notification, actor)).thenReturn(response);

        consumer.consume(amqpMessage);

        verify(messagingTemplate)
                .convertAndSend(eq("/topic/notifications." + recipientId), eq(response));
    }

    @Test
    void consume_actorBlockedWithRecipient_doesNotPush() {
        UUID notificationId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        DomainEventEnvelope event =
                new DomainEventEnvelope(
                        UUID.randomUUID(),
                        "notification.created.v1",
                        OffsetDateTime.now(ZoneOffset.UTC),
                        actorId,
                        "notification",
                        notificationId,
                        Map.of("recipientId", recipientId.toString()));
        when(parser.parse(amqpMessage)).thenReturn(event);
        Notification notification =
                Notification.builder()
                        .id(notificationId)
                        .recipientId(recipientId)
                        .actorId(actorId)
                        .type(NotificationType.MENTION_POST)
                        .category(NotificationType.MENTION_POST.category())
                        .build();
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(blockRepository.existsBetween(actorId, recipientId)).thenReturn(true);

        consumer.consume(amqpMessage);

        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void consume_notificationNotFound_doesNotPush() {
        UUID notificationId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        DomainEventEnvelope event =
                new DomainEventEnvelope(
                        UUID.randomUUID(),
                        "notification.created.v1",
                        OffsetDateTime.now(ZoneOffset.UTC),
                        null,
                        "notification",
                        notificationId,
                        Map.of("recipientId", recipientId.toString()));
        when(parser.parse(amqpMessage)).thenReturn(event);
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.empty());

        consumer.consume(amqpMessage);

        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void consume_missingRecipientId_doesNotPush() {
        UUID notificationId = UUID.randomUUID();
        DomainEventEnvelope event =
                new DomainEventEnvelope(
                        UUID.randomUUID(),
                        "notification.created.v1",
                        OffsetDateTime.now(ZoneOffset.UTC),
                        null,
                        "notification",
                        notificationId,
                        Map.of());
        when(parser.parse(amqpMessage)).thenReturn(event);

        consumer.consume(amqpMessage);

        verify(notificationRepository, never()).findById(any());
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void consume_parserThrows_isCaughtAndLogged() {
        when(parser.parse(amqpMessage)).thenThrow(new RuntimeException("malformed envelope"));

        consumer.consume(amqpMessage);

        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }
}
