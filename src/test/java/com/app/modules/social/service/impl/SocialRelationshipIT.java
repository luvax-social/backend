package com.app.modules.social.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
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

import com.app.common.response.UserListItemResponse;
import com.app.modules.mail.service.MailService;
import com.app.modules.post.service.PostLikeService;
import com.app.modules.social.service.SocialService;
import com.app.testsupport.TestContainerImages;

/**
 * Proves the viewer's follow/block relationship to each row is resolved with a constant query count
 * across page size, and that pending-vs-accepted, followedBy asymmetry, and block reachability on
 * the likers list are all correct.
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
class SocialRelationshipIT {

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
        r.add("JWT_SECRET", () -> "social-relationship-it-secret-32-chars-min!!!");
        r.add("JWT_ISSUER", () -> "https://social-relationship.it.local");
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

    @Autowired private SocialService socialService;
    @Autowired private PostLikeService postLikeService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM post_likes");
        jdbcTemplate.update("DELETE FROM posts");
        jdbcTemplate.update("DELETE FROM blocks");
        jdbcTemplate.update("DELETE FROM follows");
        jdbcTemplate.update("DELETE FROM user_settings");
        jdbcTemplate.update("DELETE FROM users");
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void getFollowers_relationshipQueryCountIsConstantAcrossThreePageSizes() {
        UUID target = insertUser("target");
        UUID viewer = insertUser("viewer");
        for (int i = 0; i < 12; i++) {
            UUID follower = insertUser("follower" + i);
            follow(follower, target, "accepted", minutesAgo(i));
            if (i % 2 == 0) {
                follow(viewer, follower, "accepted", minutesAgo(100 + i));
            }
        }

        Statistics stats = statistics();
        socialService.getFollowers(target, viewer, null, 2);

        stats.clear();
        socialService.getFollowers(target, viewer, null, 2);
        long size2 = stats.getPrepareStatementCount();

        stats.clear();
        socialService.getFollowers(target, viewer, null, 6);
        long size6 = stats.getPrepareStatementCount();

        stats.clear();
        socialService.getFollowers(target, viewer, null, 12);
        long size12 = stats.getPrepareStatementCount();

        assertThat(size6).isEqualTo(size2);
        assertThat(size12).isEqualTo(size2);
    }

    @Test
    void getFollowing_relationshipQueryCountIsConstantAcrossThreePageSizes() {
        UUID target = insertUser("target");
        UUID viewer = insertUser("viewer");
        for (int i = 0; i < 12; i++) {
            UUID followee = insertUser("followee" + i);
            follow(target, followee, "accepted", minutesAgo(i));
        }

        Statistics stats = statistics();
        socialService.getFollowing(target, viewer, null, 2);

        stats.clear();
        socialService.getFollowing(target, viewer, null, 2);
        long size2 = stats.getPrepareStatementCount();

        stats.clear();
        socialService.getFollowing(target, viewer, null, 6);
        long size6 = stats.getPrepareStatementCount();

        stats.clear();
        socialService.getFollowing(target, viewer, null, 12);
        long size12 = stats.getPrepareStatementCount();

        assertThat(size6).isEqualTo(size2);
        assertThat(size12).isEqualTo(size2);
    }

    @Test
    void listLikers_relationshipQueryCountIsConstantAcrossThreePageSizes() {
        UUID owner = insertUser("owner");
        UUID viewer = insertUser("viewer");
        UUID post = insertPublishedPost(owner);
        for (int i = 0; i < 12; i++) {
            UUID liker = insertUser("liker" + i);
            like(post, liker);
            if (i % 2 == 0) {
                follow(viewer, liker, "accepted", minutesAgo(100 + i));
            }
        }

        Statistics stats = statistics();
        postLikeService.listLikers(viewer, post, null, 2);

        stats.clear();
        postLikeService.listLikers(viewer, post, null, 2);
        long size2 = stats.getPrepareStatementCount();

        stats.clear();
        postLikeService.listLikers(viewer, post, null, 6);
        long size6 = stats.getPrepareStatementCount();

        stats.clear();
        postLikeService.listLikers(viewer, post, null, 12);
        long size12 = stats.getPrepareStatementCount();

        assertThat(size6).isEqualTo(size2);
        assertThat(size12).isEqualTo(size2);
    }

    @Test
    void getBlockedUsers_relationshipQueryCountIsConstantAcrossThreePageSizes() {
        UUID viewer = insertUser("viewer");
        for (int i = 0; i < 12; i++) {
            UUID blocked = insertUser("blocked" + i);
            block(viewer, blocked);
            if (i % 2 == 0) {
                block(blocked, viewer);
            }
        }

        Statistics stats = statistics();
        socialService.getBlockedUsers(viewer, null, 2);

        stats.clear();
        socialService.getBlockedUsers(viewer, null, 2);
        long size2 = stats.getPrepareStatementCount();

        stats.clear();
        socialService.getBlockedUsers(viewer, null, 6);
        long size6 = stats.getPrepareStatementCount();

        stats.clear();
        socialService.getBlockedUsers(viewer, null, 12);
        long size12 = stats.getPrepareStatementCount();

        assertThat(size6).isEqualTo(size2);
        assertThat(size12).isEqualTo(size2);
    }

    @Test
    void getFollowers_pendingVersusAccepted_flagsDistinctly() {
        UUID target = insertUser("target");
        UUID viewer = insertUser("viewer");
        UUID acceptedOther = insertUser("acceptedOther");
        UUID pendingOther = insertUser("pendingOther");
        follow(acceptedOther, target, "accepted", minutesAgo(2));
        follow(pendingOther, target, "accepted", minutesAgo(1));
        follow(viewer, acceptedOther, "accepted", minutesAgo(50));
        follow(viewer, pendingOther, "pending", minutesAgo(50));

        List<UserListItemResponse> content =
                socialService.getFollowers(target, viewer, null, 10).getContent();

        assertThat(content).hasSize(2);
        UserListItemResponse acceptedItem =
                content.stream()
                        .filter(i -> i.user().id().equals(acceptedOther))
                        .findFirst()
                        .orElseThrow();
        UserListItemResponse pendingItem =
                content.stream()
                        .filter(i -> i.user().id().equals(pendingOther))
                        .findFirst()
                        .orElseThrow();
        assertThat(acceptedItem.viewerState().isFollowing()).isTrue();
        assertThat(acceptedItem.viewerState().isFollowRequested()).isFalse();
        assertThat(pendingItem.viewerState().isFollowing()).isFalse();
        assertThat(pendingItem.viewerState().isFollowRequested()).isTrue();
    }

    @Test
    void getFollowers_isFollowedByAsymmetry_correctPerRow() {
        UUID target = insertUser("target");
        UUID viewer = insertUser("viewer");
        UUID followsViewerBack = insertUser("followsViewerBack");
        UUID doesNotFollowViewer = insertUser("doesNotFollowViewer");
        follow(followsViewerBack, target, "accepted", minutesAgo(2));
        follow(doesNotFollowViewer, target, "accepted", minutesAgo(1));
        // followsViewerBack follows the viewer; doesNotFollowViewer does not.
        follow(followsViewerBack, viewer, "accepted", minutesAgo(50));

        List<UserListItemResponse> content =
                socialService.getFollowers(target, viewer, null, 10).getContent();

        UserListItemResponse followsBack =
                content.stream()
                        .filter(i -> i.user().id().equals(followsViewerBack))
                        .findFirst()
                        .orElseThrow();
        UserListItemResponse doesNotFollow =
                content.stream()
                        .filter(i -> i.user().id().equals(doesNotFollowViewer))
                        .findFirst()
                        .orElseThrow();
        assertThat(followsBack.viewerState().isFollowedBy()).isTrue();
        assertThat(doesNotFollow.viewerState().isFollowedBy()).isFalse();
    }

    @Test
    void listLikers_excludesBlockedLikerButKeepsOrdinaryOne() {
        UUID owner = insertUser("owner");
        UUID viewer = insertUser("viewer");
        UUID blockedLiker = insertUser("blockedLiker");
        UUID ordinaryLiker = insertUser("ordinaryLiker");
        UUID post = insertPublishedPost(owner);
        like(post, blockedLiker);
        like(post, ordinaryLiker);
        // Viewer blocks blockedLiker after they liked the post; the stealth block model requires
        // listLikers to exclude a liker in a block relationship with the viewer rather than
        // surface them with an isBlocking flag.
        block(viewer, blockedLiker);

        List<UserListItemResponse> content =
                postLikeService.listLikers(viewer, post, null, 10).getContent();

        assertThat(content).hasSize(1);
        UserListItemResponse ordinary = content.get(0);
        assertThat(ordinary.user().id()).isEqualTo(ordinaryLiker);
        assertThat(ordinary.viewerState().isBlocking()).isFalse();
    }

    private OffsetDateTime minutesAgo(int minutes) {
        return OffsetDateTime.now().minusMinutes(minutes);
    }

    private UUID insertUser(String username) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users(username, email, display_name, is_verified, deleted_at)"
                        + " VALUES (?, ?, ?, false, NULL) RETURNING id",
                UUID.class,
                username,
                username + "@example.com",
                username);
    }

    private UUID insertPublishedPost(UUID userId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO posts(user_id, post_type, status) VALUES (?, 'image', 'published')"
                        + " RETURNING id",
                UUID.class,
                userId);
    }

    private void follow(UUID follower, UUID following, String status, OffsetDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO follows(follower_id, following_id, status, created_at)"
                        + " VALUES (?, ?, ?::follow_status, ?)",
                follower,
                following,
                status,
                createdAt);
    }

    private void block(UUID blocker, UUID blocked) {
        jdbcTemplate.update(
                "INSERT INTO blocks(blocker_id, blocked_id) VALUES (?, ?)", blocker, blocked);
    }

    private void like(UUID postId, UUID userId) {
        jdbcTemplate.update(
                "INSERT INTO post_likes(post_id, user_id) VALUES (?, ?)", postId, userId);
    }
}
