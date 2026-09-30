package com.app.modules.admin.consumer;

import java.io.IOException;
import java.util.UUID;

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
import com.app.modules.admin.repository.AdminActionAnalyticsRepository;
import com.app.modules.admin.repository.AdminActionReplicaRow;
import com.app.modules.admin.repository.AdminActionRepository;
import com.rabbitmq.client.Channel;

/**
 * Replicates one {@code admin_actions} row to ClickHouse per delivered event.
 *
 * <p>Notify-then-fetch: the event names a row, and this consumer reads the row's current state from
 * PostgreSQL and writes that. An event delivered late, twice, or after a newer change therefore
 * cannot regress the replica, because the replica keeps the row with the highest {@code
 * row_version} and every fetch returns the highest one that exists.
 *
 * <p>Failure handling follows the analytics rule. An unavailable ClickHouse requeues the message
 * and rolls back the inbox marker, and the ingestion controller stops this listener once the
 * breaker opens, so the message waits in its queue instead of burning retries into the dead-letter
 * queue. Any other failure is a message that can never succeed and is nacked without requeue, which
 * the queue's dead-letter arguments route to {@code admin.action.replication.dlq}.
 *
 * <p>Only present when the analytics tier is enabled. The listener starts stopped and is started by
 * the ingestion controller once the ClickHouse schema is ready.
 */
@Component
@ConditionalOnProperty(name = "app.analytics.enabled", havingValue = "true")
public class AdminActionReplicationConsumer {

    static final String CONSUMER_NAME = "admin-action-replication-consumer";

    private static final Logger log = LoggerFactory.getLogger(AdminActionReplicationConsumer.class);

    private final DomainEventMessageParser parser;
    private final ProcessedMessageService processedMessageService;
    private final AdminActionRepository adminActionRepository;
    private final AdminActionAnalyticsRepository analyticsRepository;

    public AdminActionReplicationConsumer(
            DomainEventMessageParser parser,
            ProcessedMessageService processedMessageService,
            AdminActionRepository adminActionRepository,
            AdminActionAnalyticsRepository analyticsRepository) {
        this.parser = parser;
        this.processedMessageService = processedMessageService;
        this.adminActionRepository = adminActionRepository;
        this.analyticsRepository = analyticsRepository;
    }

    @RabbitListener(
            id = AnalyticsListenerIds.ADMIN_ACTION_REPLICATION,
            queues = RabbitMqTopologyConfig.ADMIN_ACTION_REPLICATION_QUEUE,
            autoStartup = "false",
            concurrency = "1")
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
                    "Admin action replication paused, message returned to the queue: {}",
                    ex.getMessage());
            nack(channel, deliveryTag, true);
        } catch (RuntimeException ex) {
            log.warn(
                    "Admin action replication event permanently failed, routing to DLQ via broker"
                            + " dead-letter binding: {}",
                    ex.getMessage());
            nack(channel, deliveryTag, false);
        }
    }

    private void apply(DomainEventEnvelope event) {
        if (!AdminEventTypes.ACTION_RECORDED_V1.equals(event.eventType())
                && !AdminEventTypes.ACTION_CHANGED_V1.equals(event.eventType())) {
            throw new PermanentMessageException("unknown event type: " + event.eventType());
        }
        UUID actionId = extractActionId(event);
        AdminActionReplicaRow row = adminActionRepository.findReplicaRow(actionId).orElse(null);
        if (row == null) {
            // A reseed truncates admin_actions while events for its old rows can still be queued.
            // There is nothing to replicate and never will be.
            log.debug("Admin action {} no longer exists, nothing to replicate", actionId);
            return;
        }
        analyticsRepository.insert(row);
    }

    private static UUID extractActionId(DomainEventEnvelope event) {
        Object raw = event.data() == null ? null : event.data().get("adminActionId");
        if (raw == null) {
            throw new PermanentMessageException(
                    "Admin action event has no adminActionId: " + event.eventId());
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException ex) {
            throw new PermanentMessageException(
                    "Admin action event adminActionId is not a UUID: " + raw, ex);
        }
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
            log.error("Failed to ack admin action replication message: {}", ex.getMessage());
        }
    }

    private static void nack(Channel channel, long deliveryTag, boolean requeue) {
        try {
            channel.basicNack(deliveryTag, false, requeue);
        } catch (IOException ex) {
            log.error("Failed to nack admin action replication message: {}", ex.getMessage());
        }
    }
}
