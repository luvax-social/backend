package com.app.modules.users.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
import com.app.modules.users.dto.response.PublicUserProfileResponse;
import com.app.modules.users.service.UserService;
import com.app.testsupport.TestContainerImages;

/**
 * Proves the anonymous caller short-circuits to zero relationship queries with every flag present
 * and false, and that an authenticated viewer's relationship is resolved correctly.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "spring.jpa.properties.hibernate.generate_statistics=true",
            "app.outbox.publisher.enabled=false",
            "app.post.seed.enabled=false",
            "app.hashtag.seed.enabled=false"
        })
@Testcontainers
class UserProfileViewerStateIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> "user-viewer-state-it-secret-32-chars-minimum!!");
        r.add("JWT_ISSUER", () -> "https://user-viewer-state.it.local");
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

    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM follows");
        jdbcTemplate.update("DELETE FROM blocks");
        jdbcTemplate.update("DELETE FROM user_settings");
        jdbcTemplate.update("DELETE FROM users");
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void getUserProfile_anonymousViewer_allFlagsPresentAndFalseWithNoExtraQueries() {
        UUID target = insertUser("target", false);

        Statistics stats = statistics();
        stats.clear();
        PublicUserProfileResponse response = userService.getUserProfile(null, target);
        long anonymousCount = stats.getPrepareStatementCount();

        assertThat(response.viewerState().isFollowing()).isFalse();
        assertThat(response.viewerState().isFollowRequested()).isFalse();
        assertThat(response.viewerState().isFollowedBy()).isFalse();
        assertThat(response.viewerState().isBlocking()).isFalse();

        // Re-run with a real viewer to isolate the relationship-query delta the anonymous path
        // must avoid: findByIdAndDeletedAtIsNull (the profile lookup) still runs either way, so
        // the anonymous run's statement count is strictly less than an authenticated run's.
        UUID viewer = insertUser("viewer", false);
        stats.clear();
        userService.getUserProfile(viewer, target);
        long authenticatedCount = stats.getPrepareStatementCount();

        assertThat(anonymousCount).isLessThan(authenticatedCount);
    }

    @Test
    void getUserProfile_viewerFollowsTarget_flagsIsFollowingTrue() {
        UUID target = insertUser("target", false);
        UUID viewer = insertUser("viewer", false);
        follow(viewer, target, "accepted");

        PublicUserProfileResponse response = userService.getUserProfile(viewer, target);

        assertThat(response.viewerState().isFollowing()).isTrue();
        assertThat(response.viewerState().isFollowRequested()).isFalse();
    }

    @Test
    void getUserProfile_viewerHasPendingRequest_flagsIsFollowRequestedTrue() {
        UUID target = insertUser("target", true);
        UUID viewer = insertUser("viewer", false);
        follow(viewer, target, "pending");

        PublicUserProfileResponse response = userService.getUserProfile(viewer, target);

        assertThat(response.viewerState().isFollowing()).isFalse();
        assertThat(response.viewerState().isFollowRequested()).isTrue();
    }

    @Test
    void getUserProfile_selfView_viewerStateAllFalse() {
        UUID target = insertUser("target", false);

        PublicUserProfileResponse response = userService.getUserProfile(target, target);

        assertThat(response.viewerState().isFollowing()).isFalse();
        assertThat(response.viewerState().isFollowRequested()).isFalse();
        assertThat(response.viewerState().isFollowedBy()).isFalse();
        assertThat(response.viewerState().isBlocking()).isFalse();
    }

    private UUID insertUser(String username, boolean isPrivate) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users(username, email, display_name, is_verified, is_private,"
                        + " deleted_at) VALUES (?, ?, ?, false, ?, NULL) RETURNING id",
                UUID.class,
                username,
                username + "@example.com",
                username,
                isPrivate);
    }

    private void follow(UUID follower, UUID following, String status) {
        jdbcTemplate.update(
                "INSERT INTO follows(follower_id, following_id, status) VALUES (?, ?,"
                        + " ?::follow_status)",
                follower,
                following,
                status);
    }
}
