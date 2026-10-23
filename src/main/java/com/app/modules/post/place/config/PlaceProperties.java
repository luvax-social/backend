package com.app.modules.post.place.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Configuration for the place search index seed runner. Bound to the {@code app.place} namespace;
 * the index-sync consumer is switched by {@code app.place.consumer.enabled}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.place")
public class PlaceProperties {

    private Seed seed = new Seed();

    @Getter
    @Setter
    public static class Seed {
        private boolean enabled = true;
    }
}
