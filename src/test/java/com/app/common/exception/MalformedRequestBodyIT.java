package com.app.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.modules.mail.service.MailService;

/**
 * Reproduces the audit's F-3.16-a defect over HTTP: a malformed request body must return 400, not
 * the unhandled-exception catch-all's 500.
 *
 * <p>All five malformed-body causes reach Spring MVC as the same {@link
 * org.springframework.http.converter.HttpMessageNotReadableException}, so one handler and one
 * status code covers unrecognised properties, invalid JSON syntax, a missing body, a wrong root
 * type, and an invalid enum value alike. This deliberately does not introduce a second status code
 * for the enum case: {@code ApiErrorCode.VALIDATION_ERROR} is 400, not 422, so a malformed body is
 * treated the same way the rest of the application already treats a validation failure.
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
class MalformedRequestBodyIT {

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
        r.add("JWT_SECRET", () -> "malformed-body-it-secret-32-chars-minimum!!");
        r.add("JWT_ISSUER", () -> "https://malformed-body.it.local");
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
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final AtomicInteger IP_COUNTER = new AtomicInteger(1);

    private String accessToken;

    @Test
    void unrecognisedProperty_returns400MalformedRequestBody() {
        ResponseEntity<Map> response =
                postRaw("{\"caption\":\"x\",\"postType\":\"text\",\"nope\":1}");
        assertMalformedBody(response, "nope");
    }

    @Test
    void invalidJsonSyntax_returns400MalformedRequestBody() {
        ResponseEntity<Map> response = postRaw("{\"caption\":");
        assertMalformedBody(response, null);
    }

    @Test
    void missingBody_returns400MalformedRequestBody() {
        HttpHeaders headers = authHeaders();
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/posts", HttpMethod.POST, new HttpEntity<>(headers), Map.class);
        assertMalformedBody(response, null);
    }

    @Test
    void wrongRootType_returns400MalformedRequestBody() {
        ResponseEntity<Map> response = postRaw("[]");
        assertMalformedBody(response, null);
    }

    @Test
    void invalidEnumValue_returns400MalformedRequestBody() {
        ResponseEntity<Map> response = postRaw("{\"caption\":\"x\",\"postType\":\"not_a_type\"}");
        assertMalformedBody(response, "not_a_type");
    }

    /**
     * Asserts the full contract: status 400, the {@code MALFORMED_REQUEST_BODY} code, a failed
     * envelope with no data, and - the leak-prevention requirement - that neither the internal
     * package prefix nor the caller-supplied offending token ever reaches the response body.
     */
    private void assertMalformedBody(ResponseEntity<Map> response, String rejectedToken) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("success")).isEqualTo(false);
        assertThat(body.get("code")).isEqualTo("MALFORMED_REQUEST_BODY");
        assertThat(body.get("data")).isNull();
        String raw = String.valueOf(body);
        assertThat(raw).doesNotContain("com.app.");
        if (rejectedToken != null) {
            assertThat(raw).doesNotContain(rejectedToken);
        }
    }

    private ResponseEntity<Map> postRaw(String rawJson) {
        HttpHeaders headers = authHeaders();
        return rest.exchange(
                "/api/v1/posts", HttpMethod.POST, new HttpEntity<>(rawJson, headers), Map.class);
    }

    private HttpHeaders authHeaders() {
        if (accessToken == null) {
            accessToken = registerAndLogin();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        return headers;
    }

    private String registerAndLogin() {
        String username = "malformedit" + IP_COUNTER.get();
        String email = username + "@test.local";
        String password = "S3cur3P@ssword!";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", "10.77." + IP_COUNTER.incrementAndGet() + ".1");

        ResponseEntity<Map> registerResponse =
                rest.exchange(
                        "/api/v1/auth/register",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of(
                                        "username",
                                        username,
                                        "email",
                                        email,
                                        "password",
                                        password,
                                        "displayName",
                                        username),
                                headers),
                        Map.class);
        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        UUID id =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM users WHERE username = ?", UUID.class, username);
        jdbcTemplate.update(
                "UPDATE user_credentials SET email_verified = TRUE WHERE user_id = ?", id);

        ResponseEntity<Map> loginResponse =
                rest.exchange(
                        "/api/v1/auth/login",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of("identifier", email, "password", password), headers),
                        Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) loginResponse.getBody().get("data");
        return (String) data.get("accessToken");
    }
}
