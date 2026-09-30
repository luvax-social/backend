package com.app.modules.recommendation.consumer;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import com.app.common.analytics.ClickHouseException;
import com.app.common.analytics.ClickHouseUnavailableException;
import com.app.common.analytics.ingest.AnalyticsListenerIds;
import com.app.common.config.rabbit.RabbitMqTopologyConfig;
import com.app.common.inbox.service.ProcessedMessageService;
import com.app.common.messaging.DomainEventMessageParser;
import com.app.common.messaging.config.ConsumerRetryProperties;
import com.app.common.messaging.exception.PermanentMessageException;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.modules.comment.messaging.CommentEventTypes;
import com.app.modules.message.messaging.MessageEventTypes;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.recommendation.client.GorseClient;
import com.app.modules.recommendation.client.dto.GorseFeedback;
import com.app.modules.recommendation.enums.UserEventType;
import com.app.modules.recommendation.repository.UserEventAnalyticsRepository;
import com.app.modules.users.service.UserSummaryService;
import com.rabbitmq.client.Channel;

/**
 * RabbitMQ consumer that turns engagement events into recommender feedback.
 *
 * <p>Each event is recorded in the canonical append-only {@code user_events} store in ClickHouse
 * and then pushed to Gorse, in that order, so Gorse never holds feedback that has no durable
 * record. The row carries the Gorse feedback type and value the event is sent with, so a rebuild of
 * the recommender reproduces exactly what this pipeline accumulated. Both writes are idempotent
 * (deterministic event id folded by the table engine, Gorse feedback upsert), so redeliveries and
 * retries after a partial failure are safe.
 *
 * <p>Uses manual acknowledgement: ack after idempotent duplicate detection or successful side
 * effect. A permanently failing message is nacked without requeue; {@code
 * recommendation.feedback.queue} declares {@code x-dead-letter-exchange} / {@code
 * x-dead-letter-routing-key} in {@link RabbitMqTopologyConfig}, so the broker itself routes the
 * rejected message to {@code recommendation.feedback.dlq} - no application-level DLQ publish is
 * needed, and none is attempted, so a poison message cannot loop back onto this queue.
 *
 * <p>An unavailable ClickHouse is the one failure that is neither retried in process nor
 * dead-lettered: the message is returned to the queue and the ingestion controller stops this
 * listener once the breaker opens, so the backlog waits in RabbitMQ until ClickHouse is back. Gorse
 * failures keep the retry-then-dead-letter path.
 *
 * <p>The listener starts stopped and is started by the ingestion controller once the ClickHouse
 * schema is ready, which is also what lets a Gorse rebuild suspend it.
 */
