package com.app.modules.post.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.app.common.response.CursorPageResponse;
import com.app.modules.mail.service.MailService;
import com.app.modules.post.dto.response.FeedPostResponse;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.dto.response.SavedPostResponse;
import com.app.modules.post.service.PostSaveService;
import com.app.modules.post.service.PostService;
import com.app.modules.post.validation.PostTypeFilter;
import com.app.testsupport.TestContainerImages;

/**
 * Proves the viewer's like/save state is resolved with a constant query count across page size, and
 * that the flags are correct per row rather than uniformly set.
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
class PostViewerStateIT {

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
        r.add("JWT_SECRET", () -> "post-viewer-state-it-secret-32-chars-minimum!!");
        r.add("JWT_ISSUER", () -> "https://post-viewer-state.it.local");
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

    @Autowired private PostService postService;
    @Autowired private PostSaveService postSaveService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM post_likes");
        jdbcTemplate.update("DELETE FROM post_saves");
        jdbcTemplate.update("DELETE FROM posts");
        jdbcTemplate.update("DELETE FROM follows");
        jdbcTemplate.update("DELETE FROM user_settings");
        jdbcTemplate.update("DELETE FROM users");
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void getFeed_viewerStateQueryCountIsConstantAcrossThreePageSizes() {
        UUID viewer = insertUser("viewer");
        UUID author = insertUser("author");
        follow(viewer, author);
        for (int i = 0; i < 12; i++) {
            UUID post = insertPublishedPost(author);
            if (i % 2 == 0) {
                like(post, viewer);
            }
        }

        Statistics stats = statistics();
        postService.getFeed(viewer, null, 2);

        stats.clear();
        postService.getFeed(viewer, null, 2);
        long size2 = stats.getPrepareStatementCount();

        stats.clear();
        postService.getFeed(viewer, null, 6);
        long size6 = stats.getPrepareStatementCount();

        stats.clear();
        postService.getFeed(viewer, null, 12);
        long size12 = stats.getPrepareStatementCount();

        assertThat(size6).isEqualTo(size2);
        assertThat(size12).isEqualTo(size2);
    }

    @Test
    void listUserPosts_viewerStateQueryCountIsConstantAcrossThreePageSizes() {
        UUID viewer = insertUser("viewer");
        UUID owner = insertUser("owner");
        for (int i = 0; i < 12; i++) {
            UUID post = insertPublishedPost(owner);
            if (i % 3 == 0) {
                save(post, viewer);
            }
        }

        Statistics stats = statistics();
        postService.listUserPosts(viewer, owner, PostTypeFilter.empty(), null, 2);

        stats.clear();
        postService.listUserPosts(viewer, owner, PostTypeFilter.empty(), null, 2);
        long size2 = stats.getPrepareStatementCount();

        stats.clear();
        postService.listUserPosts(viewer, owner, PostTypeFilter.empty(), null, 6);
        long size6 = stats.getPrepareStatementCount();

        stats.clear();
        postService.listUserPosts(viewer, owner, PostTypeFilter.empty(), null, 12);
        long size12 = stats.getPrepareStatementCount();

        assertThat(size6).isEqualTo(size2);
        assertThat(size12).isEqualTo(size2);
    }

    @Test
    void getFeed_mixedLikedAndUnliked_flagsCorrectPerRowNotUniform() {
        UUID viewer = insertUser("viewer");
        UUID author = insertUser("author");
        follow(viewer, author);
        UUID likedPost = insertPublishedPost(author);
        UUID unlikedPost = insertPublishedPost(author);
        like(likedPost, viewer);

        List<FeedPostResponse> feed = postService.getFeed(viewer, null, 10).getContent();

        assertThat(feed).hasSize(2);
        FeedPostResponse likedResponse =
                feed.stream().filter(p -> p.id().equals(likedPost)).findFirst().orElseThrow();
        FeedPostResponse unlikedResponse =
                feed.stream().filter(p -> p.id().equals(unlikedPost)).findFirst().orElseThrow();
        assertThat(likedResponse.isLiked()).isTrue();
        assertThat(unlikedResponse.isLiked()).isFalse();
    }

    @Test
    void getFeed_mixedSavedAndUnsaved_flagsCorrectPerRowNotUniform() {
        UUID viewer = insertUser("viewer");
        UUID author = insertUser("author");
        follow(viewer, author);
        UUID savedPost = insertPublishedPost(author);
        UUID unsavedPost = insertPublishedPost(author);
        save(savedPost, viewer);

        List<FeedPostResponse> feed = postService.getFeed(viewer, null, 10).getContent();

        assertThat(feed).hasSize(2);
        FeedPostResponse savedResponse =
                feed.stream().filter(p -> p.id().equals(savedPost)).findFirst().orElseThrow();
        FeedPostResponse unsavedResponse =
                feed.stream().filter(p -> p.id().equals(unsavedPost)).findFirst().orElseThrow();
        assertThat(savedResponse.isSaved()).isTrue();
        assertThat(unsavedResponse.isSaved()).isFalse();
    }

    @Test
    void listSavedPosts_isSavedAlwaysTrue() {
        UUID viewer = insertUser("viewer");
        UUID owner = insertUser("owner");
        UUID post = insertPublishedPost(owner);
        save(post, viewer);

        CursorPageResponse<SavedPostResponse> page =
                postSaveService.listSavedPosts(viewer, null, 10);

        assertThat(page.getContent()).hasSize(1);
        PostResponse embedded = page.getContent().get(0).post();
        assertThat(embedded.isSaved()).isTrue();
    }

    @Test
    void getPostById_singlePost_flagsIsLikedTrue() {
        UUID viewer = insertUser("viewer");
        UUID owner = insertUser("owner");
        UUID post = insertPublishedPost(owner);
        like(post, viewer);

        PostResponse response = postService.getPostById(viewer, post);

        assertThat(response.isLiked()).isTrue();
        assertThat(response.isSaved()).isFalse();
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

    private void follow(UUID follower, UUID following) {
        jdbcTemplate.update(
                "INSERT INTO follows(follower_id, following_id, status) VALUES (?, ?, 'accepted')",
                follower,
                following);
    }

    private void like(UUID postId, UUID userId) {
        jdbcTemplate.update(
                "INSERT INTO post_likes(post_id, user_id) VALUES (?, ?)", postId, userId);
    }

    private void save(UUID postId, UUID userId) {
        jdbcTemplate.update(
                "INSERT INTO post_saves(post_id, user_id) VALUES (?, ?)", postId, userId);
    }
}
