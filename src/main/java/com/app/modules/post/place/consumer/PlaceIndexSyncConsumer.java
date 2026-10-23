package com.app.modules.post.place.consumer;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

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
import com.app.modules.post.place.entity.Place;
import com.app.modules.post.place.enums.PlaceStatus;
import com.app.modules.post.place.event.PlaceIndexDeleteEvent;
import com.app.modules.post.place.event.PlaceIndexUpsertEvent;
import com.app.modules.post.place.messaging.PlaceEventTypes;
import com.app.modules.post.place.repository.PlaceRepository;
import com.app.modules.post.place.search.PlaceDocument;
import com.app.modules.post.place.search.PlaceSearchRepository;
import com.rabbitmq.client.Channel;

import tools.jackson.databind.ObjectMapper;

/**
 * RabbitMQ consumer that applies place index events to Elasticsearch.
 *
 * <p>Uses manual acknowledgement: ack after idempotent duplicate detection or successful side
 * effect, route poison messages to the DLQ, and nack with requeue if DLQ publishing itself fails.
 */
@Component
@ConditionalOnProperty(
        prefix = "app.place.consumer",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class PlaceIndexSyncConsumer {

    static final String CONSUMER_NAME = "place-index-sync-consumer";

    private static final Logger log = LoggerFactory.getLogger(PlaceIndexSyncConsumer.class);

    private final DomainEventMessageParser parser;
    private final ProcessedMessageService processedMessageService;
    private final DeadLetterPublisher deadLetterPublisher;
    private final ConsumerRetryProperties retryProperties;
    private final PlaceSearchRepository placeSearchRepository;
    private final PlaceRepository placeRepository;
    private final ObjectMapper objectMapper;
    private final Sleeper sleeper;

    @Autowired
    public PlaceIndexSyncConsumer(
            DomainEventMessageParser parser,
            ProcessedMessageService processedMessageService,
            DeadLetterPublisher deadLetterPublisher,
            ConsumerRetryProperties retryProperties,
            PlaceSearchRepository placeSearchRepository,
            PlaceRepository placeRepository,
            ObjectMapper objectMapper) {
        this(
                parser,
                processedMessageService,
                deadLetterPublisher,
                retryProperties,
                placeSearchRepository,
                placeRepository,
                objectMapper,
                Thread::sleep);
    }

    PlaceIndexSyncConsumer(
            DomainEventMessageParser parser,
            ProcessedMessageService processedMessageService,
            DeadLetterPublisher deadLetterPublisher,
            ConsumerRetryProperties retryProperties,
            PlaceSearchRepository placeSearchRepository,
            PlaceRepository placeRepository,
            ObjectMapper objectMapper,
            Sleeper sleeper) {
        this.parser = parser;
        this.processedMessageService = processedMessageService;
        this.deadLetterPublisher = deadLetterPublisher;
        this.retryProperties = retryProperties;
        this.placeSearchRepository = placeSearchRepository;
        this.placeRepository = placeRepository;
        this.objectMapper = objectMapper;
        this.sleeper = sleeper;
    }

    /**
     * Consumes a place index-sync event and applies it idempotently to Elasticsearch.
     *
     * @param message delivered RabbitMQ message carrying the domain event envelope
     * @param channel channel used for manual acknowledgement and DLQ routing
     */
    @RabbitListener(queues = RabbitMqTopologyConfig.PLACE_INDEX_SYNC_QUEUE)
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
        switch (event.eventType()) {
            case PlaceEventTypes.PLACE_INDEX_UPSERT_V1 -> {
                PlaceIndexUpsertEvent payload =
                        objectMapper.convertValue(event.data(), PlaceIndexUpsertEvent.class);
                Optional<Place> place = placeRepository.findById(payload.placeId());
                // Two gates, both read from the source of truth rather than from the envelope,
                // which carries only the id.
                //   post_count: only index a place that a published post uses, so a name someone
                //     typed and never posted does not reach search. A missing row or zero count
                //     drops any stale document rather than resurrecting it from an out-of-order
                //     upsert.
                //   status: a banned or deleted place belongs on no surface. Reading the status
                //     here is why banning needs no event type of its own: the same upsert turns
                //     into a delete the moment the row says the place is out of circulation.
                if (place.isPresent()
                        && place.get().getPostCount() > 0
                        && place.get().getStatus() == PlaceStatus.ACTIVE) {
                    Place source = place.get();
                    placeSearchRepository.save(
                            PlaceDocument.builder()
                                    .id(source.getId().toString())
                                    .name(source.getName())
                                    .normalizedName(source.getNormalizedName())
                                    .postCount(source.getPostCount())
                                    .createdAt(source.getCreatedAt())
                                    .build());
                } else {
                    placeSearchRepository.deleteById(payload.placeId().toString());
                }
            }
            case PlaceEventTypes.PLACE_INDEX_DELETE_V1 -> {
                PlaceIndexDeleteEvent payload =
                        objectMapper.convertValue(event.data(), PlaceIndexDeleteEvent.class);
                placeSearchRepository.deleteById(payload.placeId().toString());
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
            throw new RuntimeException("Interrupted during place index sync retry backoff", ex);
        }
    }

    private void routeToDlqOrRequeue(
            Message message, Channel channel, long deliveryTag, RuntimeException failure) {
        try {
            deadLetterPublisher.publish(
                    message,
                    RabbitMqTopologyConfig.PLACE_INDEX_DEAD_LETTER_ROUTING_KEY,
                    failure.getMessage());
            ack(channel, deliveryTag);
        } catch (RuntimeException dlqFailure) {
            log.warn(
                    "Failed to publish place index sync event to DLQ; requeueing: {}",
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
            log.error("Failed to ack place index sync message: {}", ex.getMessage());
        }
    }

    private void nack(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (IOException ex) {
            log.error("Failed to nack place index sync message: {}", ex.getMessage());
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
