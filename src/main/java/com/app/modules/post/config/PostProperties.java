package com.app.modules.post.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Configuration for the post module: the Elasticsearch index seed and backfill runners and the
 * scheduled publisher. Bound to the {@code app.post} namespace.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.post")
public class PostProperties {

    private Seed seed = new Seed();
    private ScheduledPublish scheduledPublish = new ScheduledPublish();
    private Index index = new Index();

    @Getter
    @Setter
    public static class Seed {
        private boolean enabled = true;
    }

    /** Settings of the job that publishes due scheduled posts. */
    @Getter
    @Setter
    public static class ScheduledPublish {
        private boolean enabled = false;
        private Duration initialDelay = Duration.ofSeconds(20);
        private Duration fixedDelay = Duration.ofSeconds(30);
        private int batchSize = 50;
    }

    /** Settings of the posts index maintenance runners. */
    @Getter
    @Setter
    public static class Index {
        /** Forces a full rewrite of the index from PostgreSQL at the next startup. */
        private boolean backfill = false;
    }
}
