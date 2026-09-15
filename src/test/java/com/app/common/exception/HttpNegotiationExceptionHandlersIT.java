package com.app.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.response.ApiResponse;
import com.app.modules.mail.service.MailService;

/**
 * Proves the three content-negotiation and missing-parameter failure shapes reach the {@link
 * ApiResponse} envelope with the correct status instead of falling through to the unhandled-
 * exception catch-all (415/406 -> 500) or, for an unacceptable {@code Accept} header, re-entering
 * the security filter chain on the {@code /error} ERROR dispatch and answering 401 (406 -> 401).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "app.outbox.publisher.enabled=false",
            "app.post.seed.enabled=false",
            "app.hashtag.seed.enabled=false"
        })
@Testcontainers
@AutoConfigureTestRestTemplate
class HttpNegotiationExceptionHandlersIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        // These tests drive login, register and report submission as setup, not as the
        // subject under test. The kill switch keeps them off the network: the dev profile
        // defaults the secret to Cloudflare's test key, and a real siteverify call would
        // make the suite depend on an external service being reachable.
        r.add("TURNSTILE_AUTH_ENABLED", () -> false);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> "http-negotiation-it-secret-32-chars-minimum!!!");
        r.add("JWT_ISSUER", () -> "https://http-negotiation.it.local");
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
    }

    @MockitoBean private MailService mailService;

    @Autowired private TestRestTemplate rest;

    @Test
    void wrongContentType_returns415NotServerError() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/auth/register",
                        HttpMethod.POST,
                        new HttpEntity<>("not json", headers),
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("success")).isEqualTo(false);
        assertThat(body.get("code")).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void unacceptableAcceptHeader_returns406NotUnauthorized() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/xml");
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/hashtags/trending",
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("success")).isEqualTo(false);
        assertThat(body.get("code")).isEqualTo("NOT_ACCEPTABLE");
    }

    @Test
    void missingRequiredQueryParameter_returns400NotServerError() {
        ResponseEntity<Map> response = rest.getForEntity("/api/v1/auth/verify-email", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("success")).isEqualTo(false);
        assertThat(body.get("code")).isEqualTo("MISSING_REQUIRED_PARAMETER");
    }
}
