package com.app.common.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.analytics.migration.AnalyticsSchemaGate;
import com.app.testsupport.ClickHouseTestSupport;
import com.app.testsupport.TestContainerImages;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A ClickHouse outage must never turn {@code /actuator/health} DOWN, which would fail the
 * deployment platform's health check, and adding the ClickHouse pools must not change which
 * database the rest of the application resolves.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "management.server.port=0",
            "management.endpoint.health.show-details=always",
            "management.health.elasticsearch.enabled=false"
        })
@Testcontainers
class AnalyticsHealthIsolationIT {

    static final ClickHouseContainer clickhouse = ClickHouseTestSupport.startProvisioned();

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        ClickHouseTestSupport.register(r, clickhouse);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> "analytics-health-it-secret-32-characters!!");
        r.add("JWT_ISSUER", () -> "https://analytics-health.it.local");
        r.add("JWT_AUDIENCE", () -> "App");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
        r.add("app.outbox.publisher.enabled", () -> false);
    }

    @LocalManagementPort private int managementPort;
    @Autowired private AnalyticsSchemaGate gate;
    // Unqualified: must still be the PostgreSQL one now that ClickHouse pools exist.
    @Autowired private JdbcClient primaryJdbcClient;

    private ResponseEntity<String> health() {
        return new TestRestTemplate()
                .getForEntity(
                        "http://localhost:" + managementPort + "/actuator/health", String.class);
    }

    @Test
    void health_clickHouseStopped_staysUpAndNamesOnlyPostgres() throws Exception {
        assertThat(gate.attempt()).isTrue();
        assertThat(health().getStatusCode()).isEqualTo(HttpStatus.OK);

        clickhouse.stop();

        ResponseEntity<String> response = health();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = JsonMapper.builder().build().readTree(response.getBody());
        assertThat(body.get("status").asString()).isEqualTo("UP");
        JsonNode db = body.get("components").get("db");
        assertThat(db.get("status").asString()).isEqualTo("UP");
        assertThat(db.get("details").get("database").asString()).isEqualTo("PostgreSQL");
        assertThat(response.getBody().toLowerCase()).doesNotContain("clickhouse");
    }

    @Test
    void primaryJdbcClient_withClickHousePoolsPresent_stillResolvesPostgres() {
        String version = primaryJdbcClient.sql("SELECT version()").query(String.class).single();

        assertThat(version).contains("PostgreSQL");
    }
}
