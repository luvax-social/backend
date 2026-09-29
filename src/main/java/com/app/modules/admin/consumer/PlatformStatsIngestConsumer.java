package com.app.modules.admin.consumer;

import java.io.IOException;

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
import com.app.modules.admin.messaging.AdminEventTypes;
import com.app.modules.admin.messaging.PlatformStatsCollectedEvent;
import com.app.modules.admin.repository.PlatformStatsAnalyticsRepository;
import com.rabbitmq.client.Channel;

/**
 * Writes one collected statistics bucket to ClickHouse per delivered event.
 *
 * <p>The event carries every row of the bucket, so the whole bucket lands in one insert. A bucket
 * can never be collected again once its moment has passed, so the message is acknowledged only
 * after ClickHouse has written the part.
 *
 * <p>Failure handling follows the analytics rule. An unavailable ClickHouse requeues the message
 * and rolls back the inbox marker, and the ingestion controller stops this listener once the
 * breaker opens, so the message waits in its queue instead of burning retries into the dead-letter
 * queue. Any other failure is a message that can never succeed and is nacked without requeue, which
 * the queue's dead-letter arguments route to {@code admin.platform-stats.dlq}.
 *
 * <p>Only present when the analytics tier is enabled. The listener starts stopped and is started by
 * the ingestion controller once the ClickHouse schema is ready.
 */
@Component
@ConditionalOnProperty(name = "app.analytics.enabled", havingValue = "true")
public class PlatformStatsIngestConsumer {

    static final String CONSUMER_NAME = "platform-stats-ingest-consumer";

    private static final Logger log = LoggerFactory.getLogger(PlatformStatsIngestConsumer.class);

    private final DomainEventMessageParser parser;
    private final ProcessedMessageService processedMessageService;
    private final PlatformStatsAnalyticsRepository analyticsRepository;

    public PlatformStatsIngestConsumer(
            DomainEventMessageParser parser,
            ProcessedMessageService processedMessageService,
            PlatformStatsAnalyticsRepository analyticsRepository) {
        this.parser = parser;
        this.processedMessageService = processedMessageService;
        this.analyticsRepository = analyticsRepository;
    }

    @RabbitListener(
            id = AnalyticsListenerIds.PLATFORM_STATS_INGEST,
            queues = RabbitMqTopologyConfig.PLATFORM_STATS_QUEUE,
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
                    "Platform statistics ingest paused, message returned to the queue: {}",
                    ex.getMessage());
            nack(channel, deliveryTag, true);
        } catch (RuntimeException ex) {
            log.warn(
                    "Platform statistics event permanently failed, routing to DLQ via broker"
                            + " dead-letter binding: {}",
                    ex.getMessage());
            nack(channel, deliveryTag, false);
        }
    }

    private void apply(DomainEventEnvelope event) {
        if (!AdminEventTypes.PLATFORM_STATS_COLLECTED_V1.equals(event.eventType())) {
            throw new PermanentMessageException("unknown event type: " + event.eventType());
        }
        PlatformStatsCollectedEvent.Bucket bucket = PlatformStatsCollectedEvent.parse(event.data());
        analyticsRepository.insertBucket(bucket.bucketStart(), bucket.computedAt(), bucket.rows());
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
            log.error("Failed to ack platform statistics message: {}", ex.getMessage());
        }
    }

    private static void nack(Channel channel, long deliveryTag, boolean requeue) {
        try {
            channel.basicNack(deliveryTag, false, requeue);
        } catch (IOException ex) {
            log.error("Failed to nack platform statistics message: {}", ex.getMessage());
        }
    }
}
