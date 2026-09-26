package com.app.modules.auth.messaging;

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
import com.app.modules.mail.util.MailSuppression;
import com.rabbitmq.client.Channel;

/**
 * RabbitMQ consumer for auth mail side effects.
 *
 * <p>Uses manual acknowledgement: ack after idempotent duplicate detection or successful side
 * effect, route poison messages to DLQ, and nack with requeue if DLQ publishing itself fails.
 */
@Component
@ConditionalOnProperty(
        prefix = "app.mail.consumer",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class AuthMailEventConsumer {

    static final String CONSUMER_NAME = "auth-mail-consumer";

    private static final Logger log = LoggerFactory.getLogger(AuthMailEventConsumer.class);

    private final DomainEventMessageParser parser;
    private final ProcessedMessageService processedMessageService;
    private final AuthMailEventHandler handler;
    private final ConsumerRetryProperties retryProperties;
    private final DeadLetterPublisher deadLetterPublisher;
    private final Sleeper sleeper;

    @Autowired
    public AuthMailEventConsumer(
            DomainEventMessageParser parser,
            ProcessedMessageService processedMessageService,
            AuthMailEventHandler handler,
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

    AuthMailEventConsumer(
            DomainEventMessageParser parser,
            ProcessedMessageService processedMessageService,
            AuthMailEventHandler handler,
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

    @RabbitListener(queues = RabbitMqTopologyConfig.MAIL_QUEUE)
    public void consume(Message message, Channel channel) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            DomainEventEnvelope event = parser.parse(message);
            validateEnvelopeIdentity(event);
            ProcessedMessageResult result = processWithRetry(event);
            ack(channel, deliveryTag);
            if (result == ProcessedMessageResult.DUPLICATE) {
                log.info("Skipped duplicate auth mail event {}", event.eventId());
            }
        } catch (PermanentMessageException ex) {
            routeToDlqOrRequeue(message, channel, deliveryTag, ex);
        } catch (RuntimeException ex) {
            if (MailSuppression.isRecipientSuppressed(ex)) {
                // A configuration decision, not a failure, so it is acked rather than
                // dead-lettered. Dead-lettering it fills the DLQ with choices the operator already
                // made: on any machine running the dev allowlist that is every verification,
                // password reset and email-change message addressed to a real domain. The
                // moderation lane has always treated this as SKIPPED; this is the same decision,
                // taken in the same place.
                log.info("Suppressed auth mail event: recipient outside the configured allowlist");
                ack(channel, deliveryTag);
                return;
            }
            routeToDlqOrRequeue(message, channel, deliveryTag, ex);
        }
    }

    private ProcessedMessageResult processWithRetry(DomainEventEnvelope event) {
        AuthMailEventHandler.AuthMailEventProcessingContext context =
                new AuthMailEventHandler.AuthMailEventProcessingContext();
        int maxAttempts = retryProperties.resolvedMaxAttempts();
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return processedMessageService.processOnce(
                        CONSUMER_NAME,
                        event.eventId(),
                        event.eventType(),
                        () -> handler.handle(event, context));
            } catch (RuntimeException ex) {
                if (isPermanent(ex)) {
                    throw ex;
                }
                if (!isTransient(ex)) {
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
            throw new RuntimeException("Interrupted during auth mail retry backoff", ex);
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
                    RabbitMqTopologyConfig.MAIL_DEAD_LETTER_ROUTING_KEY,
                    failure.getMessage());
            ack(channel, deliveryTag);
        } catch (RuntimeException dlqFailure) {
            log.warn(
                    "Failed to publish auth mail event to DLQ; requeueing original message: {}",
                    dlqFailure.getMessage());
            nack(channel, deliveryTag);
        }
    }

    // channel.basicAck/basicNack declare IOException on a broken/closed AMQP channel; the
    // listener container's own recovery handles that case, so we log and return rather than
    // letting a checked IOException escape this @RabbitListener method uncaught.
    private void ack(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException ex) {
            log.error("Failed to ack auth mail message: {}", ex.getMessage());
        }
    }

    private void nack(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (IOException ex) {
            log.error("Failed to nack auth mail message: {}", ex.getMessage());
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
