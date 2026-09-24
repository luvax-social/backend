package com.app.modules.notification.messaging;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
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
import com.app.common.messaging.DeadLetterPublisher;
import com.app.common.messaging.DomainEventMessageParser;
import com.app.common.messaging.config.ConsumerRetryProperties;
import com.app.common.messaging.exception.PermanentMessageException;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.service.NotificationService;
import com.app.modules.social.enums.FollowStatus;
import com.app.modules.social.messaging.SocialEventTypes;
import com.app.modules.social.service.SocialService;
import com.app.modules.support.messaging.SupportEventTypes;
import com.rabbitmq.client.Channel;

/**
 * RabbitMQ consumer for {@code notification.queue}: the social-graph and identity events that
 * change the activity feed.
 *
 * <p>A follow or a follow request writes a notification; an unfollow, a cancelled or rejected
 * request, and a block withdraw one; an approved request converts the request into a follow in
 * place; a verification grant or revocation rewrites the verified-actor flag. Every branch acts on
 * the relationship as it stands when the event is processed, read through {@link SocialService},
 * rather than as the event described it, so a redelivered or reordered event cannot leave a
 * notification describing a relationship that no longer exists.
 *
 * <p>Uses manual acknowledgement: ack after idempotent duplicate detection or successful side
 * effect, route poison messages to DLQ, and nack with requeue if DLQ publishing itself fails.
 */
