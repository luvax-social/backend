package com.app.modules.report.service.impl;

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

import com.app.modules.comment.dto.response.CommentResponse;
import com.app.modules.comment.service.CommentService;
import com.app.modules.mail.service.MailService;
import com.app.modules.post.service.PostService;
import com.app.modules.post.validation.PostTypeFilter;
import com.app.modules.users.service.UserService;
import com.app.testsupport.TestContainerImages;

import tools.jackson.databind.ObjectMapper;

/**
 * Proves {@code hasReported} is true exactly when a new report from the same viewer against the
 * same target would be rejected as a duplicate, on the post, comment, and user surfaces.
 *
 * <p>Assertions read the serialised JSON rather than a typed accessor so the published wire
 * contract is what is under test.
 */
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
class ReportedViewerStateIT {

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
        r.add("JWT_SECRET", () -> "reported-viewer-state-it-secret-32-chars!!");
        r.add("JWT_ISSUER", () -> "https://reported-viewer-state.it.local");
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
    @Autowired private CommentService commentService;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private ObjectMapper objectMapper;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM reports");
        jdbcTemplate.update("DELETE FROM comment_likes");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM post_likes");
        jdbcTemplate.update("DELETE FROM post_saves");
        jdbcTemplate.update("DELETE FROM posts");
        jdbcTemplate.update("DELETE FROM follows");
        jdbcTemplate.update("DELETE FROM user_settings");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void postResponse_carriesHasReported_falseBeforeReportingAndTrueAfter() {
        UUID viewer = insertUser("viewer");
        UUID owner = insertUser("owner");
        UUID post = insertPublishedPost(owner);

        assertThat(hasReported(postService.getPostById(viewer, post))).isFalse();

        insertReport(viewer, "post", post, "pending");

        assertThat(hasReported(postService.getPostById(viewer, post))).isTrue();
    }

    @Test
    void commentResponse_carriesHasReported_falseBeforeReportingAndTrueAfter() {
        UUID viewer = insertUser("viewer");
        UUID owner = insertUser("owner");
        UUID post = insertPublishedPost(owner);
        UUID comment = insertTopLevelComment(post, owner, minutesAgo(1));

        assertThat(hasReported(firstComment(viewer, post))).isFalse();

        insertReport(viewer, "comment", comment, "pending");

        assertThat(hasReported(firstComment(viewer, post))).isTrue();
    }

    @Test
    void userProfileResponse_carriesHasReported_falseBeforeReportingAndTrueAfter() {
        UUID viewer = insertUser("viewer");
        UUID target = insertUser("target");

        assertThat(hasReported(userService.getUserProfile(viewer, target))).isFalse();

        insertReport(viewer, "user", target, "pending");

        assertThat(hasReported(userService.getUserProfile(viewer, target))).isTrue();
    }

    @Test
    void hasReported_reflectsOnlyTheRequestingViewer() {
        UUID viewer = insertUser("viewer");
        UUID otherReporter = insertUser("other");
        UUID owner = insertUser("owner");
        UUID post = insertPublishedPost(owner);

        insertReport(otherReporter, "post", post, "pending");

        assertThat(hasReported(postService.getPostById(viewer, post))).isFalse();
        assertThat(hasReported(postService.getPostById(otherReporter, post))).isTrue();
    }

    @Test
    void hasReported_isFalseForAnAnonymousViewer() {
        UUID reporter = insertUser("reporter");
        UUID target = insertUser("target");
        insertReport(reporter, "user", target, "pending");

        // The public profile is the only one of the three surfaces readable anonymously: the post
        // and comment read paths both reject a null viewer outright, so there is no anonymous
        // response there to carry the flag.
        assertThat(hasReported(userService.getUserProfile(null, target))).isFalse();
        assertThat(hasReported(userService.getUserProfile(reporter, target))).isTrue();
    }

    @Test
    void hasReported_isFalseOnTheViewersOwnContent() {
        UUID owner = insertUser("owner");
        UUID post = insertPublishedPost(owner);

        assertThat(hasReported(postService.getPostById(owner, post))).isFalse();
    }

    @Test
    void hasReported_isTrueOnlyForActiveReportStatuses() {
        for (String status : List.of("pending", "reviewing", "escalated")) {
            UUID viewer = insertUser("viewer_" + status);
            UUID owner = insertUser("owner_" + status);
            UUID post = insertPublishedPost(owner);
            insertReport(viewer, "post", post, status);

            assertThat(hasReported(postService.getPostById(viewer, post)))
                    .as("hasReported for a report in status %s", status)
                    .isTrue();
        }
        for (String status : List.of("resolved", "dismissed")) {
            UUID viewer = insertUser("viewer_" + status);
            UUID owner = insertUser("owner_" + status);
            UUID post = insertPublishedPost(owner);
            insertReport(viewer, "post", post, status);

            assertThat(hasReported(postService.getPostById(viewer, post)))
                    .as("hasReported for a report in status %s", status)
                    .isFalse();
        }
    }

    @Test
    void listUserPosts_issuesTheSameStatementCountForOneItemAndTwentyItems() {
        UUID viewer = insertUser("viewer");
        UUID owner = insertUser("owner");
        for (int i = 0; i < 20; i++) {
            UUID post = insertPublishedPost(owner);
            if (i % 2 == 0) {
                insertReport(viewer, "post", post, "pending");
            }
        }

        Statistics stats = statistics();
        postService.listUserPosts(viewer, owner, PostTypeFilter.empty(), null, 1);

        stats.clear();
        postService.listUserPosts(viewer, owner, PostTypeFilter.empty(), null, 1);
        long onePage = stats.getPrepareStatementCount();

        stats.clear();
        postService.listUserPosts(viewer, owner, PostTypeFilter.empty(), null, 20);
        long twentyPage = stats.getPrepareStatementCount();

        // The number is not the point; that the two are equal is. Each viewer-state or hydration
        // concern adds exactly one batched lookup whose cost does not move with the page size, so
        // a page of twenty costs the same number of statements as a page of one. It was eight
        // before the report lookup was batched, nine after, and ten once a post response began
        // listing its hashtags. A change that makes these two differ has introduced an N+1.
        assertThat(onePage).isEqualTo(twentyPage);
        assertThat(onePage).isEqualTo(10);
    }

    private CommentResponse firstComment(UUID viewer, UUID postId) {
        return commentService
                .listTopLevelComments(viewer, postId, "newest", null, 10)
                .getContent()
                .get(0);
    }

    private boolean hasReported(Object response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            return json.contains("\"hasReported\":true");
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise response", e);
        }
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
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

    private UUID insertTopLevelComment(UUID postId, UUID userId, OffsetDateTime createdAt) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO comments(post_id, user_id, depth, content, moderation_status,"
                        + " created_at) VALUES (?, ?, 0, 'text', 'approved', ?) RETURNING id",
                UUID.class,
                postId,
                userId,
                createdAt);
    }

    private void insertReport(UUID reporterId, String reportType, UUID entityId, String status) {
        jdbcTemplate.update(
                "INSERT INTO reports(reporter_id, report_type, report_reason, entity_id, status)"
                        + " VALUES (?, CAST(? AS report_type), 'spam', ?, CAST(? AS report_status))",
                reporterId,
                reportType,
                entityId,
                status);
    }
}
