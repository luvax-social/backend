package com.app.common.retention;

import java.time.Duration;
import java.time.Period;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/** Binds the retention purge job's settings from {@code app.retention.*}. */
@ConfigurationProperties(prefix = "app.retention")
@Getter
@Setter
public class RetentionProperties {

    private boolean enabled = true;
    private Duration initialDelay = Duration.ofMinutes(10);
    private Duration fixedDelay = Duration.ofHours(1);
    private int batchSize = 5000;
    private int maxBatchesPerRun = 100;
    private Period outboxPublished = Period.ofDays(7);
    private Period processedMessages = Period.ofDays(14);
}
