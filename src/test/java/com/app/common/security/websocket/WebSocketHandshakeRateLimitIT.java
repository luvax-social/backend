package com.app.common.security.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.testsupport.TestContainerImages;

/**
 * Proves the {@code /ws/**} rate-limit rule added to {@link
 * com.app.common.security.filter.AuthRateLimitFilter} actually rejects handshake attempts beyond
 * its bucket, on the comment WebSocket surface. The equivalent test for the notification surface
 * lives with the notification WebSocket endpoint once it exists, since the surface being asserted
 * against must exist to be meaningfully rate limited.
 *
 * <p>Targets the SockJS {@code /info} sub-path rather than the full STOMP handshake: it is a plain,
 * unauthenticated GET matched by the same {@code /ws/**} rule and returns an inspectable HTTP
 * status (200 or 429), unlike a native WebSocket upgrade attempt which only surfaces as a
 * connection failure with no distinguishable cause.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "app.comment.live.enabled=true",
            "app.comment.consumer.enabled=false",
            "app.hashtag.seed.enabled=false",
            "app.post.seed.enabled=false",
            "app.outbox.publisher.enabled=false",
            "app.rate-limit.endpoint-rules[/ws/**].max-attempts=3",
            "app.rate-limit.endpoint-rules[/ws/**].window-seconds=60"
        })
@Testcontainers
class WebSocketHandshakeRateLimitIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static GenericContainer<?> rabbit =
            new GenericContainer<>(DockerImageName.parse(TestContainerImages.RABBITMQ))
                    .withExposedPorts(5672);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("spring.rabbitmq.host", rabbit::getHost);
        r.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        r.add("spring.rabbitmq.username", () -> "guest");
        r.add("spring.rabbitmq.password", () -> "guest");
        r.add("JWT_SECRET", () -> "ws-rate-limit-it-secret-32-characters-minimum!!");
        r.add("JWT_ISSUER", () -> "https://ws-rate-limit-it.test.local");
        r.add("JWT_AUDIENCE", () -> "ws-rate-limit-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App WS Rate Limit IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @LocalServerPort private int port;

    @Test
    void handshakeInfoRequests_beyondBucket_rejectedWithRetryAfter() {
        RestTemplate rest = boundedRestTemplate();
        String url = "http://localhost:" + port + "/ws/comments/info";

        ResponseEntity<String> first = getIgnoringErrors(rest, url);
        ResponseEntity<String> second = getIgnoringErrors(rest, url);
        ResponseEntity<String> third = getIgnoringErrors(rest, url);
        ResponseEntity<String> fourth = getIgnoringErrors(rest, url);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(third.getStatusCode().value()).isEqualTo(200);
        assertThat(fourth.getStatusCode().value())
                .as(
                        "a 4th handshake-info request within the window must be rejected by the"
                                + " max-attempts=3 /ws/** rule")
                .isEqualTo(429);
        assertThat(fourth.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
    }

    private static RestTemplate boundedRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(5_000);
        return new RestTemplate(factory);
    }

    private static ResponseEntity<String> getIgnoringErrors(RestTemplate rest, String url) {
        try {
            return rest.exchange(url, HttpMethod.GET, HttpEntity.EMPTY, String.class);
        } catch (HttpStatusCodeException ex) {
            HttpStatusCode status = ex.getStatusCode();
            HttpHeaders headers = ex.getResponseHeaders();
            return new ResponseEntity<>(
                    ex.getResponseBodyAsString(),
                    headers == null ? new HttpHeaders() : headers,
                    status);
        }
    }
}