@Component
@ConditionalOnProperty(
        prefix = "app.recommendation.consumer",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class RecommendationFeedbackConsumer {

    static final String CONSUMER_NAME = "recommendation-feedback-consumer";

    // Binary signals carry no magnitude of their own; a like is a like.
    private static final double UNIT_FEEDBACK_VALUE = 1.0;

    // A comment like is a user-to-comment relation, but Gorse's item space is posts, so the only
    // usable mapping attributes it to the comment's parent post. That is a deliberate reduction of
    // the signal, not the raw signal, so it is weighted below a direct post like. It reuses the
    // like feedback type rather than introducing its own, because a new type would need its own
    // entry in positive_feedback_types and would dilute the bucket the model trains on.
    private static final double COMMENT_LIKE_FEEDBACK_VALUE = 0.5;

    private static final Logger log = LoggerFactory.getLogger(RecommendationFeedbackConsumer.class);

    private final DomainEventMessageParser parser;
    private final ProcessedMessageService processedMessageService;
    private final ConsumerRetryProperties retryProperties;
    private final UserEventAnalyticsRepository userEventAnalyticsRepository;
    private final UserSummaryService userSummaryService;
    private final GorseClient gorseClient;
    private final Sleeper sleeper;

    @Autowired
    public RecommendationFeedbackConsumer(
            DomainEventMessageParser parser,
            ProcessedMessageService processedMessageService,
            ConsumerRetryProperties retryProperties,
            UserEventAnalyticsRepository userEventAnalyticsRepository,
            UserSummaryService userSummaryService,
            GorseClient gorseClient) {
        this(
                parser,
                processedMessageService,
                retryProperties,
                userEventAnalyticsRepository,
                userSummaryService,
                gorseClient,
                Thread::sleep);
    }

    RecommendationFeedbackConsumer(
            DomainEventMessageParser parser,
            ProcessedMessageService processedMessageService,
            ConsumerRetryProperties retryProperties,
            UserEventAnalyticsRepository userEventAnalyticsRepository,
            UserSummaryService userSummaryService,
            GorseClient gorseClient,
            Sleeper sleeper) {
        this.parser = parser;
        this.processedMessageService = processedMessageService;
        this.retryProperties = retryProperties;
        this.userEventAnalyticsRepository = userEventAnalyticsRepository;
        this.userSummaryService = userSummaryService;
        this.gorseClient = gorseClient;
        this.sleeper = sleeper;
    }

    /** Mapping from a domain event type to its user_events type and Gorse feedback type. */
    private record FeedbackMapping(UserEventType userEventType, String gorseFeedbackType) {}

    /**
     * Consumes an engagement event and applies it idempotently to ClickHouse and Gorse.
     *
     * @param message delivered RabbitMQ message carrying the domain event envelope
     * @param channel channel used for manual acknowledgement
     */
    // Four consumer threads because a write that waits for the part to be flushed and synced takes
    // tens of milliseconds; ordering does not matter to an append-only table or to the sums Gorse
    // accumulates, and the inbox's unique key serialises a duplicate across threads.
    @RabbitListener(
            id = AnalyticsListenerIds.RECOMMENDATION_FEEDBACK,
            queues = RabbitMqTopologyConfig.RECOMMENDATION_FEEDBACK_QUEUE,
            autoStartup = "false",
            concurrency = "${app.recommendation.consumer.concurrency:4}")
    public void consume(Message message, Channel channel) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            DomainEventEnvelope event = parser.parse(message);
            validateEnvelope(event);
            processWithRetry(event);
            ack(channel, deliveryTag);
        } catch (ClickHouseUnavailableException ex) {
            // Nothing was written and the inbox marker rolled back with the handler's transaction.
            log.warn(
                    "Recommendation feedback paused, message returned to the queue: {}",
                    ex.getMessage());
            nack(channel, deliveryTag, true);
        } catch (RuntimeException ex) {
            log.warn(
                    "Recommendation feedback event permanently failed, routing to DLQ via broker"
                            + " dead-letter binding: {}",
                    ex.getMessage());
            nack(channel, deliveryTag, false);
        }
    }

    private void processWithRetry(DomainEventEnvelope event) {
        int maxAttempts = retryProperties.resolvedMaxAttempts();
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                processedMessageService.processOnce(
                        CONSUMER_NAME, event.eventId(), event.eventType(), () -> apply(event));
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

    private void apply(DomainEventEnvelope event) {
        FeedbackMapping mapping = mapEventType(event.eventType());
        UUID postId = extractPostId(event);
        UUID actorId = event.actorId();
        if (actorId == null) {
            throw new PermanentMessageException(
                    "Engagement event has no actor: " + event.eventId());
        }
        // ClickHouse has no foreign keys, so the reference the user_events constraint used to check
        // is checked here. An actor that no longer exists is a permanent condition that retrying
        // can never resolve, so it dead-letters once instead of exhausting the retry budget on
        // every redelivery.
        if (!userSummaryService.exists(actorId)) {
            throw new PermanentMessageException(
                    "Engagement event references a user that no longer exists: " + event.eventId());
        }
        double value = feedbackValue(event);
        userEventAnalyticsRepository.insertEngagement(
                event.eventId(),
                actorId,
                mapping.userEventType(),
                "post",
                postId,
                event.occurredAt(),
                mapping.gorseFeedbackType(),
                value);
        gorseClient.insertFeedback(
                List.of(
                        new GorseFeedback(
                                mapping.gorseFeedbackType(),
                                actorId.toString(),
                                postId.toString(),
                                event.occurredAt(),
                                value)));
    }

    // An impression carries its dwell so a future positive_feedback_types threshold can promote a
    // long dwell to positive feedback without a code change. A view recorded by the single-post
    // view endpoint, and any message enqueued before dwell existed, carries no dwell key and falls
    // back to the unit value rather than being treated as a zero-second read.
    private static double feedbackValue(DomainEventEnvelope event) {
        if (CommentEventTypes.COMMENT_LIKED_V1.equals(event.eventType())) {
            return COMMENT_LIKE_FEEDBACK_VALUE;
        }
        Object raw = event.data() == null ? null : event.data().get("dwellSeconds");
        if (raw instanceof Number dwell) {
            return dwell.doubleValue();
        }
        return UNIT_FEEDBACK_VALUE;
    }

    private static FeedbackMapping mapEventType(String eventType) {
        return switch (eventType) {
            case PostEventTypes.POST_LIKED_V1 ->
                    new FeedbackMapping(UserEventType.POST_LIKE, "like");
            case PostEventTypes.POST_SAVED_V1 ->
                    new FeedbackMapping(UserEventType.POST_SAVE, "save");
            case PostEventTypes.POST_VIEWED_V1 ->
                    new FeedbackMapping(UserEventType.POST_VIEW, "read");
            case CommentEventTypes.COMMENT_CREATED_V1 ->
                    new FeedbackMapping(UserEventType.POST_COMMENT, "comment");
            case CommentEventTypes.COMMENT_LIKED_V1 ->
                    new FeedbackMapping(UserEventType.COMMENT_LIKE, "like");
            case MessageEventTypes.POST_SHARED_V1 ->
                    new FeedbackMapping(UserEventType.POST_SHARE, "share");
            default -> throw new PermanentMessageException("unknown event type: " + eventType);
        };
    }

    private static UUID extractPostId(DomainEventEnvelope event) {
        Object raw = event.data() == null ? null : event.data().get("postId");
        if (raw == null) {
            throw new PermanentMessageException(
                    "Engagement event has no postId: " + event.eventId());
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException ex) {
            throw new PermanentMessageException("Engagement event postId is not a UUID: " + raw);
        }
    }

    private void validateEnvelope(DomainEventEnvelope event) {
        if (event == null || event.eventId() == null) {
            throw new PermanentMessageException("Event envelope or id is null");
        }
        if (event.eventType() == null || event.eventType().isBlank()) {
            throw new PermanentMessageException("Event type is missing");
        }
        if (event.occurredAt() == null) {
            throw new PermanentMessageException("Event occurredAt is missing");
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
            throw new RuntimeException(
                    "Interrupted during recommendation feedback retry backoff", ex);
        }
    }

    // channel.basicAck/basicNack declare IOException on a broken/closed AMQP channel; the
    // listener container's own recovery handles that case, so we log and return rather than
    // letting a checked IOException escape this @RabbitListener method uncaught.
    private void ack(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException ex) {
            log.error("Failed to ack recommendation feedback message: {}", ex.getMessage());
        }
    }

    // requeue=false: the broker routes the rejection to the queue's configured dead-letter
    // exchange instead of redelivering to this same queue, so a permanently failing message
    // cannot loop. requeue=true is used only while ClickHouse is unavailable, when the ingestion
    // controller is about to stop this listener and the message must wait in the queue.
    private void nack(Channel channel, long deliveryTag, boolean requeue) {
        try {
            channel.basicNack(deliveryTag, false, requeue);
        } catch (IOException ex) {
            log.error("Failed to nack recommendation feedback message: {}", ex.getMessage());
        }
    }

    private static boolean isPermanent(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof PermanentMessageException) {
                return true;
            }
        }
        return false;
    }

    // Default-transient like the other consumers, with two additions: a Gorse 4xx response means
    // the request itself is wrong and will never succeed, so it must dead-letter immediately, and a
    // ClickHouse failure is never retried in process - an outage requeues the message and pauses
    // the listener, and a rejected request dead-letters at once.
    boolean isTransient(Throwable ex) {
        if (ex instanceof ClickHouseException) {
            return false;
        }
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof RestClientResponseException response) {
                return response.getStatusCode().is5xxServerError();
            }
            if (current instanceof DataAccessException || current instanceof AmqpException) {
                return true;
            }
        }
        return true;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
