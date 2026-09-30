package com.app.common.analytics.ingest;

import java.util.List;

/**
 * Ids of the RabbitMQ listener containers that write to ClickHouse. Each consumer declares its id
 * on {@code @RabbitListener} with {@code autoStartup = "false"}, and {@link
 * AnalyticsIngestionController} starts and stops the container by that id.
 */
public final class AnalyticsListenerIds {

    public static final String ADMIN_ACTION_REPLICATION = "adminActionReplication";
    public static final String PLATFORM_STATS_INGEST = "platformStatsIngest";
    public static final String USER_EVENT_IMPORT = "userEventImport";
    public static final String RECOMMENDATION_FEEDBACK = "recommendationFeedback";

    /** Every controlled id. A listener whose consumer is switched off simply has no container. */
    public static final List<String> ALL =
            List.of(
                    ADMIN_ACTION_REPLICATION,
                    PLATFORM_STATS_INGEST,
                    USER_EVENT_IMPORT,
                    RECOMMENDATION_FEEDBACK);

    private AnalyticsListenerIds() {}
}
