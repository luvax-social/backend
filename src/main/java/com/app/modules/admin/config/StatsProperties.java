package com.app.modules.admin.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for platform statistics collection. Bound from {@code app.stats.*}.
 *
 * <ul>
 *   <li>{@code enabled}: whether the scheduled collection job is registered at all. Defaults to
 *       true.
 *   <li>{@code interval}: bucket width, and the delay between collection passes. Buckets are
 *       aligned to the epoch, not to process start.
 *   <li>{@code half-hour-horizon}: how far back a series may be requested at half-hour width.
 *       Nothing is deleted at this age: every half-hour bucket is kept, and the horizon only stops
 *       a chart from asking for a year of half-hour points, which is 17,520 of them.
 * </ul>
 *
 * <p>A single application instance is assumed. No distributed scheduler lock exists in this
 * codebase, so two instances would each collect every bucket. That is harmless rather than
 * duplicative, because both collections describe the same bucket and the store keeps the one
 * computed last, but it is an assumption to revisit before scaling out.
 */
@ConfigurationProperties(prefix = "app.stats")
public record StatsProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("PT30M") Duration interval,
        @DefaultValue("P30D") Duration halfHourHorizon) {}
