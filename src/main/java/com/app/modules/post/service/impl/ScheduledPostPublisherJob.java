package com.app.modules.post.service.impl;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.app.modules.post.config.PostProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Wakes the scheduled publisher on a fixed delay.
 *
 * <p>A post due now is published within one interval, which is the promise the composer makes when
 * it offers a time. Off by default and switched on with {@code app.post.scheduled-publish.enabled},
 * so a profile that has no reason to publish, such as the seed profile replaying historic data,
 * does not.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "app.post.scheduled-publish",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class ScheduledPostPublisherJob {

    private final ScheduledPostPublisher scheduledPostPublisher;
    private final PostProperties properties;

    public ScheduledPostPublisherJob(
            ScheduledPostPublisher scheduledPostPublisher, PostProperties properties) {
        this.scheduledPostPublisher = scheduledPostPublisher;
        this.properties = properties;
    }

    @Scheduled(
            initialDelayString = "${app.post.scheduled-publish.initial-delay:PT20S}",
            fixedDelayString = "${app.post.scheduled-publish.fixed-delay:PT30S}")
    public void publishDuePosts() {
        int published =
                scheduledPostPublisher.publishDue(properties.getScheduledPublish().getBatchSize());
        if (published > 0) {
            log.info("ScheduledPostPublisherJob: published {} scheduled posts", published);
        }
    }
}
