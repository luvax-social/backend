package com.app.modules.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.dao.QueryTimeoutException;

import com.app.common.config.rabbit.RabbitMqTopologyConfig;
import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.inbox.enums.ProcessedMessageResult;
import com.app.common.inbox.service.ProcessedMessageService;
import com.app.common.messaging.DeadLetterPublisher;
import com.app.common.messaging.DomainEventMessageParser;
import com.app.common.messaging.config.ConsumerRetryProperties;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.common.outbox.model.DomainEventEnvelopeJson;
import com.app.modules.admin.messaging.AdminEventTypes;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.service.NotificationDraft;
import com.app.modules.notification.service.NotificationService;
import com.rabbitmq.client.Channel;

@ExtendWith(MockitoExtension.class)
class AdminNotificationConsumerTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID WARNING_ID = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID ACTION_ID = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    @Mock private ProcessedMessageService processedMessageService;
    @Mock private NotificationService notificationService;
    @Mock private DeadLetterPublisher deadLetterPublisher;
    @Mock private Channel channel;

    private AdminNotificationConsumer consumer;

    @BeforeEach
    void setUp() {
        ConsumerRetryProperties retryProperties = new ConsumerRetryProperties();
        retryProperties.setMaxAttempts(3);
        retryProperties.setRetryBackoffs(List.of(Duration.ZERO, Duration.ZERO));
        consumer =
                new AdminNotificationConsumer(
                        new DomainEventMessageParser(),
                        processedMessageService,
                        notificationService,
                        retryProperties,
                        deadLetterPublisher);
    }

    @Test
    void consume_userWarned_writesAPlatformNoticeLinkedToTheAuditRow() throws Exception {
        runHandlerInline();

        consumer.consume(message(warned(Map.of("adminActionId", ACTION_ID.toString()))), channel);

        NotificationDraft draft = createdDraft();
        assertThat(draft.recipientId()).isEqualTo(USER_ID);
        assertThat(draft.actorId()).isNull();
        assertThat(draft.type()).isEqualTo(NotificationType.WARNING);
        assertThat(draft.entityType()).isEqualTo("warning");
        assertThat(draft.entityId()).isEqualTo(WARNING_ID);
        assertThat(draft.postId()).isNull();
        assertThat(draft.adminActionId()).isEqualTo(ACTION_ID);
        verify(channel).basicAck(1L, false);
        verify(deadLetterPublisher, never()).publish(any(), anyString(), any());
    }

    @Test
    void consume_eventFromBeforeTheAuditLink_stillWritesTheNoticeWithoutIt() throws Exception {
        runHandlerInline();

        consumer.consume(message(warned(Map.of())), channel);

        assertThat(createdDraft().adminActionId()).isNull();
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_duplicateDelivery_writesNothing() throws Exception {
        when(processedMessageService.processOnce(any(), any(), any(), any()))
                .thenReturn(ProcessedMessageResult.DUPLICATE);

        consumer.consume(message(warned(Map.of())), channel);

        verify(notificationService, never()).create(any(NotificationDraft.class));
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_otherEventType_writesNothing() throws Exception {
        runHandlerInline();

        consumer.consume(message(envelope("user.struck.v1", baseData())), channel);

        verify(notificationService, never()).create(any(NotificationDraft.class));
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_missingWarningId_deadLettersWithoutProcessing() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("userId", USER_ID.toString());

        consumer.consume(message(envelope(AdminEventTypes.USER_WARNED_V1, data)), channel);

        verify(processedMessageService, never()).processOnce(any(), any(), any(), any());
        verify(deadLetterPublisher)
                .publish(
                        any(Message.class),
                        eq(RabbitMqTopologyConfig.ADMIN_NOTIFICATION_DEAD_LETTER_ROUTING_KEY),
                        anyString());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_transientFailure_retriesThenSucceeds() throws Exception {
        when(processedMessageService.processOnce(any(), any(), any(), any()))
                .thenThrow(new QueryTimeoutException("timeout"))
                .thenReturn(ProcessedMessageResult.PROCESSED);

        consumer.consume(message(warned(Map.of())), channel);

        verify(processedMessageService, times(2)).processOnce(any(), any(), any(), any());
        verify(deadLetterPublisher, never()).publish(any(), anyString(), any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_permanentFailure_deadLettersWithoutRetrying() throws Exception {
        when(processedMessageService.processOnce(any(), any(), any(), any()))
                .thenThrow(new AppException(ApiErrorCode.NOT_FOUND));

        consumer.consume(message(warned(Map.of())), channel);

        verify(processedMessageService, times(1)).processOnce(any(), any(), any(), any());
        verify(deadLetterPublisher)
                .publish(
                        any(Message.class),
                        eq(RabbitMqTopologyConfig.ADMIN_NOTIFICATION_DEAD_LETTER_ROUTING_KEY),
                        anyString());
    }

    @Test
    void consume_deadLetterPublishFails_requeues() throws Exception {
        when(processedMessageService.processOnce(any(), any(), any(), any()))
                .thenThrow(new AppException(ApiErrorCode.NOT_FOUND));
        doThrow(new IllegalStateException("broker down"))
                .when(deadLetterPublisher)
                .publish(any(), anyString(), any());

        consumer.consume(message(warned(Map.of())), channel);

        verify(channel).basicNack(1L, false, true);
        verify(channel, never()).basicAck(1L, false);
    }

    private void runHandlerInline() {
        when(processedMessageService.processOnce(any(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            inv.getArgument(3, Runnable.class).run();
                            return ProcessedMessageResult.PROCESSED;
                        });
    }

    private NotificationDraft createdDraft() {
        ArgumentCaptor<NotificationDraft> captor = ArgumentCaptor.forClass(NotificationDraft.class);
        verify(notificationService).create(captor.capture());
        return captor.getValue();
    }

    private static Map<String, Object> baseData() {
        Map<String, Object> data = new HashMap<>();
        data.put("userId", USER_ID.toString());
        data.put("warningId", WARNING_ID.toString());
        return data;
    }

    private static DomainEventEnvelope warned(Map<String, Object> extra) {
        Map<String, Object> data = baseData();
        data.putAll(extra);
        return envelope(AdminEventTypes.USER_WARNED_V1, data);
    }

    private static DomainEventEnvelope envelope(String eventType, Map<String, Object> data) {
        return new DomainEventEnvelope(
                EVENT_ID,
                eventType,
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                "user",
                USER_ID,
                data);
    }

    private static Message message(DomainEventEnvelope envelope) {
        return MessageBuilder.withBody(
                        DomainEventEnvelopeJson.write(envelope).getBytes(StandardCharsets.UTF_8))
                .setDeliveryTag(1L)
                .build();
    }
}
