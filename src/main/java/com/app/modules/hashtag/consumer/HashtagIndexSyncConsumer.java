package com.app.modules.hashtag.consumer;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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
import com.app.modules.hashtag.enums.HashtagStatus;
import com.app.modules.hashtag.event.HashtagIndexDeleteEvent;
import com.app.modules.hashtag.event.HashtagIndexUpsertEvent;
import com.app.modules.hashtag.messaging.HashtagEventTypes;
import com.app.modules.hashtag.repository.HashtagIndexProjection;
import com.app.modules.hashtag.repository.HashtagRepository;
import com.app.modules.hashtag.search.HashtagDocument;
import com.app.modules.hashtag.search.HashtagSearchRepository;
import com.rabbitmq.client.Channel;

import tools.jackson.databind.ObjectMapper;

/**
 * RabbitMQ consumer that applies hashtag index events to Elasticsearch.
 *
 * <p>Uses manual acknowledgement: ack after idempotent duplicate detection or successful side
 * effect, route poison messages to DLQ, and nack with requeue if DLQ publishing itself fails.
 */
@Component
@ConditionalOnProperty(
        prefix = "app.hashtag.consumer",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class HashtagIndexSyncConsumer {

    static final String CONSUMER_NAME = "hashtag-index-sync-consumer";

    private static final Logger log = LoggerFactory.getLogger(HashtagIndexSyncConsumer.class);

    private final DomainEventMessageParser parser;
    private final ProcessedMessageService processedMessageService;
    private final DeadLetterPublisher deadLetterPublisher;
    private final ConsumerRetryProperties retryProperties;
    private final HashtagSearchRepository hashtagSearchRepository;
    private final HashtagRepository hashtagRepository;
    private final ObjectMapper objectMapper;
    private final Sleeper sleeper;

    @Autowired
    public HashtagIndexSyncConsumer(
            DomainEventMessageParser parser,
            ProcessedMessageService processedMessageService,
            DeadLetterPublisher deadLetterPublisher,
            ConsumerRetryProperties retryProperties,
            HashtagSearchRepository hashtagSearchRepository,
            HashtagRepository hashtagRepository,
            ObjectMapper objectMapper) {
        this(
                parser,
                processedMessageService,
                deadLetterPublisher,
                retryProperties,
                hashtagSearchRepository,
                hashtagRepository,
                objectMapper,
                Thread::sleep);
    }

    HashtagIndexSyncConsumer(
            DomainEventMessageParser parser,
            ProcessedMessageService processedMessageService,
            DeadLetterPublisher deadLetterPublisher,
            ConsumerRetryProperties retryProperties,
            HashtagSearchRepository hashtagSearchRepository,
            HashtagRepository hashtagRepository,
            ObjectMapper objectMapper,
            Sleeper sleeper) {
        this.parser = parser;
        this.processedMessageService = processedMessageService;
        this.deadLetterPublisher = deadLetterPublisher;
        this.retryProperties = retryProperties;
        this.hashtagSearchRepository = hashtagSearchRepository;
        this.hashtagRepository = hashtagRepository;
        this.objectMapper = objectMapper;
        this.sleeper = sleeper;
    }

    /**
     * Consumes a hashtag index-sync event and applies it idempotently to Elasticsearch.
     *
     * @param message delivered RabbitMQ message carrying the domain event envelope
     * @param channel channel used for manual acknowledgement and DLQ routing
     */
    @RabbitListener(queues = RabbitMqTopologyConfig.HASHTAG_INDEX_SYNC_QUEUE)
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
                        CONSUMER_NAME, event.eventId(), event.eventType(), () -> apply(event));
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

    private void apply(DomainEventEnvelope event) {
        switch (event.eventType()) {
            case HashtagEventTypes.HASHTAG_INDEX_UPSERT_V1 -> {
                HashtagIndexUpsertEvent payload =
                        objectMapper.convertValue(event.data(), HashtagIndexUpsertEvent.class);
                UUID hashtagId = payload.hashtagId();
                Optional<HashtagIndexProjection> projection =
                        hashtagRepository.findIndexProjectionsByIdIn(List.of(hashtagId)).stream()
                                .findFirst();
                // Two gates, both read from the source of truth rather than from the envelope,
                // which carries only the id.
                //   post_count: only index a hashtag with live posts. A missing row or zero count
                //     means a concurrent delete won the race; drop any stale doc rather than
                //     resurrecting it from an out-of-order upsert.
                //   status: a banned or deleted hashtag belongs on no discovery surface, and
                //     Elasticsearch is the primary path behind hashtag search. Reading the status
                //     here is why banning needs no new event type: the same upsert event turns
                //     into a delete the moment the row says the tag is out of circulation.
                if (projection.isPresent()
                        && projection.get().getPostCount() > 0
                        && projection.get().getStatus() == HashtagStatus.ACTIVE) {
                    HashtagIndexProjection source = projection.get();
                    HashtagDocument document =
                            HashtagDocument.builder()
                                    .id(hashtagId.toString())
                                    .name(source.getName())
                                    .postCount(source.getPostCount())
                                    .createdAt(source.getCreatedAt())
                                    .build();
                    hashtagSearchRepository.save(document);
                } else {
                    hashtagSearchRepository.deleteById(hashtagId.toString());
                }
            }
            case HashtagEventTypes.HASHTAG_INDEX_DELETE_V1 -> {
                HashtagIndexDeleteEvent payload =
                        objectMapper.convertValue(event.data(), HashtagIndexDeleteEvent.class);
                hashtagSearchRepository.deleteById(payload.hashtagId().toString());
            }
            default ->
                    throw new PermanentMessageException("unknown event type: " + event.eventType());
        }
    }

    private void validateEnvelope(DomainEventEnvelope event) {
        if (event == null || event.eventId() == null) {
            throw new PermanentMessageException("Event envelope or id is null");
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
            throw new RuntimeException("Interrupted during hashtag index sync retry backoff", ex);
        }
    }

    private void routeToDlqOrRequeue(
            Message message, Channel channel, long deliveryTag, RuntimeException failure) {
        try {
            deadLetterPublisher.publish(
                    message,
                    RabbitMqTopologyConfig.HASHTAG_INDEX_DEAD_LETTER_ROUTING_KEY,
                    failure.getMessage());
            ack(channel, deliveryTag);
        } catch (RuntimeException dlqFailure) {
            log.warn(
                    "Failed to publish hashtag index sync event to DLQ; requeueing: {}",
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
            log.error("Failed to ack hashtag index sync message: {}", ex.getMessage());
        }
    }

    private void nack(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (IOException ex) {
            log.error("Failed to nack hashtag index sync message: {}", ex.getMessage());
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
