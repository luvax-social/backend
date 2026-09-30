package com.app.modules.comment.service.impl;

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

import com.app.common.response.CursorPageResponse;
import com.app.common.response.UserSummaryResponse;
import com.app.modules.comment.dto.request.CreateCommentRequest;
import com.app.modules.comment.dto.response.CommentResponse;
import com.app.modules.comment.service.CommentService;
import com.app.modules.mail.service.MailService;
import com.app.modules.users.service.impl.UserSummaryServiceImpl;
import com.app.testsupport.TestContainerImages;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "spring.jpa.properties.hibernate.generate_statistics=true",
            "app.comment.consumer.enabled=false",
            "app.comment.live.enabled=false",
            "app.outbox.publisher.enabled=false",
            "app.post.seed.enabled=false",
            "app.hashtag.seed.enabled=false"
        })
@Testcontainers
class CommentAuthorEmbeddingIT {

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
        r.add("JWT_SECRET", () -> "comment-embedding-it-secret-32-chars-min!!!!");
        r.add("JWT_ISSUER", () -> "https://comment.it.local");
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

    @Autowired private CommentService commentService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM posts");
        jdbcTemplate.update("DELETE FROM user_settings");
        jdbcTemplate.update("DELETE FROM users");
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void listTopLevelComments_authorQueryCountIsConstantAcrossPageSize() {
        UUID owner = insertUser("owner", false);
        UUID viewer = insertUser("viewer", false);
        UUID post = insertPublicPost(owner);
        for (int i = 0; i < 6; i++) {
            insertTopLevelComment(post, insertUser("author" + i, false), minutesAgo(i));
        }

        Statistics stats = statistics();
        // Warm up so one-time metamodel/sequence statements do not skew the first measured call.
        commentService.listTopLevelComments(viewer, post, null, null, 2);

        stats.clear();
        commentService.listTopLevelComments(viewer, post, null, null, 2);
        long smallPage = stats.getPrepareStatementCount();

        stats.clear();
        commentService.listTopLevelComments(viewer, post, null, null, 6);
        long largePage = stats.getPrepareStatementCount();

        assertThat(largePage).isEqualTo(smallPage);
    }

    @Test
    void listReplies_authorQueryCountIsConstantAcrossPageSize() {
        UUID owner = insertUser("owner", false);
        UUID viewer = insertUser("viewer", false);
        UUID post = insertPublicPost(owner);
        UUID parent = insertTopLevelComment(post, owner, minutesAgo(20));
        for (int i = 0; i < 6; i++) {
            insertReply(post, insertUser("replier" + i, false), parent, minutesAgo(i));
        }

        Statistics stats = statistics();
        commentService.listReplies(viewer, parent, null, 2);

        stats.clear();
        commentService.listReplies(viewer, parent, null, 2);
        long smallPage = stats.getPrepareStatementCount();

        stats.clear();
        commentService.listReplies(viewer, parent, null, 6);
        long largePage = stats.getPrepareStatementCount();

        assertThat(largePage).isEqualTo(smallPage);
    }

    @Test
    void listTopLevelComments_deletedAuthor_returnsPlaceholder() {
        UUID owner = insertUser("owner", false);
        UUID viewer = insertUser("viewer", false);
        UUID ghost = insertUser("ghost", true);
        UUID post = insertPublicPost(owner);
        insertTopLevelComment(post, ghost, minutesAgo(1));

        CursorPageResponse<CommentResponse> page =
                commentService.listTopLevelComments(viewer, post, null, null, 10);

        UserSummaryResponse author = page.getContent().get(0).author();
        assertThat(author.id()).isEqualTo(ghost);
        assertThat(author.username()).isNull();
        assertThat(author.displayName()).isEqualTo(UserSummaryServiceImpl.DELETED_DISPLAY_NAME);
    }

