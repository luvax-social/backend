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
import com.app.common.response.UserListItemResponse;
import com.app.common.response.UserSummaryResponse;
import com.app.modules.hashtag.dto.response.HashtagSummaryResponse;
import com.app.modules.mail.service.MailService;
import com.app.modules.post.dto.response.FeedPostResponse;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.service.PostLikeService;
import com.app.modules.post.service.PostService;
import com.app.modules.post.validation.PostTypeFilter;
import com.app.modules.users.service.impl.UserSummaryServiceImpl;
import com.app.testsupport.TestContainerImages;

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
class PostAuthorEmbeddingIT {

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
        r.add("JWT_SECRET", () -> "post-embedding-it-secret-32-chars-minimum!!!!");
        r.add("JWT_ISSUER", () -> "https://post.it.local");
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
    @Autowired private PostLikeService postLikeService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM post_likes");
        jdbcTemplate.update("DELETE FROM post_hashtags");
        jdbcTemplate.update("DELETE FROM posts");
        jdbcTemplate.update("DELETE FROM hashtags");
        jdbcTemplate.update("DELETE FROM follows");
        jdbcTemplate.update("DELETE FROM user_settings");
        jdbcTemplate.update("DELETE FROM users");
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void getFeed_authorQueryCountIsConstantAcrossPageSize() {
        UUID viewer = insertUser("viewer", false);
        for (int i = 0; i < 6; i++) {
            UUID author = insertUser("author" + i, false);
            follow(viewer, author);
            insertPublishedPost(author);
        }

        Statistics stats = statistics();
        postService.getFeed(viewer, null, 2);

        stats.clear();
        postService.getFeed(viewer, null, 2);
        long smallPage = stats.getPrepareStatementCount();

        stats.clear();
        postService.getFeed(viewer, null, 6);
        long largePage = stats.getPrepareStatementCount();

        assertThat(largePage).isEqualTo(smallPage);
    }

    @Test
    void getFeed_carriesHashtagsAtAConstantStatementCountFromOneItemToTwenty() {
        // The same field and the same shape the post detail carries, batched the same way. Adding
        // the field to the detail payload produced an N+1 the first time, which is why the count
        // is measured at both ends of a real page rather than asserted to be "batched".
        UUID viewer = insertUser("hashtagviewer", false);
        for (int i = 0; i < 20; i++) {
            UUID author = insertUser("hashtagauthor" + i, false);
            follow(viewer, author);
            UUID post = insertPublishedPost(author);
            attachHashtag(post, "feedtag" + i);
            attachHashtag(post, "sharedtag");
        }

        Statistics stats = statistics();
        // Warm up so one-time metamodel statements do not land inside a measured call.
        postService.getFeed(viewer, null, 1);

        stats.clear();
        List<FeedPostResponse> onePost = postService.getFeed(viewer, null, 1).getContent();
        long oneItem = stats.getPrepareStatementCount();

        stats.clear();
        List<FeedPostResponse> twentyPosts = postService.getFeed(viewer, null, 20).getContent();
        long twentyItems = stats.getPrepareStatementCount();

        assertThat(onePost).hasSize(1);
        assertThat(twentyPosts).hasSize(20);
        assertThat(twentyItems)
                .as("statement count at 20 items must equal the count at 1 item")
                .isEqualTo(oneItem);
        assertThat(twentyPosts)
                .allSatisfy(
                        post ->
                                assertThat(post.hashtags())
                                        .extracting(HashtagSummaryResponse::name)
                                        .contains("sharedtag"));
    }

    @Test
    void listLikers_authorQueryCountIsConstantAcrossPageSize() {
        UUID owner = insertUser("owner", false);
        UUID post = insertPublishedPost(owner);
        for (int i = 0; i < 6; i++) {
            like(post, insertUser("liker" + i, false));
        }

        Statistics stats = statistics();
        postLikeService.listLikers(owner, post, null, 2);

        stats.clear();
        postLikeService.listLikers(owner, post, null, 2);
        long smallPage = stats.getPrepareStatementCount();

        stats.clear();
        postLikeService.listLikers(owner, post, null, 6);
        long largePage = stats.getPrepareStatementCount();

        assertThat(largePage).isEqualTo(smallPage);
    }

    @Test
    void listLikers_deletedLiker_returnsPlaceholderNotDropped() {
        UUID owner = insertUser("owner", false);
        UUID ghost = insertUser("ghost", true);
        UUID post = insertPublishedPost(owner);
        like(post, ghost);

        CursorPageResponse<UserListItemResponse> page =
                postLikeService.listLikers(owner, post, null, 10);

        assertThat(page.getContent()).hasSize(1);
        UserSummaryResponse liker = page.getContent().get(0).user();
        assertThat(liker.id()).isEqualTo(ghost);
        assertThat(liker.username()).isNull();
        assertThat(liker.displayName()).isEqualTo(UserSummaryServiceImpl.DELETED_DISPLAY_NAME);
    }

    @Test
    void getFeed_multipleAuthorsWithDuplicate_assignsEachPostsAuthor() {
        UUID viewer = insertUser("viewer", false);
        UUID alice = insertUser("alice", false);
        UUID bob = insertUser("bob", false);
        follow(viewer, alice);
        follow(viewer, bob);
        insertPublishedPost(alice);
        insertPublishedPost(bob);
        insertPublishedPost(alice);

        List<FeedPostResponse> feed = postService.getFeed(viewer, null, 10).getContent();

        assertThat(feed).hasSize(3);
        // Two of the three posts are alice's; every post carries its own author, and alice's two
        // summaries are the identical resolved object.
        List<UserSummaryResponse> aliceAuthors =
                feed.stream()
                        .map(FeedPostResponse::author)
                        .filter(a -> a.id().equals(alice))
                        .toList();
        assertThat(aliceAuthors).hasSize(2);
        assertThat(aliceAuthors.get(0)).isEqualTo(aliceAuthors.get(1));
        assertThat(feed.stream().map(FeedPostResponse::author).map(UserSummaryResponse::id))
                .containsOnly(alice, bob);
    }

    @Test
    void listUserPosts_embedsAuthorSummary() {
        UUID owner = insertUser("owner", false);
        insertPublishedPost(owner);

        List<PostResponse> posts =
                postService
                        .listUserPosts(owner, owner, PostTypeFilter.empty(), null, 10)
                        .getContent();

        assertThat(posts).hasSize(1);
        assertThat(posts.get(0).author().id()).isEqualTo(owner);
        assertThat(posts.get(0).author().username()).isEqualTo("owner");
    }

    private void attachHashtag(UUID postId, String name) {
        UUID hashtagId =
                jdbcTemplate.queryForObject(
                        "INSERT INTO hashtags(name) VALUES (?)"
                                + " ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name"
                                + " RETURNING id",
                        UUID.class,
                        name);
        jdbcTemplate.update(
                "INSERT INTO post_hashtags(post_id, hashtag_id) VALUES (?, ?)", postId, hashtagId);
    }

    private UUID insertUser(String username, boolean deleted) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users(username, email, display_name, is_verified, deleted_at)"
                        + " VALUES (?, ?, ?, false, CASE WHEN ? THEN NOW() ELSE NULL END)"
                        + " RETURNING id",
                UUID.class,
                username,
                username + "@example.com",
                username,
                deleted);
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
}
