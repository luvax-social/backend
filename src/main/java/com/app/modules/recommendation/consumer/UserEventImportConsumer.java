package com.app.modules.recommendation.consumer;

import java.io.IOException;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.app.common.analytics.ClickHouseUnavailableException;
import com.app.common.analytics.ingest.AnalyticsListenerIds;
import com.app.common.config.rabbit.RabbitMqTopologyConfig;
import com.app.common.inbox.service.ProcessedMessageService;
import com.app.common.messaging.DomainEventMessageParser;
import com.app.common.messaging.exception.PermanentMessageException;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.modules.recommendation.messaging.RecommendationEventTypes;
import com.app.modules.recommendation.messaging.UserEventImportedEvent;
import com.app.modules.recommendation.repository.UserEventAnalyticsRepository;
import com.rabbitmq.client.Channel;

/**
 * Stores one imported behavioural event in ClickHouse per delivered message.
 *
 * <p>An imported event is never sent to Gorse: the row carries no feedback type, so a rebuild of
 * the recommender never replays it either. The message is acknowledged only after ClickHouse has
 * written the part.
 *
 * <p>Failure handling follows the analytics rule. An unavailable ClickHouse requeues the message
 * and rolls back the inbox marker, and the ingestion controller stops this listener once the
 * breaker opens, so the message waits in its queue instead of burning retries into the dead-letter
 * queue. Any other failure is a message that can never succeed and is nacked without requeue, which
 * the queue's dead-letter arguments route to {@code recommendation.user-event.import.dlq}.
 *
 * <p>Only present when the analytics tier is enabled. The listener starts stopped and is started by
 * the ingestion controller once the ClickHouse schema is ready.
 */
@Component
@ConditionalOnProperty(name = "app.analytics.enabled", havingValue = "true")
public class UserEventImportConsumer {

    static final String CONSUMER_NAME = "user-event-import-consumer";

    private static final Logger log = LoggerFactory.getLogger(UserEventImportConsumer.class);

    private final DomainEventMessageParser parser;
    private final ProcessedMessageService processedMessageService;
    private final UserEventAnalyticsRepository analyticsRepository;

    public UserEventImportConsumer(
            DomainEventMessageParser parser,
            ProcessedMessageService processedMessageService,
            UserEventAnalyticsRepository analyticsRepository) {
        this.parser = parser;
        this.processedMessageService = processedMessageService;
        this.analyticsRepository = analyticsRepository;
    }

    @RabbitListener(
            id = AnalyticsListenerIds.USER_EVENT_IMPORT,
            queues = RabbitMqTopologyConfig.USER_EVENT_IMPORT_QUEUE,
            autoStartup = "false",
            concurrency = "2")
    public void consume(Message message, Channel channel) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            DomainEventEnvelope event = parser.parse(message);
            validateEnvelope(event);
            processedMessageService.processOnce(
                    CONSUMER_NAME, event.eventId(), event.eventType(), () -> apply(event));
            ack(channel, deliveryTag);
        } catch (ClickHouseUnavailableException ex) {
            // Nothing was written and the inbox marker rolled back with the handler's transaction.
            log.warn(
                    "User event import paused, message returned to the queue: {}", ex.getMessage());
            nack(channel, deliveryTag, true);
        } catch (RuntimeException ex) {
            log.warn(
                    "User event import permanently failed, routing to DLQ via broker dead-letter"
                            + " binding: {}",
                    ex.getMessage());
            nack(channel, deliveryTag, false);
        }
    }

    private void apply(DomainEventEnvelope event) {
        if (!RecommendationEventTypes.USER_EVENT_IMPORTED_V1.equals(event.eventType())) {
            throw new PermanentMessageException("unknown event type: " + event.eventType());
        }
        if (event.aggregateId() == null) {
            throw new PermanentMessageException(
                    "User event import has no account: " + event.eventId());
        }
        UserEventImportedEvent.Imported imported = UserEventImportedEvent.parse(event.data());
        analyticsRepository.insertImported(
                event.eventId(),
                event.aggregateId(),
                imported.eventType(),
                imported.entityType(),
                imported.entityId(),
                imported.metadata(),
                imported.createdAt().atOffset(ZoneOffset.UTC));
    }

    private static void validateEnvelope(DomainEventEnvelope event) {
        if (event == null || event.eventId() == null) {
            throw new PermanentMessageException("Event envelope or id is null");
        }
        if (event.eventType() == null || event.eventType().isBlank()) {
            throw new PermanentMessageException("Event type is missing");
        }
    }

    // channel.basicAck/basicNack declare IOException on a broken channel; the listener container's
    // own recovery handles that case, so it is logged rather than thrown out of the listener.
    private static void ack(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException ex) {
            log.error("Failed to ack user event import message: {}", ex.getMessage());
        }
    }

    private static void nack(Channel channel, long deliveryTag, boolean requeue) {
        try {
            channel.basicNack(deliveryTag, false, requeue);
        } catch (IOException ex) {
            log.error("Failed to nack user event import message: {}", ex.getMessage());
        }
    }
}
