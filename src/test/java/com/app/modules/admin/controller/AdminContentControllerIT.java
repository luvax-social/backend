package com.app.modules.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Exercises the administrative content-inspection surface, whose whole purpose is to invert the
 * ordinary visibility rules for a moderator and for nobody else.
 *
 * <p>The tests that matter here are the pair: removed and soft-deleted content must be visible
 * through these endpoints, and the same content must stay invisible through the ordinary public
 * ones. Either half alone would pass while the surface was broken in the other direction.
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
class AdminContentControllerIT {

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
        registry.add("JWT_SECRET", () -> "admin-content-it-secret-32-chars-minimum!!");
        registry.add("JWT_ISSUER", () -> "https://admincontent.it.local");
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

    private record TestUser(UUID id, String token) {}

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM posts");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void listPostsForUser_administrator_seesRemovedAndDraftPostsAsWellAsPublished() {
        TestUser admin = createUser("content_admin", "admin");
        TestUser author = createUser("content_author", "user");
        insertPost(author.id(), "published", false, "visible one");
        insertPost(author.id(), "draft", false, "never published");
        insertPost(author.id(), "removed", true, "taken down");

        ResponseEntity<Map> response =
                getWithAuth("/api/v1/admin/content/for-user/" + author.id() + "/posts", admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(contentOf(response)).hasSize(3);
        assertThat(contentOf(response).stream().map(row -> row.get("status")).toList())
                .containsExactlyInAnyOrder("published", "draft", "removed");
        assertThat(contentOf(response).stream().map(row -> row.get("removed")).toList())
                .containsExactlyInAnyOrder(true, false, false);
    }

