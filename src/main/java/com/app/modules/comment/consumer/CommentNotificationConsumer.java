package com.app.modules.comment.consumer;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import com.app.common.messaging.DomainEventMessageParser;
import com.app.common.messaging.config.ConsumerRetryProperties;
import com.app.common.messaging.exception.PermanentMessageException;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.modules.comment.messaging.CommentEventTypes;
import com.app.modules.comment.repository.CommentLikeRepository;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.service.NotificationService;
import com.rabbitmq.client.Channel;

/**
 * RabbitMQ consumer that creates comment, reply, mention, and like notifications, and withdraws a
 * like notification's actor on unlike.
 *
 * <p>The single notification producer for the comment module. Recipients are resolved from the
 * event {@code data} payload, not from the aggregate id. {@code NotificationService.create} already
 * suppresses self-notifications, blocked actors, and toggled-off preferences, so no extra guards
 * are applied here. Uses manual acknowledgement with bounded retry; a permanent or retry-exhausted
 * failure nacks without requeue so the broker routes the message to the dead-letter queue per the
 * topology's declared policy.
 */
@Component
@ConditionalOnProperty(
        prefix = "app.comment.consumer",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class CommentNotificationConsumer {

    static final String CONSUMER_NAME = "comment-notification-consumer";

    private static final Logger log = LoggerFactory.getLogger(CommentNotificationConsumer.class);
    private static final String ENTITY_TYPE = "comment";

    private final DomainEventMessageParser parser;
    private final ProcessedMessageService processedMessageService;
    private final NotificationService notificationService;
    private final ConsumerRetryProperties retryProperties;
    private final CommentLikeRepository commentLikeRepository;

    public CommentNotificationConsumer(
            DomainEventMessageParser parser,
            ProcessedMessageService processedMessageService,
            NotificationService notificationService,
            ConsumerRetryProperties retryProperties,
            CommentLikeRepository commentLikeRepository) {
        this.parser = parser;
        this.processedMessageService = processedMessageService;
        this.notificationService = notificationService;
        this.retryProperties = retryProperties;
        this.commentLikeRepository = commentLikeRepository;
    }

    @RabbitListener(queues = RabbitMqTopologyConfig.COMMENT_NOTIFICATION_QUEUE)
    public void consume(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            DomainEventEnvelope event = parser.parse(message);
            validateEnvelope(event);
            processWithRetry(event);
            channel.basicAck(deliveryTag, false);
        } catch (PermanentMessageException ex) {
            log.warn(
                    "Comment notification event permanently invalid, dead-lettering: {}",
                    ex.getMessage());
            channel.basicNack(deliveryTag, false, false);
        } catch (RuntimeException ex) {
            log.warn(
                    "Comment notification event failed after exhausting retries, dead-lettering: {}",
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

    private void dispatch(DomainEventEnvelope event) {
        Map<String, Object> data = event.data();
        UUID actor = event.actorId();
        UUID commentId = uuid(data.get("commentId"));
        UUID postId = uuid(data.get("postId"));
        switch (event.eventType()) {
            case CommentEventTypes.COMMENT_CREATED_V1 -> {
                int depth = ((Number) data.get("depth")).intValue();
                UUID primaryRecipient =
                        uuid(depth == 0 ? data.get("postOwnerId") : data.get("parentOwnerId"));
                boolean primaryDelivered =
                        primaryRecipient != null
                                && notificationService.create(
                                        actor,
                                        primaryRecipient,
                                        depth == 0
                                                ? NotificationType.COMMENT_POST
                                                : NotificationType.REPLY_COMMENT,
                                        ENTITY_TYPE,
                                        commentId,
                                        postId);
                // One notification per person per comment. The account the comment answers is
                // already told by the comment or reply notification, so a mention of them in
                // the same comment would say the same thing twice; a repeated handle is one
                // mention. A mention still goes out when that primary notification was
                // suppressed (by a toggle, for instance), so the account is told once.
                Set<UUID> mentioned = new LinkedHashSet<>();
                Object mentions = data.getOrDefault("mentionedUserIds", List.of());
                if (mentions instanceof List<?> ids) {
                    for (Object mid : ids) {
                        mentioned.add(uuid(mid));
                    }
                }
                if (primaryDelivered) {
                    mentioned.remove(primaryRecipient);
                }
                for (UUID recipient : mentioned) {
                    notificationService.create(
                            actor,
                            recipient,
                            NotificationType.MENTION_COMMENT,
                            ENTITY_TYPE,
                            commentId,
                            postId);
                }
            }
            // Both like branches act on the like as it stands when the event is processed, not as
            // the event described it, so a redelivered or reordered like/unlike pair converges:
            // a like already withdrawn writes nothing, and an unlike already followed by a
            // re-like retracts nothing.
            case CommentEventTypes.COMMENT_LIKED_V1 -> {
                if (commentLikeRepository.existsByIdUserIdAndIdCommentId(actor, commentId)) {
                    notificationService.create(
                            actor,
                            uuid(data.get("commentOwnerId")),
                            NotificationType.LIKE_COMMENT,
                            ENTITY_TYPE,
                            commentId,
                            postId);
                }
            }
            case CommentEventTypes.COMMENT_UNLIKED_V1 -> {
                if (!commentLikeRepository.existsByIdUserIdAndIdCommentId(actor, commentId)) {
                    notificationService.retract(
                            actor,
                            uuid(data.get("commentOwnerId")),
                            NotificationType.LIKE_COMMENT,
                            commentId);
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
        if (event.data() == null || event.data().get("commentId") == null) {
            throw new PermanentMessageException("Comment id is missing from event data");
        }
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
            throw new RuntimeException("Interrupted during comment notification retry backoff", ex);
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

    private boolean isTransient(Throwable ex) {
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
}