    @Test
    void listReplies_deletedAuthor_returnsPlaceholder() {
        UUID owner = insertUser("owner", false);
        UUID viewer = insertUser("viewer", false);
        UUID ghost = insertUser("ghost", true);
        UUID post = insertPublicPost(owner);
        UUID parent = insertTopLevelComment(post, owner, minutesAgo(10));
        insertReply(post, ghost, parent, minutesAgo(1));

        CursorPageResponse<CommentResponse> page =
                commentService.listReplies(viewer, parent, null, 10);

        assertThat(page.getContent().get(0).author().displayName())
                .isEqualTo(UserSummaryServiceImpl.DELETED_DISPLAY_NAME);
    }

    @Test
    void listTopLevelComments_multipleAuthorsWithDuplicate_assignsEachRowsAuthor() {
        UUID owner = insertUser("owner", false);
        UUID viewer = insertUser("viewer", false);
        UUID alice = insertUser("alice", false);
        UUID bob = insertUser("bob", false);
        UUID post = insertPublicPost(owner);
        // Two rows by alice and one by bob, interleaved, to prove per-row assignment and dedup.
        insertTopLevelComment(post, alice, minutesAgo(3));
        insertTopLevelComment(post, bob, minutesAgo(2));
        insertTopLevelComment(post, alice, minutesAgo(1));

        List<CommentResponse> content =
                commentService.listTopLevelComments(viewer, post, null, null, 10).getContent();

        assertThat(content).hasSize(3);
        // Newest first: alice, bob, alice.
        assertThat(content.get(0).author().id()).isEqualTo(alice);
        assertThat(content.get(0).author().username()).isEqualTo("alice");
        assertThat(content.get(1).author().id()).isEqualTo(bob);
        assertThat(content.get(2).author().id()).isEqualTo(alice);
        assertThat(content.get(0).author()).isEqualTo(content.get(2).author());
    }

    @Test
    void liveEventCarriesSameAuthorAsCreateResponse() {
        UUID owner = insertUser("owner", false);
        UUID actor = insertUser("actor", false);
        UUID post = insertPublicPost(owner);

        CommentResponse created =
                commentService.createComment(
                        actor, new CreateCommentRequest(post, null, "hello world"), null);

        // The live fanout emits the outbox event's data map verbatim; the author under
        // data.comment.author is exactly what a WebSocket subscriber receives.
        String liveUsername =
                jdbcTemplate.queryForObject(
                        "SELECT payload->'data'->'comment'->'author'->>'username' FROM outbox_events"
                                + " WHERE event_type = 'comment.created.v1'",
                        String.class);
        String liveId =
                jdbcTemplate.queryForObject(
                        "SELECT payload->'data'->'comment'->'author'->>'id' FROM outbox_events"
                                + " WHERE event_type = 'comment.created.v1'",
                        String.class);

        assertThat(liveUsername).isEqualTo(created.author().username()).isEqualTo("actor");
        assertThat(liveId).isEqualTo(created.author().id().toString());
    }

    private OffsetDateTime minutesAgo(int minutes) {
        return OffsetDateTime.now().minusMinutes(minutes);
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

    private UUID insertPublicPost(UUID userId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO posts(user_id, post_type, status) VALUES (?, 'image', 'published')"
                        + " RETURNING id",
                UUID.class,
                userId);
    }

    private UUID insertTopLevelComment(UUID postId, UUID userId, OffsetDateTime createdAt) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO comments(post_id, user_id, depth, content, moderation_status,"
                        + " created_at) VALUES (?, ?, 0, 'text', 'approved', ?) RETURNING id",
                UUID.class,
                postId,
                userId,
                createdAt);
    }

    private UUID insertReply(UUID postId, UUID userId, UUID parentId, OffsetDateTime createdAt) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO comments(post_id, user_id, parent_id, root_id, depth, content,"
                        + " moderation_status, created_at) VALUES (?, ?, ?, ?, 1, 'text',"
                        + " 'approved', ?) RETURNING id",
                UUID.class,
                postId,
                userId,
                parentId,
                parentId,
                createdAt);
    }
}
