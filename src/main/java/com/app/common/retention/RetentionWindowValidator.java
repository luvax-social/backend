package com.app.common.retention;

import java.time.Duration;
import java.time.Period;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Component;

import com.app.common.messaging.config.ConsumerRetryProperties;
import com.app.common.outbox.config.OutboxPublisherProperties;

/**
 * Refuses to start unless the inbox retention window outlives the longest possible redelivery of a
 * duplicate event, and is at least as long as the outbox retention window.
 *
 * <p>A marker in {@code processed_messages} only matters while a second copy of the same {@code
 * (consumer, event_id)} can still arrive. Copies arise from two mechanisms: an outbox republish
 * whose confirm was lost, bounded by {@code max-attempts * processing-timeout} plus its retry
 * backoffs, and a consumer redelivery of a message whose ack was lost, bounded by the consumer
 * retry backoffs. {@code processed-messages} must exceed the sum of both, and must be at least
 * {@code outbox-published}: once an outbox row is purged, the inbox marker is what still rejects a
 * duplicate client retry deduplicated by {@code OutboxService.enqueueOnce}.
 */
@Component
public class RetentionWindowValidator {

    private final RetentionProperties retentionProperties;
    private final OutboxPublisherProperties outboxPublisherProperties;
    private final ConsumerRetryProperties consumerRetryProperties;

    public RetentionWindowValidator(
            RetentionProperties retentionProperties,
            OutboxPublisherProperties outboxPublisherProperties,
            ConsumerRetryProperties consumerRetryProperties) {
        this.retentionProperties = retentionProperties;
        this.outboxPublisherProperties = outboxPublisherProperties;
        this.consumerRetryProperties = consumerRetryProperties;
    }

    @PostConstruct
    void validate() {
        Duration maxRedeliveryWindow = maxRedeliveryWindow();
        Duration processedMessages = asDuration(retentionProperties.getProcessedMessages());
        Duration outboxPublished = asDuration(retentionProperties.getOutboxPublished());

        if (processedMessages.compareTo(maxRedeliveryWindow) <= 0) {
            throw new IllegalStateException(
                    "app.retention.processed-messages ("
                            + processedMessages
                            + ") must exceed the maximum possible redelivery window ("
                            + maxRedeliveryWindow
                            + "); a shorter window can purge a marker while a duplicate delivery"
                            + " is still possible.");
        }
        if (processedMessages.compareTo(outboxPublished) < 0) {
            throw new IllegalStateException(
                    "app.retention.processed-messages ("
                            + processedMessages
                            + ") must be at least app.retention.outbox-published ("
                            + outboxPublished
                            + "); once an outbox row is purged, the inbox marker is the only"
                            + " remaining guard against a duplicate client retry.");
        }
    }

    /**
     * The longest possible gap between an event's first and last delivered copy.
     *
     * @return {@code W_o + W_c}, the sum of the outbox republish window and the consumer redelivery
     *     window
     */
    Duration maxRedeliveryWindow() {
        return outboxRepublishWindow().plus(consumerRedeliveryWindow());
    }

    private Duration outboxRepublishWindow() {
        int maxAttempts = Math.max(1, outboxPublisherProperties.getMaxAttempts());
        Duration lease = outboxPublisherProperties.resolvedProcessingTimeout();
        Duration total = lease.multipliedBy(maxAttempts);
        for (int attempt = 1; attempt < maxAttempts; attempt++) {
            total = total.plus(outboxPublisherProperties.retryBackoffForAttempt(attempt));
        }
        return total;
    }

    private Duration consumerRedeliveryWindow() {
        int maxAttempts = consumerRetryProperties.resolvedMaxAttempts();
        Duration total = Duration.ZERO;
        for (int attempt = 1; attempt < maxAttempts; attempt++) {
            total = total.plus(consumerRetryProperties.retryBackoffForAttempt(attempt));
        }
        return total;
    }

    // app.retention.* windows are day-granularity ISO-8601 periods (P7D, P14D); the redelivery
    // windows they are compared against are seconds to minutes, so a day-based Duration is exact
    // for every value this property is meant to carry.
    private static Duration asDuration(Period period) {
        return Duration.ofDays(
                period.getDays() + (period.getMonths() * 30L) + (period.getYears() * 365L));
    }
}
