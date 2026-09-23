package com.app.modules.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisSystemException;

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
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.service.NotificationService;
import com.app.modules.social.enums.FollowStatus;
import com.app.modules.social.messaging.SocialEventTypes;
import com.app.modules.social.service.SocialService;
import com.app.modules.support.messaging.SupportEventTypes;
import com.rabbitmq.client.Channel;

@ExtendWith(MockitoExtension.class)
class SocialNotificationConsumerTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID RECIPIENT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Mock private ProcessedMessageService processedMessageService;
    @Mock private NotificationService notificationService;
    @Mock private DeadLetterPublisher deadLetterPublisher;
    @Mock private SocialService socialService;
    @Mock private Channel channel;

    private ConsumerRetryProperties retryProperties;
    private List<Long> sleptMillis;
    private SocialNotificationConsumer consumer;

    @BeforeEach
    void setUp() {
        retryProperties = new ConsumerRetryProperties();
        retryProperties.setMaxAttempts(3);
        retryProperties.setRetryBackoffs(List.of(Duration.ofMillis(5), Duration.ZERO));
        sleptMillis = new ArrayList<>();
        consumer =
                new SocialNotificationConsumer(
                        new DomainEventMessageParser(),
                        processedMessageService,
                        notificationService,
                        retryProperties,
                        deadLetterPublisher,
                        socialService,
                        sleptMillis::add);
    }

    @Test
    void isTransient_dataAccessException_returnsTrue() {
        assertThat(consumer.isTransient(new QueryTimeoutException("timeout"))).isTrue();
    }

    @Test
    void isTransient_redisSystemException_returnsTrue() {
        assertThat(consumer.isTransient(new RedisSystemException("redis down", null))).isTrue();
    }

    @Test
    void isTransient_amqpException_returnsTrue() {
        assertThat(consumer.isTransient(new AmqpException("broker down"))).isTrue();
    }

    @Test
    void isTransient_appExceptionServiceUnavailable_returnsTrue() {
        assertThat(consumer.isTransient(new AppException(ApiErrorCode.SERVICE_UNAVAILABLE)))
                .isTrue();
    }

    @Test
    void isTransient_appExceptionOtherCode_returnsFalse() {
        assertThat(consumer.isTransient(new AppException(ApiErrorCode.NOT_FOUND))).isFalse();
    }

    @Test
    void isTransient_unknownRuntimeException_returnsTrue() {
        assertThat(consumer.isTransient(new IllegalStateException("unexpected"))).isTrue();
    }

    @Test
    void consume_nullEventId_routesToDlqAndAcks() throws Exception {
        Message message = message(nullEventIdEnvelope());

        consumer.consume(message, channel);

        verify(deadLetterPublisher)
                .publish(
                        eq(message),
                        eq(RabbitMqTopologyConfig.NOTIFICATION_DEAD_LETTER_ROUTING_KEY),
                        any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_blankEventType_routesToDlqAndAcks() throws Exception {
        Message message = message(blankEventTypeEnvelope());

        consumer.consume(message, channel);

        verify(deadLetterPublisher)
                .publish(
                        eq(message),
                        eq(RabbitMqTopologyConfig.NOTIFICATION_DEAD_LETTER_ROUTING_KEY),
                        any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_nullAggregateId_routesToDlqAndAcks() throws Exception {
        DomainEventEnvelope envelope =
                new DomainEventEnvelope(
                        EVENT_ID,
                        SocialEventTypes.USER_FOLLOWED_V1,
                        OffsetDateTime.now(ZoneOffset.UTC),
                        ACTOR_ID,
                        "user",
                        null,
                        Map.of());
        Message message = message(envelope);

        consumer.consume(message, channel);

        verify(deadLetterPublisher)
                .publish(
                        eq(message),
                        eq(RabbitMqTopologyConfig.NOTIFICATION_DEAD_LETTER_ROUTING_KEY),
                        any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_unknownEventType_acksWithoutCreatingNotification() throws Exception {
        Message message = message(envelope("unknown.event.type"));

        consumer.consume(message, channel);

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
        verify(channel).basicAck(1L, false);
        verify(deadLetterPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void consume_userFollowedEvent_createsFollowNotification() throws Exception {
        Message message = message(envelope(SocialEventTypes.USER_FOLLOWED_V1));
        runHandlers();
        when(socialService.findFollowStatus(ACTOR_ID, RECIPIENT_ID))
                .thenReturn(Optional.of(FollowStatus.ACCEPTED));

        consumer.consume(message, channel);

        verify(notificationService)
                .create(ACTOR_ID, RECIPIENT_ID, NotificationType.FOLLOW, null, null, null);
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_userFollowRequestedEvent_createsFollowRequestNotification() throws Exception {
        Message message = message(envelope(SocialEventTypes.USER_FOLLOW_REQUESTED_V1));
        runHandlers();
        when(socialService.findFollowStatus(ACTOR_ID, RECIPIENT_ID))
                .thenReturn(Optional.of(FollowStatus.PENDING));

        consumer.consume(message, channel);

        verify(notificationService)
                .create(ACTOR_ID, RECIPIENT_ID, NotificationType.FOLLOW_REQUEST, null, null, null);
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_followEventWhoseEdgeIsGone_writesNothing() throws Exception {
        runHandlers();
        when(socialService.findFollowStatus(ACTOR_ID, RECIPIENT_ID)).thenReturn(Optional.empty());

        consumer.consume(message(envelope(SocialEventTypes.USER_FOLLOWED_V1)), channel);

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_unfollowedWithNoEdgeLeft_retractsTheFollow() throws Exception {
        runHandlers();
        when(socialService.findFollowStatus(ACTOR_ID, RECIPIENT_ID)).thenReturn(Optional.empty());

        consumer.consume(
                message(
                        envelope(
                                SocialEventTypes.USER_UNFOLLOWED_V1,
                                Map.of(
                                        "followerId", ACTOR_ID.toString(),
                                        "followingId", RECIPIENT_ID.toString(),
                                        "previousStatus", "accepted"))),
                channel);

        verify(notificationService).retract(ACTOR_ID, RECIPIENT_ID, NotificationType.FOLLOW, null);
    }

    @Test
    void consume_unfollowedButFollowedAgain_retractsNothing() throws Exception {
        runHandlers();
        when(socialService.findFollowStatus(ACTOR_ID, RECIPIENT_ID))
                .thenReturn(Optional.of(FollowStatus.ACCEPTED));

        consumer.consume(
                message(
                        envelope(
                                SocialEventTypes.USER_UNFOLLOWED_V1,
                                Map.of(
                                        "followerId", ACTOR_ID.toString(),
                                        "followingId", RECIPIENT_ID.toString(),
                                        "previousStatus", "accepted"))),
                channel);

        verify(notificationService, never()).retract(any(), any(), any(), any());
    }

    @Test
    void consume_requestApprovedAndAccepted_convertsTheRequest() throws Exception {
        runHandlers();
        when(socialService.findFollowStatus(ACTOR_ID, RECIPIENT_ID))
                .thenReturn(Optional.of(FollowStatus.ACCEPTED));

        consumer.consume(
                message(envelope(SocialEventTypes.USER_FOLLOW_REQUEST_APPROVED_V1, resolution())),
                channel);

        verify(notificationService).resolveFollowRequest(ACTOR_ID, RECIPIENT_ID, true);
    }

    @Test
    void consume_requestRejected_withdrawsTheRequest() throws Exception {
        runHandlers();
        when(socialService.findFollowStatus(ACTOR_ID, RECIPIENT_ID)).thenReturn(Optional.empty());

        consumer.consume(
                message(envelope(SocialEventTypes.USER_FOLLOW_REQUEST_REJECTED_V1, resolution())),
                channel);

        verify(notificationService).resolveFollowRequest(ACTOR_ID, RECIPIENT_ID, false);
    }

    @Test
    void consume_blockStillInPlace_cleansBothUsersGroups() throws Exception {
        runHandlers();
        when(socialService.isBlockedBetween(RECIPIENT_ID, ACTOR_ID)).thenReturn(true);

        consumer.consume(
                message(
                        envelope(
                                SocialEventTypes.USER_BLOCKED_V1,
                                Map.of(
                                        "blockerId", RECIPIENT_ID.toString(),
                                        "blockedId", ACTOR_ID.toString()))),
                channel);

        verify(notificationService).onBlock(RECIPIENT_ID, ACTOR_ID);
    }

    @Test
    void consume_verificationChanged_resyncsTheAccountsRows() throws Exception {
        runHandlers();

        consumer.consume(
                message(
                        envelope(
                                SupportEventTypes.USER_VERIFICATION_CHANGED_V1,
                                Map.of("userId", RECIPIENT_ID.toString()))),
                channel);

        verify(notificationService).resyncActorVerified(RECIPIENT_ID);
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consume_resolutionMissingItsUsers_routesToDlq() throws Exception {
        runHandlers();

        consumer.consume(
                message(envelope(SocialEventTypes.USER_FOLLOW_REQUEST_APPROVED_V1)), channel);

        verify(deadLetterPublisher).publish(any(), any(), any());
        verify(notificationService, never()).resolveFollowRequest(any(), any(), anyBoolean());
    }

    @Test
    void consume_dlqPublishFailure_nacksOriginal() throws Exception {
        Message message = message(nullEventIdEnvelope());
        doThrow(new IllegalStateException("dlq down"))
                .when(deadLetterPublisher)
                .publish(any(), any(), any());

        consumer.consume(message, channel);

        verify(channel).basicNack(1L, false, true);
        verify(channel, never()).basicAck(1L, false);
    }

    @Test
    void consume_ackThrowsIOException_doesNotPropagate() throws Exception {
        Message message = message(envelope("unknown.event.type"));
        doThrow(new IOException("channel closed")).when(channel).basicAck(1L, false);

        assertThatCode(() -> consumer.consume(message, channel)).doesNotThrowAnyException();
    }

    @Test
    void consume_nackThrowsIOException_doesNotPropagate() throws Exception {
        Message message = message(nullEventIdEnvelope());
        doThrow(new IllegalStateException("dlq down"))
                .when(deadLetterPublisher)
                .publish(any(), any(), any());
        doThrow(new IOException("channel closed")).when(channel).basicNack(1L, false, true);

        assertThatCode(() -> consumer.consume(message, channel)).doesNotThrowAnyException();
    }

    private Message message(DomainEventEnvelope envelope) {
        return MessageBuilder.withBody(
                        DomainEventEnvelopeJson.write(envelope).getBytes(StandardCharsets.UTF_8))
                .setDeliveryTag(1L)
                .build();
    }

    private DomainEventEnvelope envelope(String eventType) {
        return envelope(eventType, Map.of());
    }

    private DomainEventEnvelope envelope(String eventType, Map<String, Object> data) {
        return new DomainEventEnvelope(
                EVENT_ID,
                eventType,
                OffsetDateTime.now(ZoneOffset.UTC),
                ACTOR_ID,
                "user",
                RECIPIENT_ID,
                data);
    }

    private static Map<String, Object> resolution() {
        return Map.of("requesterId", ACTOR_ID.toString(), "approverId", RECIPIENT_ID.toString());
    }

    private void runHandlers() {
        when(processedMessageService.processOnce(any(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            inv.getArgument(3, Runnable.class).run();
                            return ProcessedMessageResult.PROCESSED;
                        });
    }

    private DomainEventEnvelope nullEventIdEnvelope() {
        return new DomainEventEnvelope(
                null,
                SocialEventTypes.USER_FOLLOWED_V1,
                OffsetDateTime.now(ZoneOffset.UTC),
                ACTOR_ID,
                "user",
                RECIPIENT_ID,
                Map.of());
    }

    private DomainEventEnvelope blankEventTypeEnvelope() {
        return new DomainEventEnvelope(
                EVENT_ID,
                "",
                OffsetDateTime.now(ZoneOffset.UTC),
                ACTOR_ID,
                "user",
                RECIPIENT_ID,
                Map.of());
    }
}
