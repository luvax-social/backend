package com.app.common.analytics;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.app.common.analytics.migration.AnalyticsSchemaGate;

import lombok.extern.slf4j.Slf4j;

/**
 * Empties every analytics table as the DDL user, for a reseed.
 *
 * <p>Only the migrator holds the {@code TRUNCATE} grant: the writer can only insert and the reader
 * can only select. A failure is thrown rather than logged, because a reseed that left the analytics
 * tables behind would silently disagree with the freshly truncated PostgreSQL rows.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.analytics.enabled", havingValue = "true")
public class AnalyticsStoreTruncator {

    private static final List<String> TABLES =
            List.of(
                    "luvax_analytics.user_events",
                    "luvax_analytics.admin_actions",
                    "luvax_analytics.platform_stats");

    private final DataSource migrator;
    private final AnalyticsSchemaGate schemaGate;

    public AnalyticsStoreTruncator(
            @Qualifier("clickHouseMigrator") DataSource migrator, AnalyticsSchemaGate schemaGate) {
        this.migrator = migrator;
        this.schemaGate = schemaGate;
    }

    /**
     * Truncates {@code user_events}, {@code admin_actions} and {@code platform_stats}.
     *
     * @throws IllegalStateException when ClickHouse cannot be reached, its schema cannot be
     *     migrated, or it refuses a truncation
     */
    public void truncateAll() {
        // A seed starts on the same application-ready event that starts the schema gate, so on a
        // first boot the tables may not exist yet. Running the migrations here, which is a no-op
        // once they have applied, removes that race.
        try {
            schemaGate.attempt();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "The analytics schema is not ready, so the tables cannot be truncated: "
                            + e.getMessage(),
                    e);
        }
        try (Connection connection = migrator.getConnection();
                Statement statement = connection.createStatement()) {
            for (String table : TABLES) {
                statement.execute("TRUNCATE TABLE " + table);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not truncate the analytics tables: " + e.getMessage(), e);
        }
        log.info("[seed] reset: {} analytics tables truncated", TABLES.size());
    }
}
