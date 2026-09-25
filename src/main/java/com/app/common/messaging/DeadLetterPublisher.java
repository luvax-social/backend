package com.app.common.messaging;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.app.common.config.rabbit.RabbitMqTopologyConfig;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** Publishes failed messages to RabbitMQ DLX and waits for broker acceptance. */
@Component
public class DeadLetterPublisher {

    private static final long CONFIRM_TIMEOUT_MILLIS = 5000L;
    private static final int MAX_REASON_LENGTH = 500;

    private final ObjectProvider<RabbitTemplate> rabbitTemplateProvider;
    private final MeterRegistry meterRegistry;

    public DeadLetterPublisher(
            ObjectProvider<RabbitTemplate> rabbitTemplateProvider, MeterRegistry meterRegistry) {
        this.rabbitTemplateProvider = rabbitTemplateProvider;
        this.meterRegistry = meterRegistry;
    }

    public void publish(Message original, String deadLetterRoutingKey, String reason) {
        RabbitTemplate rabbitTemplate =
                rabbitTemplateProvider.getIfAvailable(
                        () -> {
                            throw new IllegalStateException(
                                    "RabbitTemplate is not available for dead-letter publishing");
                        });
        CorrelationData correlationData = new CorrelationData(UUID.randomUUID().toString());
        rabbitTemplate.send(
                RabbitMqTopologyConfig.SOCIAL_EVENTS_DEAD_LETTER_EXCHANGE,
                deadLetterRoutingKey,
                buildDeadLetterMessage(original, reason),
                correlationData);
        waitForConfirm(correlationData);
        if (correlationData.getReturned() != null) {
            throw new IllegalStateException("Dead-letter message was returned by RabbitMQ");
        }
        String queue = original.getMessageProperties().getConsumerQueue();
        Counter.builder("luvax.messaging.dead.lettered")
                .tag("queue", queue == null ? "unknown" : queue)
                .tag("dead_letter_routing_key", deadLetterRoutingKey)
                .register(meterRegistry)
                .increment();
    }

    private Message buildDeadLetterMessage(Message original, String reason) {
        MessageBuilder builder = MessageBuilder.withBody(original.getBody());
        original.getMessageProperties()
                .getHeaders()
                .forEach((key, value) -> builder.setHeaderIfAbsent(key, value));
        if (original.getMessageProperties().getContentType() != null) {
            builder.setContentType(original.getMessageProperties().getContentType());
        }
        if (original.getMessageProperties().getContentEncoding() != null) {
            builder.setContentEncoding(original.getMessageProperties().getContentEncoding());
        }
        if (original.getMessageProperties().getMessageId() != null) {
            builder.setMessageId(original.getMessageProperties().getMessageId());
        }
        return builder.setHeader(
                        "x-original-exchange",
                        original.getMessageProperties().getReceivedExchange())
                .setHeader(
                        "x-original-routing-key",
                        original.getMessageProperties().getReceivedRoutingKey())
                .setHeader("x-dead-lettered-at", OffsetDateTime.now(ZoneOffset.UTC).toString())
                .setHeader("x-dead-letter-reason", sanitize(reason))
                .build();
    }

    private void waitForConfirm(CorrelationData correlationData) {
        try {
            CorrelationData.Confirm confirm =
                    correlationData.getFuture().get(CONFIRM_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                throw new IllegalStateException("RabbitMQ rejected dead-letter message");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing dead-letter message", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Failed to publish dead-letter message", ex.getCause());
        } catch (TimeoutException ex) {
            throw new IllegalStateException("Timed out publishing dead-letter message", ex);
        }
    }

    private String sanitize(String reason) {
        String value = reason == null || reason.isBlank() ? "Unknown message failure" : reason;
        if (value.length() <= MAX_REASON_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_REASON_LENGTH);
    }
}
