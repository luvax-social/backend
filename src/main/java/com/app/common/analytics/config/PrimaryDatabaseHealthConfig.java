package com.app.common.analytics.config;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.jdbc.health.DataSourceHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps ClickHouse out of {@code /actuator/health}.
 *
 * <p>Spring Boot's {@code dbHealthContributor} covers every {@code DataSource} bean, so with the
 * ClickHouse pools present a ClickHouse outage would turn the application's health DOWN and fail
 * the deployment platform's health check. It backs off when a bean named {@code dbHealthIndicator}
 * exists, and this one covers only the primary PostgreSQL {@code DataSource}.
 */
@Configuration
@ConditionalOnProperty(name = "app.analytics.enabled", havingValue = "true")
public class PrimaryDatabaseHealthConfig {

    @Bean
    HealthIndicator dbHealthIndicator(DataSource primaryDataSource) {
        return new DataSourceHealthIndicator(primaryDataSource);
    }
}
