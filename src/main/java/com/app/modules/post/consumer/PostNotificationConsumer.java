package com.app.modules.post.consumer;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

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
import com.app.common.inbox.service.ProcessedMessageService;
import com.app.common.messaging.DomainEventMessageParser;
import com.app.common.messaging.config.ConsumerRetryProperties;
import com.app.common.messaging.exception.PermanentMessageException;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.service.NotificationService;
import com.app.modules.post.entity.PostLikeId;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.repository.PostLikeRepository;
import com.rabbitmq.client.Channel;

/**
 * RabbitMQ consumer that turns post likes into like notifications and withdraws them on unlike.
 *
 * <p>The single notification producer for the post module, following the comment module's consumer.
 * {@code NotificationService} aggregates the likes on one post into one group and applies the self,
 * block and toggle guards, so none are repeated here.
 *
 * <p>Both branches act on the like as it stands when the event is processed rather than as the
 * event described it. A like withdrawn before its event was consumed writes nothing, and an unlike
 * whose liker has since liked again retracts nothing, so a redelivered or reordered pair converges
 * on the state of {@code post_likes}.
 *
 * <p>Uses manual acknowledgement with bounded retry; a permanent or retry-exhausted failure nacks
 * without requeue so the broker routes the message to {@code post.notification.dlq}.
 */
@Component
@ConditionalOnProperty(
        prefix = "app.post.notification-consumer",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class PostNotificationConsumer {

    public static final String CONSUMER_NAME = "post-notification-consumer";

    private static final Logger log = LoggerFactory.getLogger(PostNotificationConsumer.class);
    private static final String ENTITY_TYPE = "post";

    private final DomainEventMessageParser parser;
    private final ProcessedMessageService processedMessageService;
    private final NotificationService notificationService;
    private final ConsumerRetryProperties retryProperties;
    private final PostLikeRepository postLikeRepository;
    private final Sleeper sleeper;

    @Autowired
    public PostNotificationConsumer(
            DomainEventMessageParser parser,
            ProcessedMessageService processedMessageService,
            NotificationService notificationService,
            ConsumerRetryProperties retryProperties,
            PostLikeRepository postLikeRepository) {
        this(
                parser,
                processedMessageService,
                notificationService,
                retryProperties,
                postLikeRepository,
                Thread::sleep);
    }

    PostNotificationConsumer(
            DomainEventMessageParser parser,
            ProcessedMessageService processedMessageService,
            NotificationService notificationService,
            ConsumerRetryProperties retryProperties,
            PostLikeRepository postLikeRepository,
            Sleeper sleeper) {
        this.parser = parser;
        this.processedMessageService = processedMessageService;
        this.notificationService = notificationService;
        this.retryProperties = retryProperties;
        this.postLikeRepository = postLikeRepository;
        this.sleeper = sleeper;
    }

    @RabbitListener(queues = RabbitMqTopologyConfig.POST_NOTIFICATION_QUEUE)
    public void consume(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            DomainEventEnvelope event = parser.parse(message);
            validateEnvelope(event);
            processWithRetry(event);
            channel.basicAck(deliveryTag, false);
        } catch (PermanentMessageException ex) {
            log.warn(
                    "Post notification event permanently invalid, dead-lettering: {}",
                    ex.getMessage());
            channel.basicNack(deliveryTag, false, false);
        } catch (RuntimeException ex) {
            log.warn(
                    "Post notification event failed after exhausting retries, dead-lettering: {}",
                    ex.getMessage());
            channel.basicNack(deliveryTag, false, false);
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

    void dispatch(DomainEventEnvelope event) {
        Map<String, Object> data = event.data();
        UUID postId = uuid(data.get("postId"));
        UUID ownerId = uuid(data.get("postOwnerId"));
        UUID likerId = uuid(data.get("userId"));
        boolean liked = postLikeRepository.existsById(new PostLikeId(likerId, postId));
        switch (event.eventType()) {
            case PostEventTypes.POST_LIKED_V1 -> {
                if (liked) {
                    notificationService.create(
                            likerId,
                            ownerId,
                            NotificationType.LIKE_POST,
                            ENTITY_TYPE,
                            postId,
                            postId);
                }
            }
            case PostEventTypes.POST_UNLIKED_V1 -> {
                if (!liked) {
                    notificationService.retract(
                            likerId, ownerId, NotificationType.LIKE_POST, postId);
                }
            }
            default -> {
                // Not a notification-bearing event; ignore.
            }
        }
    }

    private void validateEnvelope(DomainEventEnvelope event) {
        if (event == null || event.eventId() == null) {
            throw new PermanentMessageException("Event envelope or id is null");
        }
        if (event.eventType() == null || event.eventType().isBlank()) {
            throw new PermanentMessageException("Event type is missing");
        }
        Map<String, Object> data = event.data();
        if (data == null
                || data.get("postId") == null
                || data.get("postOwnerId") == null
                || data.get("userId") == null) {
            throw new PermanentMessageException("Post, owner or liker id is missing");
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
            throw new RuntimeException("Interrupted during post notification retry backoff", ex);
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

    private static boolean isTransient(Throwable ex) {
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

    private static UUID uuid(Object value) {
        return value == null ? null : UUID.fromString(value.toString());
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
