package com.app.modules.admin.messaging;

import java.io.IOException;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.stereotype.Component;

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
import com.rabbitmq.client.Channel;

/**
 * RabbitMQ consumer for moderation notice mail.
 *
 * <p>Shaped on {@code AuthMailEventConsumer}: manual acknowledgement, inbox deduplication under its
 * own consumer name, the shared retry policy, and the same application-level dead-letter route. It
 * is a separate consumer rather than a second listener on {@code mail.queue} because the handler
 * behind it must reach accounts the auth mail handler refuses.
 */
@Component
@ConditionalOnProperty(
        prefix = "app.admin.moderation-mail.consumer",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class ModerationMailEventConsumer {

    static final String CONSUMER_NAME = "moderation-mail-consumer";

    private static final Logger log = LoggerFactory.getLogger(ModerationMailEventConsumer.class);

    private final DomainEventMessageParser parser;
    private final ProcessedMessageService processedMessageService;
    private final ModerationMailEventHandler handler;
    private final ConsumerRetryProperties retryProperties;
    private final DeadLetterPublisher deadLetterPublisher;
    private final Sleeper sleeper;

    @Autowired
    public ModerationMailEventConsumer(
            DomainEventMessageParser parser,
            ProcessedMessageService processedMessageService,
            ModerationMailEventHandler handler,
            ConsumerRetryProperties retryProperties,
            DeadLetterPublisher deadLetterPublisher) {
        this(
                parser,
                processedMessageService,
                handler,
                retryProperties,
                deadLetterPublisher,
                Thread::sleep);
    }

    ModerationMailEventConsumer(
            DomainEventMessageParser parser,
            ProcessedMessageService processedMessageService,
            ModerationMailEventHandler handler,
            ConsumerRetryProperties retryProperties,
            DeadLetterPublisher deadLetterPublisher,
            Sleeper sleeper) {
        this.parser = parser;
        this.processedMessageService = processedMessageService;
        this.handler = handler;
        this.retryProperties = retryProperties;
        this.deadLetterPublisher = deadLetterPublisher;
        this.sleeper = sleeper;
    }

    @RabbitListener(queues = RabbitMqTopologyConfig.MODERATION_MAIL_QUEUE)
    public void consume(Message message, Channel channel) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            DomainEventEnvelope event = parser.parse(message);
            validateEnvelopeIdentity(event);
            ProcessedMessageResult result = processWithRetry(event);
            ack(channel, deliveryTag);
            if (result == ProcessedMessageResult.DUPLICATE) {
                log.info("Skipped duplicate moderation mail event {}", event.eventId());
            }
        } catch (RuntimeException ex) {
            routeToDlqOrRequeue(message, channel, deliveryTag, ex);
        }
    }

    private ProcessedMessageResult processWithRetry(DomainEventEnvelope event) {
        int maxAttempts = retryProperties.resolvedMaxAttempts();
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return processedMessageService.processOnce(
                        CONSUMER_NAME,
                        event.eventId(),
                        event.eventType(),
                        () -> handler.handle(event));
            } catch (RuntimeException ex) {
                if (isPermanent(ex) || !isTransient(ex)) {
                    throw ex;
                }
                lastFailure = ex;
                if (attempt >= maxAttempts) {
                    break;
                }
                sleepBeforeRetry(attempt);
            }
        }
        throw lastFailure;
    }

    private void validateEnvelopeIdentity(DomainEventEnvelope event) {
        if (event == null) {
            throw new PermanentMessageException("Event envelope is null");
        }
        if (event.eventId() == null) {
            throw new PermanentMessageException("Event id is missing");
        }
        if (event.eventType() == null || event.eventType().isBlank()) {
            throw new PermanentMessageException("Event type is missing");
        }
    }

    private void sleepBeforeRetry(int attempt) {
        Duration backoff = retryProperties.retryBackoffForAttempt(attempt);
        if (backoff.isZero()) {
            return;
        }
        try {
            sleeper.sleep(backoff.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AppException(ApiErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private boolean isPermanent(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof PermanentMessageException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void routeToDlqOrRequeue(
            Message message, Channel channel, long deliveryTag, RuntimeException failure) {
        try {
            deadLetterPublisher.publish(
                    message,
                    RabbitMqTopologyConfig.MODERATION_MAIL_DEAD_LETTER_ROUTING_KEY,
                    failure.getMessage());
            ack(channel, deliveryTag);
        } catch (RuntimeException dlqFailure) {
            log.warn(
                    "Failed to publish moderation mail event to DLQ; requeueing original message:"
                            + " {}",
                    dlqFailure.getMessage());
            nack(channel, deliveryTag);
        }
    }

    // channel.basicAck/basicNack declare IOException on a broken or closed AMQP channel; the
    // listener container's own recovery handles that case, so we log and return rather than letting
    // a checked IOException escape this @RabbitListener method uncaught.
    private void ack(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException ex) {
            log.error("Failed to ack moderation mail message: {}", ex.getMessage());
        }
    }

    private void nack(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (IOException ex) {
            log.error("Failed to nack moderation mail message: {}", ex.getMessage());
        }
    }

    boolean isTransient(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof DataAccessException
                    || current instanceof RedisSystemException
                    || current instanceof AmqpException) {
                return true;
            }
            if (current instanceof AppException appException) {
                return appException.getErrorCode() == ApiErrorCode.SERVICE_UNAVAILABLE;
            }
            current = current.getCause();
        }
        return true;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
