package com.app.modules.auth.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.app.common.messaging.exception.PermanentMessageException;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.common.outbox.model.DomainEventEnvelopeJson;
import com.rabbitmq.client.Channel;

@ExtendWith(MockitoExtension.class)
class AuthMailEventConsumerTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000456");

    @Mock private ProcessedMessageService processedMessageService;
    @Mock private AuthMailEventHandler handler;
    @Mock private DeadLetterPublisher deadLetterPublisher;
    @Mock private Channel channel;

    private DomainEventMessageParser parser;
    private ConsumerRetryProperties retryProperties;
    private List<Long> sleptMillis;
    private AuthMailEventConsumer consumer;

    @BeforeEach
    void setUp() {
        parser = new DomainEventMessageParser();
        retryProperties = new ConsumerRetryProperties();
        retryProperties.setMaxAttempts(3);
        retryProperties.setRetryBackoffs(List.of(Duration.ofMillis(5), Duration.ZERO));
        sleptMillis = new ArrayList<>();
        consumer =
                new AuthMailEventConsumer(
                        parser,
                        processedMessageService,
                        handler,
                        retryProperties,
                        deadLetterPublisher,
                        sleptMillis::add);
    }

    @Test
    void consumeDuplicateEvent_acksWithoutRunningHandler() throws Exception {
        Message message = message(event(AuthEventTypes.USER_REGISTERED_V1));
        when(processedMessageService.processOnce(
                        org.mockito.ArgumentMatchers.eq(AuthMailEventConsumer.CONSUMER_NAME),
                        org.mockito.ArgumentMatchers.eq(EVENT_ID),
                        org.mockito.ArgumentMatchers.eq(AuthEventTypes.USER_REGISTERED_V1),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(ProcessedMessageResult.DUPLICATE);

        consumer.consume(message, channel);

        verify(handler, never())
                .handle(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consumeTransientFailure_retriesThenAckOnSuccess() throws Exception {
        Message message = message(event(AuthEventTypes.USER_REGISTERED_V1));
        when(processedMessageService.processOnce(
                        org.mockito.ArgumentMatchers.eq(AuthMailEventConsumer.CONSUMER_NAME),
                        org.mockito.ArgumentMatchers.eq(EVENT_ID),
                        org.mockito.ArgumentMatchers.eq(AuthEventTypes.USER_REGISTERED_V1),
                        org.mockito.ArgumentMatchers.any()))
                .thenThrow(new AppException(ApiErrorCode.SERVICE_UNAVAILABLE))
                .thenReturn(ProcessedMessageResult.PROCESSED);

        consumer.consume(message, channel);

        verify(channel).basicAck(1L, false);
        verify(deadLetterPublisher, never())
                .publish(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        org.assertj.core.api.Assertions.assertThat(sleptMillis).containsExactly(5L);
    }

    @Test
    void consumeTransientFailureExhausted_routesToDlqAndAcksOriginal() throws Exception {
        Message message = message(event(AuthEventTypes.USER_REGISTERED_V1));
        when(processedMessageService.processOnce(
                        org.mockito.ArgumentMatchers.eq(AuthMailEventConsumer.CONSUMER_NAME),
                        org.mockito.ArgumentMatchers.eq(EVENT_ID),
                        org.mockito.ArgumentMatchers.eq(AuthEventTypes.USER_REGISTERED_V1),
                        org.mockito.ArgumentMatchers.any()))
                .thenThrow(new AppException(ApiErrorCode.SERVICE_UNAVAILABLE))
                .thenThrow(new AppException(ApiErrorCode.SERVICE_UNAVAILABLE))
                .thenThrow(new AppException(ApiErrorCode.SERVICE_UNAVAILABLE));

        consumer.consume(message, channel);

        verify(processedMessageService, times(3))
                .processOnce(
                        org.mockito.ArgumentMatchers.eq(AuthMailEventConsumer.CONSUMER_NAME),
                        org.mockito.ArgumentMatchers.eq(EVENT_ID),
                        org.mockito.ArgumentMatchers.eq(AuthEventTypes.USER_REGISTERED_V1),
                        org.mockito.ArgumentMatchers.any());
        verify(deadLetterPublisher)
                .publish(
                        org.mockito.ArgumentMatchers.eq(message),
                        org.mockito.ArgumentMatchers.eq(
                                RabbitMqTopologyConfig.MAIL_DEAD_LETTER_ROUTING_KEY),
                        org.mockito.ArgumentMatchers.any());
        verify(channel).basicAck(1L, false);
        org.assertj.core.api.Assertions.assertThat(sleptMillis).containsExactly(5L);
    }

    @Test
    void consumeProviderRejection_deadLettersOnFirstAttemptWithoutRetrying() throws Exception {
        // A 422 about a malformed recipient used to arrive as SERVICE_UNAVAILABLE, so it burned
        // the whole retry ladder with its backoff, held a consumer thread behind it, and then
        // dead-lettered labelled "temporarily unavailable" - a reason that invites a replay which
        // can only fail identically.
        Message message = message(event(AuthEventTypes.USER_REGISTERED_V1));
        when(processedMessageService.processOnce(
                        org.mockito.ArgumentMatchers.eq(AuthMailEventConsumer.CONSUMER_NAME),
                        org.mockito.ArgumentMatchers.eq(EVENT_ID),
                        org.mockito.ArgumentMatchers.eq(AuthEventTypes.USER_REGISTERED_V1),
                        org.mockito.ArgumentMatchers.any()))
                .thenThrow(
                        new AppException(
                                ApiErrorCode.MAIL_PERMANENTLY_REJECTED,
                                "Mail provider permanently rejected the message: 422"));

        consumer.consume(message, channel);

        verify(processedMessageService, times(1))
                .processOnce(
                        org.mockito.ArgumentMatchers.eq(AuthMailEventConsumer.CONSUMER_NAME),
                        org.mockito.ArgumentMatchers.eq(EVENT_ID),
                        org.mockito.ArgumentMatchers.eq(AuthEventTypes.USER_REGISTERED_V1),
                        org.mockito.ArgumentMatchers.any());
        verify(deadLetterPublisher)
                .publish(
                        org.mockito.ArgumentMatchers.eq(message),
                        org.mockito.ArgumentMatchers.eq(
                                RabbitMqTopologyConfig.MAIL_DEAD_LETTER_ROUTING_KEY),
                        org.mockito.ArgumentMatchers.contains("permanently rejected"));
        verify(channel).basicAck(1L, false);
        org.assertj.core.api.Assertions.assertThat(sleptMillis).isEmpty();
    }

    // P7-BE-002. The allowlist refusing a recipient is the operator's own configuration decision,
    // not a delivery failure, so it must be acked rather than dead-lettered. Under the dev default
    // allowlist of example.invalid this is every verification, reset and email-change message
    // addressed to a seeded account at a real domain, which filled the DLQ with choices the
    // operator had already made.
    @Test
    void consumeSuppressedRecipient_acksWithoutDeadLettering() throws Exception {
        Message message = message(event(AuthEventTypes.USER_REGISTERED_V1));
        when(processedMessageService.processOnce(
                        org.mockito.ArgumentMatchers.eq(AuthMailEventConsumer.CONSUMER_NAME),
                        org.mockito.ArgumentMatchers.eq(EVENT_ID),
                        org.mockito.ArgumentMatchers.eq(AuthEventTypes.USER_REGISTERED_V1),
                        org.mockito.ArgumentMatchers.any()))
                .thenThrow(new AppException(ApiErrorCode.MAIL_RECIPIENT_NOT_ALLOWED));

        consumer.consume(message, channel);

        verify(deadLetterPublisher, never())
                .publish(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(1L, false, true);
        // Not retried either: a configuration decision will not change on a second attempt.
        org.assertj.core.api.Assertions.assertThat(sleptMillis).isEmpty();
    }

    @Test
    void isTransient_mailRecipientNotAllowed_returnsFalse() {
        assertThat(consumer.isTransient(new AppException(ApiErrorCode.MAIL_RECIPIENT_NOT_ALLOWED)))
                .isFalse();
    }

    @Test
    void isTransient_mailPermanentlyRejected_returnsFalse() {
        assertThat(consumer.isTransient(new AppException(ApiErrorCode.MAIL_PERMANENTLY_REJECTED)))
                .isFalse();
    }

    @Test
    void consumePermanentFailure_routesToDlqAndAcksOriginal() throws Exception {
        Message message = message(event(AuthEventTypes.USER_REGISTERED_V1));
        when(processedMessageService.processOnce(
                        org.mockito.ArgumentMatchers.eq(AuthMailEventConsumer.CONSUMER_NAME),
                        org.mockito.ArgumentMatchers.eq(EVENT_ID),
                        org.mockito.ArgumentMatchers.eq(AuthEventTypes.USER_REGISTERED_V1),
                        org.mockito.ArgumentMatchers.any()))
                .thenThrow(new PermanentMessageException("bad event"));

        consumer.consume(message, channel);

        verify(deadLetterPublisher)
                .publish(message, RabbitMqTopologyConfig.MAIL_DEAD_LETTER_ROUTING_KEY, "bad event");
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consumeInvalidJson_routesToDlqAndAcksOriginal() throws Exception {
        Message message =
                MessageBuilder.withBody("{bad json".getBytes(StandardCharsets.UTF_8))
                        .setDeliveryTag(1L)
                        .build();

        consumer.consume(message, channel);

        verify(deadLetterPublisher)
                .publish(
                        org.mockito.ArgumentMatchers.eq(message),
                        org.mockito.ArgumentMatchers.eq(
                                RabbitMqTopologyConfig.MAIL_DEAD_LETTER_ROUTING_KEY),
                        org.mockito.ArgumentMatchers.contains("not a readable event envelope"));
        verify(channel).basicAck(1L, false);
        verify(processedMessageService, never())
                .processOnce(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void consumeDlqPublishFailure_nacksOriginalForRequeue() throws Exception {
        Message message = message(event(AuthEventTypes.USER_REGISTERED_V1));
        when(processedMessageService.processOnce(
                        org.mockito.ArgumentMatchers.eq(AuthMailEventConsumer.CONSUMER_NAME),
                        org.mockito.ArgumentMatchers.eq(EVENT_ID),
                        org.mockito.ArgumentMatchers.eq(AuthEventTypes.USER_REGISTERED_V1),
                        org.mockito.ArgumentMatchers.any()))
                .thenThrow(new PermanentMessageException("bad event"));
        doThrow(new IllegalStateException("dlq down"))
                .when(deadLetterPublisher)
                .publish(message, RabbitMqTopologyConfig.MAIL_DEAD_LETTER_ROUTING_KEY, "bad event");

        consumer.consume(message, channel);

        verify(channel).basicNack(1L, false, true);
        verify(channel, never()).basicAck(1L, false);
    }

    @Test
    void consume_ackThrowsIOException_doesNotPropagate() throws Exception {
        Message message = message(event(AuthEventTypes.USER_REGISTERED_V1));
        when(processedMessageService.processOnce(
                        org.mockito.ArgumentMatchers.eq(AuthMailEventConsumer.CONSUMER_NAME),
                        org.mockito.ArgumentMatchers.eq(EVENT_ID),
                        org.mockito.ArgumentMatchers.eq(AuthEventTypes.USER_REGISTERED_V1),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(ProcessedMessageResult.PROCESSED);
        doThrow(new IOException("channel closed")).when(channel).basicAck(1L, false);

        assertThatCode(() -> consumer.consume(message, channel)).doesNotThrowAnyException();
    }

    @Test
    void consume_nackThrowsIOException_doesNotPropagate() throws Exception {
        Message message = message(event(AuthEventTypes.USER_REGISTERED_V1));
        when(processedMessageService.processOnce(
                        org.mockito.ArgumentMatchers.eq(AuthMailEventConsumer.CONSUMER_NAME),
                        org.mockito.ArgumentMatchers.eq(EVENT_ID),
                        org.mockito.ArgumentMatchers.eq(AuthEventTypes.USER_REGISTERED_V1),
                        org.mockito.ArgumentMatchers.any()))
                .thenThrow(new PermanentMessageException("bad event"));
        doThrow(new IllegalStateException("dlq down"))
                .when(deadLetterPublisher)
                .publish(message, RabbitMqTopologyConfig.MAIL_DEAD_LETTER_ROUTING_KEY, "bad event");
        doThrow(new IOException("channel closed")).when(channel).basicNack(1L, false, true);

        assertThatCode(() -> consumer.consume(message, channel)).doesNotThrowAnyException();
    }

    @Test
    void isTransient_dataAccessException_returnsTrue() {
        assertThat(consumer.isTransient(new QueryTimeoutException("db timeout"))).isTrue();
    }

    @Test
    void isTransient_redisSystemException_returnsTrue() {
        assertThat(consumer.isTransient(new RedisSystemException("redis down", null))).isTrue();
    }

    @Test
    void isTransient_amqpException_returnsTrue() {
        assertThat(consumer.isTransient(new AmqpException("broker unreachable"))).isTrue();
    }

    @Test
    void isTransient_appExceptionNonServiceUnavailable_returnsFalse() {
        assertThat(consumer.isTransient(new AppException(ApiErrorCode.POST_NOT_FOUND))).isFalse();
    }

    private Message message(DomainEventEnvelope event) {
        return MessageBuilder.withBody(
                        DomainEventEnvelopeJson.write(event).getBytes(StandardCharsets.UTF_8))
                .setDeliveryTag(1L)
                .build();
    }

    private DomainEventEnvelope event(String eventType) {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000123");
        return new DomainEventEnvelope(
                EVENT_ID,
                eventType,
                OffsetDateTime.now(ZoneOffset.UTC),
                userId,
                "user",
                userId,
                Map.of("userId", userId.toString()));
    }
}
