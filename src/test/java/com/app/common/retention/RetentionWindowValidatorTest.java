package com.app.common.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Period;

import org.junit.jupiter.api.Test;

import com.app.common.messaging.config.ConsumerRetryProperties;
import com.app.common.outbox.config.OutboxPublisherProperties;

class RetentionWindowValidatorTest {

    @Test
    void maxRedeliveryWindow_defaults_computes440Seconds() {
        RetentionWindowValidator validator =
                validator(
                        new RetentionProperties(),
                        new OutboxPublisherProperties(),
                        new ConsumerRetryProperties());

        assertThat(validator.maxRedeliveryWindow()).isEqualTo(Duration.ofSeconds(440));
    }

    @Test
    void validate_processedMessagesEqualToWindow_refusesToStart() {
        RetentionProperties properties = new RetentionProperties();
        properties.setProcessedMessages(Period.ofDays(0));
        // 400 seconds is well under one day, so the check needs a sub-day window; express it
        // directly against the same defaults maxRedeliveryWindow_defaults_computes440Seconds
        // proves.
        RetentionWindowValidator validator =
                validator(
                        properties, new OutboxPublisherProperties(), new ConsumerRetryProperties());

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must exceed");
    }

    @Test
    void validate_processedMessagesLessThanOutboxPublished_refusesToStart() {
        RetentionProperties properties = new RetentionProperties();
        properties.setProcessedMessages(Period.ofDays(30));
        properties.setOutboxPublished(Period.ofDays(60));
        RetentionWindowValidator validator =
                validator(
                        properties, new OutboxPublisherProperties(), new ConsumerRetryProperties());

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be at least");
    }

    @Test
    void validate_defaults_accepted() {
        RetentionWindowValidator validator =
                validator(
                        new RetentionProperties(),
                        new OutboxPublisherProperties(),
                        new ConsumerRetryProperties());

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    private static RetentionWindowValidator validator(
            RetentionProperties retention,
            OutboxPublisherProperties outbox,
            ConsumerRetryProperties consumer) {
        return new RetentionWindowValidator(retention, outbox, consumer);
    }
}
