package com.app.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Pins which port serves which actuator endpoint, and to whom, now that actuator moved off the
 * application port entirely (D5). Replaces {@code ActuatorEndpointAccessIT}: the switch this class
 * guarded against reopening no longer exists, and the two ports now carry independent rules.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "management.server.port=0",
            // @SpringBootTest disables every metrics-export registry by default
            // (DisableMetricsExportContextCustomizer); this test needs the real Prometheus
            // registry bean so the scrape endpoint has something to serve.
            "management.prometheus.metrics.export.enabled=true",
            // See ActuatorEndpointAccessIT's original comment: this class declares only Postgres
            // and Redis, and the Elasticsearch health indicator would otherwise probe whatever
            // ELASTICSEARCH_URIS resolves to on the developer's machine.
            "management.health.elasticsearch.enabled=false"
        })
@Testcontainers
@AutoConfigureTestRestTemplate
class ManagementPortAccessIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> "management-port-access-it-secret-32-min!!");
        r.add("JWT_ISSUER", () -> "https://management-port-access.it.local");
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

    @Autowired private TestRestTemplate rest;
    @LocalManagementPort private int managementPort;

    private String managementUrl(String path) {
        return "http://localhost:" + managementPort + path;
    }

    @Test
    void managementPrometheus_noCredentials_isOkAndContainsOutboxGauge() {
        ResponseEntity<String> response =
                new TestRestTemplate()
                        .getForEntity(managementUrl("/actuator/prometheus"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("luvax_outbox_events");
    }

    @Test
    void managementHealth_noCredentials_isOk() {
        ResponseEntity<String> response =
                new TestRestTemplate()
                        .getForEntity(managementUrl("/actuator/health"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void managementInfo_noCredentials_isForbidden() {
        ResponseEntity<String> response =
                new TestRestTemplate().getForEntity(managementUrl("/actuator/info"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void applicationPrometheus_noCredentials_isUnauthorized() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void applicationInfo_noCredentials_isUnauthorized() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/info", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void applicationHealth_noLongerMounted_isNotFound() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void applicationSearch_noCredentials_isUnauthorized() {
        ResponseEntity<String> response =
                rest.exchange("/api/v1/users/search?q=ab", HttpMethod.GET, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
