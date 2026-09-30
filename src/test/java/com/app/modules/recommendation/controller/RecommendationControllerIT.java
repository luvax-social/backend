package com.app.modules.recommendation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import com.app.modules.recommendation.client.GorseClient;
import com.app.modules.recommendation.client.dto.GorseScore;
import com.app.testsupport.TestContainerImages;

/**
 * Covers the {@code excludeFollowed} discovery ("Explore") variant of the recommendation feed
 * against a real stack. {@link GorseClient} is stubbed rather than pointed at a live Gorse
 * instance, since this test's concern is the application-side exclusion, not the recommender itself
 * - {@code RecommendationSourceTest} and {@code RecommendationFeedServiceImplTest} already cover
 * the candidate-source and filtering logic in isolation.
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
class RecommendationControllerIT {

    private static final String FEED_PATH =
            "/api/v1/recommendations/feed?limit=20&excludeFollowed=true";

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
        registry.add("JWT_SECRET", () -> "recommendation-controller-it-secret-32ch!");
        registry.add("JWT_ISSUER", () -> "https://recommendation.it.local");
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
    @MockitoBean private GorseClient gorseClient;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM follows");
        jdbcTemplate.update("DELETE FROM posts");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void getRecommendedFeed_excludeFollowedTrue_viewerFollowsNobody_returnsFullPage() {
        UUID viewer = createUser("explore_none_viewer");
        UUID authorA = createUser("explore_none_author_a");
        UUID authorB = createUser("explore_none_author_b");
        UUID postA = createPost(authorA);
        UUID postB = createPost(authorB);
        when(gorseClient.recommend(any(), anyInt(), anyInt()))
                .thenReturn(
                        List.of(
                                new GorseScore(postA.toString(), 9.0),
                                new GorseScore(postB.toString(), 8.0)));
        when(gorseClient.trending(anyInt(), anyInt())).thenReturn(List.of());

        ResponseEntity<Map> response = getFeed(viewer);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(postIds(response)).containsExactlyInAnyOrder(postA.toString(), postB.toString());
    }

    @Test
    void getRecommendedFeed_excludeFollowedTrue_viewerFollowsMostAuthors_excludesTheirPosts() {
        UUID viewer = createUser("explore_most_viewer");
        UUID followedAuthorA = createUser("explore_most_followed_a");
        UUID followedAuthorB = createUser("explore_most_followed_b");
        UUID unfollowedAuthor = createUser("explore_most_unfollowed");
        UUID followedPostA = createPost(followedAuthorA);
        UUID followedPostB = createPost(followedAuthorB);
        UUID unfollowedPost = createPost(unfollowedAuthor);
        follow(viewer, followedAuthorA);
        follow(viewer, followedAuthorB);
        when(gorseClient.recommend(any(), anyInt(), anyInt()))
                .thenReturn(
                        List.of(
                                new GorseScore(followedPostA.toString(), 9.0),
                                new GorseScore(followedPostB.toString(), 8.5),
                                new GorseScore(unfollowedPost.toString(), 8.0)));
        when(gorseClient.trending(anyInt(), anyInt())).thenReturn(List.of());

        ResponseEntity<Map> response = getFeed(viewer);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(postIds(response)).containsExactly(unfollowedPost.toString());
    }

    @SuppressWarnings("unchecked")
    private List<String> postIds(ResponseEntity<Map> response) {
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        List<Map<String, Object>> content = (List<Map<String, Object>>) data.get("content");
        return content.stream().map(post -> post.get("id").toString()).toList();
    }

    private ResponseEntity<Map> getFeed(UUID viewer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtTokenProvider.generateAccessToken(viewer, "USER", 0));
        return rest.exchange(FEED_PATH, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
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

    private UUID createPost(UUID authorId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO posts (id, user_id, caption, post_type, status)"
                        + " VALUES (?, ?, 'seeded', CAST('text' AS post_type),"
                        + " CAST('published' AS post_status))",
                id,
                authorId);
        return id;
    }

    private void follow(UUID followerId, UUID followingId) {
        jdbcTemplate.update(
                "INSERT INTO follows (follower_id, following_id, status)"
                        + " VALUES (?, ?, CAST('accepted' AS follow_status))",
                followerId,
                followingId);
    }
}