@Component
@ConditionalOnProperty(
        prefix = "app.notification.consumer",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class SocialNotificationConsumer {

    static final String CONSUMER_NAME = "social-notification-consumer";

    static final Set<String> HANDLED_EVENT_TYPES =
            Set.of(
                    SocialEventTypes.USER_FOLLOWED_V1,
                    SocialEventTypes.USER_FOLLOW_REQUESTED_V1,
                    SocialEventTypes.USER_UNFOLLOWED_V1,
                    SocialEventTypes.USER_FOLLOW_REQUEST_APPROVED_V1,
                    SocialEventTypes.USER_FOLLOW_REQUEST_REJECTED_V1,
                    SocialEventTypes.USER_BLOCKED_V1,
                    SupportEventTypes.USER_VERIFICATION_CHANGED_V1);

    private static final Logger log = LoggerFactory.getLogger(SocialNotificationConsumer.class);

    private final DomainEventMessageParser parser;
    private final ProcessedMessageService processedMessageService;
    private final NotificationService notificationService;
    private final ConsumerRetryProperties retryProperties;
    private final DeadLetterPublisher deadLetterPublisher;
    private final SocialService socialService;
    private final Sleeper sleeper;

    @Autowired
    public SocialNotificationConsumer(
            DomainEventMessageParser parser,
            ProcessedMessageService processedMessageService,
            NotificationService notificationService,
            ConsumerRetryProperties retryProperties,
            DeadLetterPublisher deadLetterPublisher,
            SocialService socialService) {
        this(
                parser,
                processedMessageService,
                notificationService,
                retryProperties,
                deadLetterPublisher,
                socialService,
                Thread::sleep);
    }

    SocialNotificationConsumer(
            DomainEventMessageParser parser,
            ProcessedMessageService processedMessageService,
            NotificationService notificationService,
            ConsumerRetryProperties retryProperties,
            DeadLetterPublisher deadLetterPublisher,
            SocialService socialService,
            Sleeper sleeper) {
        this.parser = parser;
        this.processedMessageService = processedMessageService;
        this.notificationService = notificationService;
        this.retryProperties = retryProperties;
        this.deadLetterPublisher = deadLetterPublisher;
        this.socialService = socialService;
        this.sleeper = sleeper;
    }

    @RabbitListener(queues = RabbitMqTopologyConfig.NOTIFICATION_QUEUE)
    public void consume(Message message, Channel channel) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            DomainEventEnvelope event = parser.parse(message);
            validateEnvelope(event);
            processWithRetry(event);
            ack(channel, deliveryTag);
        } catch (PermanentMessageException ex) {
            routeToDlqOrRequeue(message, channel, deliveryTag, ex);
        } catch (RuntimeException ex) {
            routeToDlqOrRequeue(message, channel, deliveryTag, ex);
        }
    }

    private void processWithRetry(DomainEventEnvelope event) {
        if (!HANDLED_EVENT_TYPES.contains(event.eventType())) {
            return;
        }
        int maxAttempts = retryProperties.resolvedMaxAttempts();
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                processedMessageService.processOnce(
                        CONSUMER_NAME, event.eventId(), event.eventType(), () -> dispatch(event));
                return;
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

    private void validateEnvelope(DomainEventEnvelope event) {
        if (event == null || event.eventId() == null) {
            throw new PermanentMessageException("Event envelope or id is null");
        }
        if (event.eventType() == null || event.eventType().isBlank()) {
            throw new PermanentMessageException("Event type is missing");
        }
        if (event.aggregateId() == null) {
            throw new PermanentMessageException("Aggregate id (recipient) is missing");
        }
    }

    void dispatch(DomainEventEnvelope event) {
        Map<String, Object> data = event.data() == null ? Map.of() : event.data();
        switch (event.eventType()) {
            case SocialEventTypes.USER_FOLLOWED_V1 -> {
                UUID follower = event.actorId();
                UUID following = event.aggregateId();
                if (hasEdge(follower, following, FollowStatus.ACCEPTED)) {
                    notificationService.create(
                            follower, following, NotificationType.FOLLOW, null, null, null);
                }
            }
            case SocialEventTypes.USER_FOLLOW_REQUESTED_V1 -> {
                UUID requester = event.actorId();
                UUID target = event.aggregateId();
                if (hasEdge(requester, target, FollowStatus.PENDING)) {
                    notificationService.create(
                            requester, target, NotificationType.FOLLOW_REQUEST, null, null, null);
                }
            }
            case SocialEventTypes.USER_UNFOLLOWED_V1 -> {
                UUID follower = uuid(data.get("followerId"));
                UUID following = uuid(data.get("followingId"));
                if (socialService.findFollowStatus(follower, following).isEmpty()) {
                    notificationService.retract(follower, following, NotificationType.FOLLOW, null);
                }
            }
            case SocialEventTypes.USER_FOLLOW_REQUEST_APPROVED_V1 -> {
                UUID requester = uuid(data.get("requesterId"));
                UUID approver = uuid(data.get("approverId"));
                if (hasEdge(requester, approver, FollowStatus.ACCEPTED)) {
                    notificationService.resolveFollowRequest(requester, approver, true);
                }
            }
            case SocialEventTypes.USER_FOLLOW_REQUEST_REJECTED_V1 -> {
                UUID requester = uuid(data.get("requesterId"));
                UUID approver = uuid(data.get("approverId"));
                if (socialService.findFollowStatus(requester, approver).isEmpty()) {
                    notificationService.resolveFollowRequest(requester, approver, false);
                }
            }
            case SocialEventTypes.USER_BLOCKED_V1 -> {
                UUID blocker = uuid(data.get("blockerId"));
                UUID blocked = uuid(data.get("blockedId"));
                if (socialService.isBlockedBetween(blocker, blocked)) {
                    notificationService.onBlock(blocker, blocked);
                }
            }
            case SupportEventTypes.USER_VERIFICATION_CHANGED_V1 ->
                    notificationService.resyncActorVerified(event.aggregateId());
            default -> {
                // Filtered out by HANDLED_EVENT_TYPES before the inbox record is written.
            }
        }
    }

    private boolean hasEdge(UUID follower, UUID following, FollowStatus status) {
        return socialService
                .findFollowStatus(follower, following)
                .filter(status::equals)
                .isPresent();
    }

    private static UUID uuid(Object value) {
        if (value == null) {
            throw new PermanentMessageException("Event data is missing a user id");
        }
        return UUID.fromString(value.toString());
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
            throw new RuntimeException("Interrupted during social notification retry backoff", ex);
        }
    }

    private void routeToDlqOrRequeue(
            Message message, Channel channel, long deliveryTag, RuntimeException failure) {
        try {
            deadLetterPublisher.publish(
                    message,
                    RabbitMqTopologyConfig.NOTIFICATION_DEAD_LETTER_ROUTING_KEY,
                    failure.getMessage());
            ack(channel, deliveryTag);
        } catch (RuntimeException dlqFailure) {
            log.warn(
                    "Failed to publish social notification event to DLQ; requeueing: {}",
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
            log.error("Failed to ack social notification message: {}", ex.getMessage());
        }
    }

    private void nack(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (IOException ex) {
            log.error("Failed to nack social notification message: {}", ex.getMessage());
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

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
