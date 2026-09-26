package com.app.modules.post.controller;

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
import com.app.modules.notification.messaging.NotificationEventTypes;

/**
 * Pins the banned-hashtag boundary on every post write path, and the line that boundary must not
 * cross: a banned tag is refused on new writes and hidden from hashtag surfaces, while the posts
 * that already carry it keep appearing everywhere they appeared before.
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
class PostBannedHashtagIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("JWT_SECRET", () -> "post-banned-hashtag-it-secret-32-chars!!!!");
        registry.add("JWT_ISSUER", () -> "https://postbanned.it.local");
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
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM admin_actions");
        jdbcTemplate.update("DELETE FROM post_edit_history");
        jdbcTemplate.update("DELETE FROM post_hashtags");
        jdbcTemplate.update("DELETE FROM posts");
        jdbcTemplate.update("DELETE FROM hashtags");
        jdbcTemplate.update("DELETE FROM follows");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void createPost_textCaptionNamingABannedTag_isRefusedAndWritesNothing() {
        TestUser author = createUser("banned_text_author", "user");
        insertHashtag("auditban", "banned");

        ResponseEntity<Map> response =
                postWithAuth(
                        "/api/v1/posts",
                        Map.of(
                                "postType", "text",
                                "caption", "hello #auditban and #cleantag",
                                "status", "published"),
                        author);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().get("code")).isEqualTo("POST_BANNED_HASHTAG");
        assertThat(bannedTagsOf(response)).containsExactly("auditban");
        assertThat(countPosts()).isZero();
        assertThat(countOutboxEvents()).isZero();
        assertThat(countPostHashtags()).isZero();
        // The clean tag in the same caption must not have been created either: the check runs
        // before any mutation, so a rejected write leaves the registry untouched.
        assertThat(countHashtagsNamed("cleantag")).isZero();
    }

    @Test
    void createPost_mediaBranchCaptionNamingABannedTag_isRefusedBeforeTheMediaLookup() {
        TestUser author = createUser("banned_media_author", "user");
        insertHashtag("auditban", "banned");

        ResponseEntity<Map> response =
                postWithAuth(
                        "/api/v1/posts",
                        Map.of(
                                "postType", "image",
                                "caption", "shot #auditban",
                                "mediaIds", List.of(UUID.randomUUID().toString())),
                        author);

        // 422 rather than the 404 the unknown media id would otherwise produce: the banned-tag
        // check runs first, which is what proves it precedes every mutation on this branch too.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().get("code")).isEqualTo("POST_BANNED_HASHTAG");
        assertThat(countPosts()).isZero();
    }

    @Test
    void createPost_captionWithNoBannedTag_succeeds() {
        TestUser author = createUser("clean_author", "user");
        insertHashtag("auditban", "banned");

        ResponseEntity<Map> response =
                postWithAuth(
                        "/api/v1/posts",
                        Map.of("postType", "text", "caption", "hello #cleantag"),
                        author);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(countPostHashtags()).isEqualTo(1);
    }

    @Test
    void updateCaption_addingABannedTag_isRefusedAndWritesNoEditHistoryRow() {
        TestUser author = createUser("edit_author", "user");
        UUID postId = createPublishedPost(author, "first #cleantag");
        insertHashtag("auditban", "banned");

        ResponseEntity<Map> response =
                patchWithAuth(
                        "/api/v1/posts/" + postId, Map.of("caption", "revised #auditban"), author);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(bannedTagsOf(response)).containsExactly("auditban");
        assertThat(countEditHistory(postId)).isZero();
        assertThat(captionOf(postId)).isEqualTo("first #cleantag");
    }

    @Test
    void updateCaption_removingTheBannedTag_succeeds() {
        TestUser author = createUser("edit_clean_author", "user");
        UUID postId = createPublishedPost(author, "first #cleantag");
        insertHashtag("auditban", "banned");

        ResponseEntity<Map> response =
                patchWithAuth(
                        "/api/v1/posts/" + postId,
                        Map.of("caption", "revised #stillclean"),
                        author);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countEditHistory(postId)).isEqualTo(1);
    }

    @Test
    void transitionStatus_publishingADraftTaggedAfterTheBan_isRefused() {
        TestUser author = createUser("draft_author", "user");
        UUID postId = createDraftPost(author, "queued #auditban");
        insertHashtag("auditban", "banned");

        ResponseEntity<Map> response =
                patchWithAuth(
                        "/api/v1/posts/" + postId + "/status",
                        Map.of("targetStatus", "published"),
                        author);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(bannedTagsOf(response)).containsExactly("auditban");
        assertThat(statusOf(postId)).isEqualTo("draft");
        assertThat(countPostHashtags()).isZero();
    }

    @Test
    void transitionStatus_republishingAnArchivedPostTaggedAfterTheBan_isRefused() {
        TestUser author = createUser("archive_author", "user");
        UUID postId = createPublishedPost(author, "live #laterbanned");
        assertThat(
                        patchWithAuth(
                                        "/api/v1/posts/" + postId + "/status",
                                        Map.of("targetStatus", "archived"),
                                        author)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        banHashtag("laterbanned");

        ResponseEntity<Map> response =
                patchWithAuth(
                        "/api/v1/posts/" + postId + "/status",
                        Map.of("targetStatus", "published"),
                        author);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(statusOf(postId)).isEqualTo("archived");
    }

    @Test
    void restorePost_captionNamingABannedTag_succeedsAndRecordsWhatWasStripped() {
        TestUser author = createUser("restore_author", "user");
        TestUser admin = createUser("restore_admin", "admin");
        UUID postId = createPublishedPost(author, "post #keptone and #laterbanned");
        assertThat(
                        patchWithAuth(
                                        "/api/v1/admin/posts/" + postId + "/remove",
                                        Map.of("reason", "under review"),
                                        admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        banHashtag("laterbanned");

        ResponseEntity<Map> response =
                patchWithAuth(
                        "/api/v1/admin/posts/" + postId + "/restore",
                        Map.of("reason", "removed by mistake"),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusOf(postId)).isEqualTo("published");
        assertThat(associatedHashtagNames(postId)).containsExactly("keptone");
        assertThat(restoreMetadata()).contains("laterbanned");
        // The moderator is told, not only the audit log. Reading the audit row back is not a
        // remedy for a moderator who has just been shown "Post restored" and no more.
        assertThat(dataOf(response).get("remainingBannedHashtags"))
                .isEqualTo(List.of("laterbanned"));
    }

    @Test
    void restorePost_repeatedOnTheSamePost_namesTheSameRemainingBannedTagEveryTime() {
        TestUser author = createUser("repeat_restore_author", "user");
        TestUser admin = createUser("repeat_restore_admin", "admin");
        UUID postId = createPublishedPost(author, "post #keptone and #laterbanned");
        patchWithAuth(
                "/api/v1/admin/posts/" + postId + "/remove",
                Map.of("reason", "under review"),
                admin);
        banHashtag("laterbanned");

        // Three cycles. The association is gone after the first restore, so a field holding the
        // delta of one action would be empty on the second and third. This one holds the post's
        // present state instead, which is why it keeps naming the tag: the caption still says
        // #laterbanned and the post still does not carry it.
        for (int attempt = 1; attempt <= 3; attempt++) {
            ResponseEntity<Map> restored =
                    patchWithAuth(
                            "/api/v1/admin/posts/" + postId + "/restore",
                            Map.of("reason", "removed by mistake"),
                            admin);

            assertThat(restored.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(dataOf(restored).get("remainingBannedHashtags"))
                    .as("restore attempt %d", attempt)
                    .isEqualTo(List.of("laterbanned"));
            assertThat(associatedHashtagNames(postId)).containsExactly("keptone");

            if (attempt < 3) {
                patchWithAuth(
                        "/api/v1/admin/posts/" + postId + "/remove",
                        Map.of("reason", "under review"),
                        admin);
            }
        }
    }

    @Test
    void restorePost_toDraft_createsNoAssociationsWhateverTheTagStatus() {
        TestUser author = createUser("draft_restore_author", "user");
        TestUser admin = createUser("draft_restore_admin", "admin");
        // The draft is written before the ban, which is the only order that reaches this state: a
        // draft naming an already-banned tag is refused at creation. A draft also creates no
        // hashtag rows, so the tag row is seeded here rather than by the post.
        UUID postId = createDraftPost(author, "queued #keptone and #laterbanned");
        insertHashtag("laterbanned", "banned");
        patchWithAuth(
                "/api/v1/admin/posts/" + postId + "/remove",
                Map.of("reason", "under review"),
                admin);
        jdbcTemplate.update("DELETE FROM outbox_events");

        ResponseEntity<Map> response =
                patchWithAuth(
                        "/api/v1/admin/posts/" + postId + "/restore",
                        Map.of("reason", "removed by mistake"),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusOf(postId)).isEqualTo("draft");
        assertThat(associatedHashtagNames(postId)).isEmpty();
        assertThat(countOutboxEvents()).isEqualTo(1);
        assertThat(countOutboxEvents(NotificationEventTypes.NOTIFICATION_UPSERTED_V1)).isEqualTo(1);
        // Nothing was dropped because nothing was re-derived, which is not the same as a restore
        // that silently lost a tag, so the list is empty rather than naming the banned one.
        assertThat(dataOf(response).get("remainingBannedHashtags")).isEqualTo(List.of());
    }

    @Test
    void getPostById_deletedHashtag_isOmittedWhileABannedOneIsStillListed() {
        TestUser author = createUser("detail_author", "user");
        UUID postId = createPublishedPost(author, "post #keptone #laterbanned #laterdeleted");
        banHashtag("laterbanned");
        deleteHashtag("laterdeleted");

        ResponseEntity<Map> response = getWithAuth("/api/v1/posts/" + postId, author);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(hashtagNamesOf(response)).containsExactlyInAnyOrder("keptone", "laterbanned");
        // The caption is untouched: the frontend keeps the literal text and simply does not link
        // the tag it was not given.
        assertThat(response.getBody().toString()).contains("#laterdeleted");
    }

    @Test
    void banningATag_changesNeitherTheFeedNorTheProfileListing() {
        TestUser author = createUser("visible_author", "user");
        TestUser follower = createUser("visible_follower", "user");
        follow(follower.id(), author.id());
        UUID postId = createPublishedPost(author, "still here #laterbanned");

        banHashtag("laterbanned");

        assertThat(idsOf(getWithAuth("/api/v1/posts/feed", follower))).contains(postId.toString());
        assertThat(idsOf(getWithAuth("/api/v1/posts/user/" + author.id(), follower)))
                .contains(postId.toString());
        assertThat(getWithAuth("/api/v1/posts/" + postId, follower).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private TestUser createUser(String username, String role) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, username, email, role, status, is_private, is_verified) "
                        + "VALUES (?, ?, ?, CAST(? AS user_role), 'active', FALSE, TRUE)",
                id,
                username,
                username + "@test.local",
                role);
        return new TestUser(id, jwtTokenProvider.generateAccessToken(id, role.toUpperCase(), 0));
    }

    private void follow(UUID followerId, UUID followingId) {
        jdbcTemplate.update(
                "INSERT INTO follows (follower_id, following_id, status) "
                        + "VALUES (?, ?, 'accepted')",
                followerId,
                followingId);
    }

    private UUID createPublishedPost(TestUser author, String caption) {
        ResponseEntity<Map> response =
                postWithAuth(
                        "/api/v1/posts",
                        Map.of("postType", "text", "caption", caption, "status", "published"),
                        author);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) dataOf(response).get("id"));
    }

    private UUID createDraftPost(TestUser author, String caption) {
        ResponseEntity<Map> response =
                postWithAuth(
                        "/api/v1/posts",
                        Map.of("postType", "text", "caption", caption, "status", "draft"),
                        author);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) dataOf(response).get("id"));
    }

    private void insertHashtag(String name, String status) {
        jdbcTemplate.update(
                "INSERT INTO hashtags (name, status) VALUES (?, CAST(? AS hashtag_status))",
                name,
                status);
    }

    private void banHashtag(String name) {
        setHashtagStatus(name, "banned");
    }

    private void deleteHashtag(String name) {
        setHashtagStatus(name, "deleted");
    }

    private void setHashtagStatus(String name, String status) {
        int updated =
                jdbcTemplate.update(
                        "UPDATE hashtags SET status = CAST(? AS hashtag_status) WHERE name = ?",
                        status,
                        name);
        assertThat(updated).as("hashtag %s must exist before its status is changed", name).isOne();
    }

    private long countPosts() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM posts", Long.class);
    }

    private long countPostHashtags() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM post_hashtags", Long.class);
    }

    private long countOutboxEvents() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class);
    }

    private long countOutboxEvents(String eventType) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = ?", Long.class, eventType);
    }

    private long countHashtagsNamed(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM hashtags WHERE name = ?", Long.class, name);
    }

    private long countEditHistory(UUID postId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_edit_history WHERE post_id = ?", Long.class, postId);
    }

    private String captionOf(UUID postId) {
        return jdbcTemplate.queryForObject(
                "SELECT caption FROM posts WHERE id = ?", String.class, postId);
    }

    private String statusOf(UUID postId) {
        return jdbcTemplate.queryForObject(
                "SELECT CAST(status AS text) FROM posts WHERE id = ?", String.class, postId);
    }

    private List<String> associatedHashtagNames(UUID postId) {
        return jdbcTemplate.queryForList(
                "SELECT h.name FROM post_hashtags ph JOIN hashtags h ON h.id = ph.hashtag_id "
                        + "WHERE ph.post_id = ? ORDER BY h.name",
                String.class,
                postId);
    }

    private String restoreMetadata() {
        return jdbcTemplate.queryForObject(
                "SELECT CAST(metadata AS text) FROM admin_actions "
                        + "WHERE action_type = 'restore_post' ORDER BY created_at DESC LIMIT 1",
                String.class);
    }

    private ResponseEntity<Map> getWithAuth(String path, TestUser user) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(user)), Map.class);
    }

    private ResponseEntity<Map> postWithAuth(String path, Object body, TestUser user) {
        return rest.exchange(
                path, HttpMethod.POST, new HttpEntity<>(body, authHeaders(user)), Map.class);
    }

    private ResponseEntity<Map> patchWithAuth(String path, Object body, TestUser user) {
        return rest.exchange(
                path, HttpMethod.PATCH, new HttpEntity<>(body, authHeaders(user)), Map.class);
    }

    private HttpHeaders authHeaders(TestUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<String> bannedTagsOf(ResponseEntity<Map> response) {
        return (List<String>) dataOf(response).get("bannedTags");
    }

    @SuppressWarnings("unchecked")
    private static List<String> hashtagNamesOf(ResponseEntity<Map> response) {
        List<Map<String, Object>> hashtags =
                (List<Map<String, Object>>) dataOf(response).get("hashtags");
        return hashtags.stream().map(row -> (String) row.get("name")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> idsOf(ResponseEntity<Map> response) {
        List<Map<String, Object>> content =
                (List<Map<String, Object>>) dataOf(response).get("content");
        return content.stream().map(row -> (String) row.get("id")).toList();
    }
}
