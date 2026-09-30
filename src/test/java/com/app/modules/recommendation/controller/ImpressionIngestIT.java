package com.app.modules.recommendation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
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

import com.app.common.security.jwt.JwtTokenProvider;
import com.app.modules.mail.service.MailService;
import com.app.testsupport.TestContainerImages;

/**
 * Proves the impression endpoint's two load-bearing properties: it never touches the public view
 * counter, and resubmitting a batch enqueues nothing new.
 *
 * <p>The outbox publisher is disabled so rows stay observable in {@code outbox_events} rather than
 * being drained to a broker this test does not run.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@Testcontainers
@AutoConfigureTestRestTemplate
class ImpressionIngestIT {

    private static final String IMPRESSIONS_PATH = "/api/v1/recommendations/impressions";

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("JWT_SECRET", () -> "impression-ingest-it-secret-32-chars!!!!");
        registry.add("JWT_ISSUER", () -> "https://impression.it.local");
        registry.add("JWT_AUDIENCE", () -> "App");
        registry.add("ACCESS_TOKEN_TTL", () -> 900L);
        registry.add("REFRESH_TOKEN_TTL", () -> 3600L);
        registry.add("APP_BASE_URL", () -> "http://localhost:8080");
        registry.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        registry.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        registry.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        registry.add("MAIL_FROM_NAME", () -> "App IT");
        registry.add("MAIL_APP_NAME", () -> "App");
        registry.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        registry.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        registry.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        registry.add(
                "spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
        registry.add("app.outbox.publisher.enabled", () -> false);
        registry.add("app.hashtag.seed.enabled", () -> false);
        registry.add("app.post.seed.enabled", () -> false);
    }

    @MockitoBean private MailService mailService;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM posts");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void recordImpressions_validBatch_enqueuesOneOutboxRowPerImpression() {
        UUID viewer = createUser("impression-viewer-1");
        UUID author = createUser("impression-author-1");
        UUID post = createPost(author, 500);

        ResponseEntity<Map> response =
                postImpressions(viewer, List.of(impression(post, 2.5, "feed")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(outboxRowCount(post)).isEqualTo(1);
    }

    @Test
    void recordImpressions_submittedTwice_isIdempotent() {
        UUID viewer = createUser("impression-viewer-2");
        UUID author = createUser("impression-author-2");
        UUID post = createPost(author, 500);
        List<Map<String, Object>> batch = List.of(impression(post, 2.5, "feed"));

        postImpressions(viewer, batch);
        ResponseEntity<Map> replay = postImpressions(viewer, batch);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(outboxRowCount(post)).isEqualTo(1);
    }

    @Test
    void recordImpressions_validBatch_doesNotChangePostViewCount() {
        UUID viewer = createUser("impression-viewer-3");
        UUID author = createUser("impression-author-3");
        UUID post = createPost(author, 500);
        long before = viewCount(post);

        postImpressions(
                viewer, List.of(impression(post, 2.5, "feed"), impression(post, 9.0, "explore")));

        assertThat(viewCount(post)).isEqualTo(before);
        assertThat(viewCount(post)).isEqualTo(500L);
    }

    @Test
    void recordImpressions_batchOverMaximum_isRejectedRatherThanTruncated() {
        UUID viewer = createUser("impression-viewer-4");
        UUID author = createUser("impression-author-4");
        UUID post = createPost(author, 500);
        List<Map<String, Object>> oversized =
                IntStream.range(0, 101).mapToObj(i -> impression(post, 1.0, "feed")).toList();

        ResponseEntity<Map> response = postImpressions(viewer, oversized);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(outboxRowCount(post)).isZero();
    }

    @Test
    void recordImpressions_unknownProperty_isRejected() {
        UUID viewer = createUser("impression-viewer-5");
        UUID author = createUser("impression-author-5");
        UUID post = createPost(author, 500);
        Map<String, Object> bogus =
                Map.of(
                        "impressionId",
                        UUID.randomUUID().toString(),
                        "postId",
                        post.toString(),
                        "dwellSeconds",
                        1.0,
                        "surface",
                        "feed",
                        "bogus",
                        1);

        ResponseEntity<Map> response = postImpressions(viewer, List.of(bogus));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(outboxRowCount(post)).isZero();
    }

    private Map<String, Object> impression(UUID postId, double dwellSeconds, String surface) {
        return Map.of(
                "impressionId",
                UUID.randomUUID().toString(),
                "postId",
                postId.toString(),
                "dwellSeconds",
                dwellSeconds,
                "surface",
                surface);
    }

    private ResponseEntity<Map> postImpressions(UUID viewer, List<Map<String, Object>> batch) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtTokenProvider.generateAccessToken(viewer, "USER", 0));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(
                IMPRESSIONS_PATH,
                HttpMethod.POST,
                new HttpEntity<>(Map.of("impressions", batch), headers),
                Map.class);
    }

    private int outboxRowCount(UUID postId) {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ?"
                                + " AND event_type = 'post.viewed.v1'",
                        Integer.class,
                        postId);
        return count == null ? 0 : count;
    }

    private long viewCount(UUID postId) {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT view_count FROM posts WHERE id = ?", Long.class, postId);
        return count == null ? 0L : count;
    }

    private UUID createUser(String username) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, username, email, role, status, is_private, is_verified)"
                        + " VALUES (?, ?, ?, CAST('user' AS user_role), 'active', FALSE, TRUE)",
                id,
                username,
                username + "@test.local");
        return id;
    }

    private UUID createPost(UUID authorId, long viewCount) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO posts (id, user_id, caption, post_type, status, view_count)"
                        + " VALUES (?, ?, 'seeded', CAST('text' AS post_type),"
                        + " CAST('published' AS post_status), ?)",
                id,
                authorId,
                viewCount);
        return id;
    }
}
