package com.app.modules.notification.messaging;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.stereotype.Component;

import com.app.common.config.rabbit.RabbitMqTopologyConfig;
import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.inbox.service.ProcessedMessageService;
import com.app.common.messaging.DeadLetterPublisher;
import com.app.common.messaging.DomainEventMessageParser;
import com.app.common.messaging.config.ConsumerRetryProperties;
import com.app.common.messaging.exception.PermanentMessageException;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.modules.admin.messaging.AdminEventTypes;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.service.NotificationDraft;
import com.app.modules.notification.service.NotificationService;
import com.rabbitmq.client.Channel;

/**
 * RabbitMQ consumer for moderation events that the affected account must be told about.
 *
 * <p>The notification is created with a null actor on purpose. A warning comes from the platform,
 * not from a person: naming the moderator would invite retaliation, and, more concretely, an actor
 * would run the notification service's block guard, so an account that had blocked the moderator
 * would never be told it had been warned. A null actor also means the type is never suppressed by a
 * user setting, which is correct for a message the account is required to receive.
 *
 * <p>Uses manual acknowledgement: ack after idempotent duplicate detection or a successful side
 * effect, route poison messages to the DLQ, and nack with requeue if DLQ publishing itself fails.
 */
@Component
@ConditionalOnProperty(
        prefix = "app.admin.consumer",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class AdminNotificationConsumer {

    static final String CONSUMER_NAME = "admin-notification-consumer";

    private static final String ENTITY_TYPE = "warning";

    private static final Logger log = LoggerFactory.getLogger(AdminNotificationConsumer.class);

    private final DomainEventMessageParser parser;
    private final ProcessedMessageService processedMessageService;
    private final NotificationService notificationService;
    private final ConsumerRetryProperties retryProperties;
    private final DeadLetterPublisher deadLetterPublisher;

    public AdminNotificationConsumer(
            DomainEventMessageParser parser,
            ProcessedMessageService processedMessageService,
            NotificationService notificationService,
            ConsumerRetryProperties retryProperties,
            DeadLetterPublisher deadLetterPublisher) {
        this.parser = parser;
        this.processedMessageService = processedMessageService;
        this.notificationService = notificationService;
        this.retryProperties = retryProperties;
        this.deadLetterPublisher = deadLetterPublisher;
    }

    @RabbitListener(queues = RabbitMqTopologyConfig.ADMIN_NOTIFICATION_QUEUE)
    public void consume(Message message, Channel channel) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            DomainEventEnvelope event = parser.parse(message);
            validateEnvelope(event);
            processWithRetry(event);
            ack(channel, deliveryTag);
        } catch (RuntimeException ex) {
            routeToDlqOrRequeue(message, channel, deliveryTag, ex);
        }
    }

    private void processWithRetry(DomainEventEnvelope event) {
        int maxAttempts = retryProperties.resolvedMaxAttempts();
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                processedMessageService.processOnce(
                        CONSUMER_NAME, event.eventId(), event.eventType(), () -> dispatch(event));
                return;
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

    private void dispatch(DomainEventEnvelope event) {
        if (!AdminEventTypes.USER_WARNED_V1.equals(event.eventType())) {
            return;
        }
        Map<String, Object> data = event.data();
        // adminActionId links the notice to its audit row, which carries the reason and the
        // appeal route. An event enqueued before the field existed leaves it null; the notice
        // is still written and simply offers no appeal.
        notificationService.create(
                NotificationDraft.systemNotice(
                        uuid(data.get("userId")),
                        NotificationType.WARNING,
                        ENTITY_TYPE,
                        uuid(data.get("warningId")),
                        null,
                        null,
                        uuid(data.get("adminActionId"))));
    }

    private void validateEnvelope(DomainEventEnvelope event) {
        if (event == null || event.eventId() == null) {
            throw new PermanentMessageException("Event envelope or id is null");
        }
        if (event.eventType() == null || event.eventType().isBlank()) {
            throw new PermanentMessageException("Event type is missing");
        }
        if (event.data() == null
                || event.data().get("userId") == null
                || event.data().get("warningId") == null) {
            throw new PermanentMessageException("Warned account or warning id is missing");
        }
    }

    private static UUID uuid(Object value) {
        return value == null ? null : UUID.fromString(value.toString());
    }

    private void sleepBeforeRetry(int attempt) {
        Duration backoff = retryProperties.retryBackoffForAttempt(attempt);
        if (backoff.isZero()) {
            return;
        }
        try {
            Thread.sleep(backoff.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during admin notification retry backoff", ex);
        }
    }

    private void routeToDlqOrRequeue(
            Message message, Channel channel, long deliveryTag, RuntimeException failure) {
        try {
            deadLetterPublisher.publish(
                    message,
                    RabbitMqTopologyConfig.ADMIN_NOTIFICATION_DEAD_LETTER_ROUTING_KEY,
                    failure.getMessage());
            ack(channel, deliveryTag);
        } catch (RuntimeException dlqFailure) {
            log.warn(
                    "Failed to publish admin notification event to DLQ; requeueing: {}",
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
            log.error("Failed to ack admin notification message: {}", ex.getMessage());
        }
    }

    private void nack(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (IOException ex) {
            log.error("Failed to nack admin notification message: {}", ex.getMessage());
        }
    }

    private static boolean isPermanent(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof PermanentMessageException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
}
