package com.app.common.analytics.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds the ClickHouse analytics store settings from {@code app.analytics.*}.
 *
 * <p>Three application users each get their own connection pool: the writer inserts, the reader
 * serves request-path reads, and the batch pool serves long reads for jobs with the reader's
 * credentials. The migrator runs schema scripts and the seed reset's truncation and nothing else.
 *
 * @param enabled whether the ClickHouse store, its schema runner and its consumers exist at all
 * @param clickhouse connection settings
 * @param migration schema runner settings
 */
@ConfigurationProperties(prefix = "app.analytics")
public record AnalyticsProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue ClickHouse clickhouse,
        @DefaultValue Migration migration) {

    /**
     * Connection settings shared by every pool.
     *
     * @param url JDBC URL naming the {@code luvax_analytics} database
     * @param connectionTimeout bound on opening one connection, also each pool's checkout timeout
     * @param writer the insert-only user's pool
     * @param reader the select-only user's request-path pool
     * @param batch the reader user's long-read pool
     * @param migrator the DDL user, used by the schema runner and the seed reset
     */
    public record ClickHouse(
            @DefaultValue("jdbc:clickhouse://localhost:8123/luvax_analytics") String url,
            @DefaultValue("PT2S") Duration connectionTimeout,
            @DefaultValue Writer writer,
            @DefaultValue Reader reader,
            @DefaultValue Batch batch,
            @DefaultValue Migrator migrator) {}

    /**
     * The writer pool.
     *
     * @param username the ClickHouse user holding INSERT only
     * @param password its password, never logged
     * @param poolSize maximum connections
     * @param socketTimeout bound on one insert round trip
     */
    public record Writer(
            @DefaultValue("luvax_analytics_writer") String username,
            @DefaultValue("") String password,
            @DefaultValue("12") int poolSize,
            @DefaultValue("PT10S") Duration socketTimeout) {}

    /**
     * The request-path reader pool.
     *
     * @param username the ClickHouse user holding SELECT only
     * @param password its password, never logged
     * @param poolSize maximum connections
     * @param socketTimeout bound on one read, kept above the profile's server-side time limit so
     *     the server's typed timeout arrives first
     */
    public record Reader(
            @DefaultValue("luvax_analytics_reader") String username,
            @DefaultValue("") String password,
            @DefaultValue("6") int poolSize,
            @DefaultValue("PT25S") Duration socketTimeout) {}

    /**
     * The long-read pool for jobs. It authenticates as the reader user.
     *
     * @param poolSize maximum connections
     * @param socketTimeout bound on one long read
     */
    public record Batch(
            @DefaultValue("2") int poolSize, @DefaultValue("PT5M") Duration socketTimeout) {}

    /**
     * The DDL user.
     *
     * @param username the ClickHouse user holding DDL on {@code luvax_analytics} only
     * @param password its password, never logged
     */
    public record Migrator(
            @DefaultValue("luvax_analytics_migrator") String username,
            @DefaultValue("") String password) {}

    /**
     * Schema runner settings.
     *
     * @param retryInterval how often the schema gate retries a failed migration, so the application
     *     starts and serves while ClickHouse is down
     */
    public record Migration(@DefaultValue("PT30S") Duration retryInterval) {}
}