    @Test
    void listPostsForUser_moderator_seesTheSameRemovedContent() {
        TestUser moderator = createUser("content_mod", "moderator");
        TestUser author = createUser("content_author2", "user");
        insertPost(author.id(), "removed", true, "taken down");

        ResponseEntity<Map> response =
                getWithAuth("/api/v1/admin/content/for-user/" + author.id() + "/posts", moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(contentOf(response)).hasSize(1);
    }

    @Test
    void listPostsForUser_privateAccount_isStillReadableByAModerator() {
        // The point of the surface. An investigation that can see only what the investigated
        // account chose to make public is not an investigation.
        TestUser moderator = createUser("content_mod_priv", "moderator");
        TestUser author = createPrivateUser("content_private_author");
        insertPost(author.id(), "published", false, "private account post");

        ResponseEntity<Map> response =
                getWithAuth("/api/v1/admin/content/for-user/" + author.id() + "/posts", moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(contentOf(response)).hasSize(1);
    }

    @Test
    void listPostsForUser_ordinaryUser_isForbidden() {
        TestUser ordinary = createUser("content_ordinary", "user");
        TestUser author = createUser("content_author3", "user");
        insertPost(author.id(), "published", false, "visible one");

        assertThat(
                        getWithAuth(
                                        "/api/v1/admin/content/for-user/" + author.id() + "/posts",
                                        ordinary)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void listPostsForUser_noToken_isUnauthorized() {
        assertThat(
                        rest.getForEntity(
                                        "/api/v1/admin/content/for-user/"
                                                + UUID.randomUUID()
                                                + "/posts",
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void listPostsForUser_unknownAccount_isNotFound() {
        // An empty page for an account that does not exist would read as "this person posted
        // nothing", which is a different answer from "no such account".
        TestUser admin = createUser("content_admin_404", "admin");

        assertThat(
                        getWithAuth(
                                        "/api/v1/admin/content/for-user/"
                                                + UUID.randomUUID()
                                                + "/posts",
                                        admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listPostsForUser_undeclaredQueryParameter_isRejected() {
        TestUser admin = createUser("content_admin_bogus", "admin");
        TestUser author = createUser("content_author_bogus", "user");

        assertThat(
                        getWithAuth(
                                        "/api/v1/admin/content/for-user/"
                                                + author.id()
                                                + "/posts?bogus=1",
                                        admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listCommentsForUser_administrator_seesSoftDeletedComments() {
        TestUser admin = createUser("content_admin_c", "admin");
        TestUser author = createUser("content_author_c", "user");
        UUID postId = insertPost(author.id(), "published", false, "host post");
        insertComment(postId, author.id(), "still here", false);
        insertComment(postId, author.id(), "moderated away", true);

        ResponseEntity<Map> response =
                getWithAuth("/api/v1/admin/content/for-user/" + author.id() + "/comments", admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(contentOf(response)).hasSize(2);
        assertThat(contentOf(response).stream().map(row -> row.get("removed")).toList())
                .containsExactlyInAnyOrder(true, false);
    }

    @Test
    void listCommentsForUser_ordinaryUser_isForbidden() {
        TestUser ordinary = createUser("content_ordinary_c", "user");

        assertThat(
                        getWithAuth(
                                        "/api/v1/admin/content/for-user/"
                                                + ordinary.id()
                                                + "/comments",
                                        ordinary)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void getEntity_removedPostByIdentifier_isReadableByAModerator() {
        TestUser moderator = createUser("entity_mod", "moderator");
        TestUser author = createUser("entity_author", "user");
        UUID postId = insertPost(author.id(), "removed", true, "taken down");

        ResponseEntity<Map> response =
                getWithAuth("/api/v1/admin/content/post/" + postId, moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(response).get("entityId")).isEqualTo(postId.toString());
        assertThat(dataOf(response).get("removed")).isEqualTo(true);
        assertThat(dataOf(response).get("reportType")).isEqualTo("post");
    }

    @Test
    void getEntity_commentByIdentifier_carriesNoLifecycleStatus() {
        TestUser admin = createUser("entity_admin", "admin");
        TestUser author = createUser("entity_author_c", "user");
        UUID postId = insertPost(author.id(), "published", false, "host post");
        UUID commentId = insertComment(postId, author.id(), "under review", false);

        ResponseEntity<Map> response =
                getWithAuth("/api/v1/admin/content/comment/" + commentId, admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(response).get("status")).isNull();
    }

    @Test
    void getEntity_unknownEntityType_isRejected() {
        TestUser admin = createUser("entity_admin_bad", "admin");

        assertThat(
                        getWithAuth("/api/v1/admin/content/banana/" + UUID.randomUUID(), admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getEntity_unknownIdentifier_isNotFound() {
        TestUser admin = createUser("entity_admin_missing", "admin");

        assertThat(
                        getWithAuth("/api/v1/admin/content/post/" + UUID.randomUUID(), admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getEntity_ordinaryUser_isForbidden() {
        TestUser ordinary = createUser("entity_ordinary", "user");
        UUID postId = insertPost(ordinary.id(), "published", false, "own post");

        assertThat(getWithAuth("/api/v1/admin/content/post/" + postId, ordinary).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void getEntity_noToken_isUnauthorized() {
        assertThat(
                        rest.getForEntity(
                                        "/api/v1/admin/content/post/" + UUID.randomUUID(),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void removedContent_staysInvisibleThroughTheOrdinaryPublicEndpoints() {
        // The other half of the pair. A surface that makes removed content visible to a moderator
        // is only correct if it has not also made it visible to everybody else.
        TestUser admin = createUser("parity_admin", "admin");
        TestUser author = createUser("parity_author", "user");
        TestUser viewer = createUser("parity_viewer", "user");
        UUID removedPost = insertPost(author.id(), "removed", true, "taken down");
        UUID draftPost = insertPost(author.id(), "draft", false, "never published");

        assertThat(getWithAuth("/api/v1/admin/content/post/" + removedPost, admin).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(getWithAuth("/api/v1/posts/" + removedPost, viewer).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getWithAuth("/api/v1/posts/" + draftPost, viewer).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(contentOf(getWithAuth("/api/v1/posts/user/" + author.id(), viewer)))
                .as("the ordinary profile listing shows neither")
                .isEmpty();
    }

    private TestUser createUser(String username, String role) {
        return insertUser(username, role, false);
    }

    private TestUser createPrivateUser(String username) {
        return insertUser(username, "user", true);
    }

    private TestUser insertUser(String username, String role, boolean isPrivate) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, username, email, role, status, is_private, is_verified) "
                        + "VALUES (?, ?, ?, CAST(? AS user_role), 'active', ?, TRUE)",
                id,
                username,
                username + "@test.local",
                role,
                isPrivate);
        return new TestUser(id, jwtTokenProvider.generateAccessToken(id, role.toUpperCase(), 0));
    }

    private UUID insertPost(UUID userId, String status, boolean deleted, String caption) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO posts (user_id, post_type, status, caption, deleted_at) "
                        + "VALUES (?, 'image', CAST(? AS post_status), ?, "
                        + "CASE WHEN ? THEN NOW() ELSE NULL END) RETURNING id",
                UUID.class,
                userId,
                status,
                caption,
                deleted);
    }

    private UUID insertComment(UUID postId, UUID userId, String content, boolean deleted) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO comments (post_id, user_id, content, deleted_at) "
                        + "VALUES (?, ?, ?, CASE WHEN ? THEN NOW() ELSE NULL END) RETURNING id",
                UUID.class,
                postId,
                userId,
                content,
                deleted);
    }

    private ResponseEntity<Map> getWithAuth(String path, TestUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> contentOf(ResponseEntity<Map> response) {
        return (List<Map<String, Object>>) dataOf(response).get("content");
    }
}
