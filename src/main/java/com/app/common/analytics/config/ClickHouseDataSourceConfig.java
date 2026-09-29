package com.app.common.analytics.config;

import java.time.Duration;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The four ClickHouse connection pools and their {@link JdbcClient}s.
 *
 * <p>Every bean is declared with {@code defaultCandidate = false}, so Spring Boot's auto-configured
 * PostgreSQL {@code DataSource}, {@code JdbcTemplate}, JPA and Flyway keep resolving the primary
 * database, and a ClickHouse pool is only ever injected by its qualifier.
 *
 * <p>The standalone V2 driver does not register with {@code DriverManager}, so each pool names
 * {@code com.clickhouse.jdbc.Driver} explicitly. The client's own three connection retries would
 * triple every outage's latency, so {@code retry=0}, and {@code initializationFailTimeout=-1} lets
 * the application start while ClickHouse is down.
 */
@Configuration
@ConditionalOnProperty(name = "app.analytics.enabled", havingValue = "true")
public class ClickHouseDataSourceConfig {

    private static final String DRIVER_CLASS = "com.clickhouse.jdbc.Driver";

    private final AnalyticsProperties properties;

    public ClickHouseDataSourceConfig(AnalyticsProperties properties) {
        this.properties = properties;
    }

    @Bean(defaultCandidate = false, destroyMethod = "close")
    @Qualifier("clickHouseWriter")
    DataSource clickHouseWriterDataSource() {
        AnalyticsProperties.Writer writer = properties.clickhouse().writer();
        return pool(
                "clickhouse-writer",
                writer.username(),
                writer.password(),
                writer.poolSize(),
                writer.socketTimeout());
    }

    @Bean(defaultCandidate = false, destroyMethod = "close")
    @Qualifier("clickHouseReader")
    DataSource clickHouseReaderDataSource() {
        AnalyticsProperties.Reader reader = properties.clickhouse().reader();
        return pool(
                "clickhouse-reader",
                reader.username(),
                reader.password(),
                reader.poolSize(),
                reader.socketTimeout());
    }

    @Bean(defaultCandidate = false, destroyMethod = "close")
    @Qualifier("clickHouseBatch")
    DataSource clickHouseBatchDataSource() {
        AnalyticsProperties.Reader reader = properties.clickhouse().reader();
        AnalyticsProperties.Batch batch = properties.clickhouse().batch();
        return pool(
                "clickhouse-batch",
                reader.username(),
                reader.password(),
                batch.poolSize(),
                batch.socketTimeout());
    }

    @Bean(defaultCandidate = false, destroyMethod = "close")
    @Qualifier("clickHouseMigrator")
    DataSource clickHouseMigratorDataSource() {
        AnalyticsProperties.Migrator migrator = properties.clickhouse().migrator();
        // Schema scripts run one statement at a time, so a single connection is enough.
        return pool(
                "clickhouse-migrator",
                migrator.username(),
                migrator.password(),
                2,
                Duration.ofMinutes(5));
    }

    @Bean(defaultCandidate = false)
    @Qualifier("clickHouseWriterJdbcClient")
    JdbcClient clickHouseWriterJdbcClient(@Qualifier("clickHouseWriter") DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean(defaultCandidate = false)
    @Qualifier("clickHouseReaderJdbcClient")
    JdbcClient clickHouseReaderJdbcClient(@Qualifier("clickHouseReader") DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean(defaultCandidate = false)
    @Qualifier("clickHouseBatchJdbcClient")
    JdbcClient clickHouseBatchJdbcClient(@Qualifier("clickHouseBatch") DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    private DataSource pool(
            String poolName,
            String username,
            String password,
            int poolSize,
            Duration socketTimeout) {
        Duration connectionTimeout = properties.clickhouse().connectionTimeout();
        HikariConfig config = new HikariConfig();
        config.setPoolName(poolName);
        config.setDriverClassName(DRIVER_CLASS);
        config.setJdbcUrl(properties.clickhouse().url());
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(0);
        // A starved pool must fail fast into a drop or a requeue instead of holding a permit or a
        // listener thread for Hikari's default 30 seconds.
        config.setConnectionTimeout(connectionTimeout.toMillis());
        config.setInitializationFailTimeout(-1);
        config.addDataSourceProperty("retry", "0");
        config.addDataSourceProperty("connection_timeout", millis(connectionTimeout));
        config.addDataSourceProperty("socket_timeout", millis(socketTimeout));
        return new HikariDataSource(config);
    }

    private static String millis(Duration duration) {
        return Long.toString(duration.toMillis());
    }
}
