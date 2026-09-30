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
class AdminHashtagControllerIT {

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
        registry.add("JWT_SECRET", () -> "admin-hashtag-it-secret-32-chars-minimum!!");
        registry.add("JWT_ISSUER", () -> "https://adminhashtag.it.local");
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
        jdbcTemplate.update("DELETE FROM hashtag_trending");
        jdbcTemplate.update("DELETE FROM post_hashtags");
        jdbcTemplate.update("DELETE FROM posts");
        jdbcTemplate.update("DELETE FROM hashtags");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void listHashtags_moderator_returnsForbidden() {
        TestUser moderator = createUser("hashtag_list_mod", "moderator");

        assertThat(getWithAuth("/api/v1/admin/hashtags", moderator).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void listHashtags_administrator_spansEveryStatus() {
        TestUser admin = createUser("hashtag_list_admin", "admin");
        insertHashtag("listactive", "active");
        insertHashtag("listbanned", "banned");
        insertHashtag("listdeleted", "deleted");

        ResponseEntity<Map> response = getWithAuth("/api/v1/admin/hashtags?limit=50", admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(namesOf(response)).contains("listactive", "listbanned", "listdeleted");
        assertThat(statusesOf(response)).contains("active", "banned", "deleted");
    }

    @Test
    void listHashtags_createdAtTie_pagesStably() {
        TestUser admin = createUser("hashtag_page_admin", "admin");
        for (int i = 0; i < 6; i++) {
            insertHashtag("tiedtag" + i, "active", "2026-01-01T00:00:00Z");
        }

        ResponseEntity<Map> first = getWithAuth("/api/v1/admin/hashtags?limit=3", admin);
        String endCursor = (String) pageInfoOf(first).get("endCursor");
        ResponseEntity<Map> second =
                getWithAuth("/api/v1/admin/hashtags?limit=3&cursor=" + endCursor, admin);

        List<String> firstIds = idsOf(first);
        List<String> secondIds = idsOf(second);
        assertThat(firstIds).hasSize(3);
        assertThat(secondIds).hasSize(3);
        assertThat(secondIds).doesNotContainAnyElementsOf(firstIds);
    }

    @Test
    void searchHashtags_cursorFromTheListing_isRejected() {
        TestUser admin = createUser("hashtag_scope_admin", "admin");
        insertHashtag("scopetag", "active");
        ResponseEntity<Map> listing = getWithAuth("/api/v1/admin/hashtags?limit=1", admin);
        String listCursor = (String) pageInfoOf(listing).get("endCursor");

        ResponseEntity<Map> response =
                getWithAuth("/api/v1/admin/hashtags/search?q=scope&cursor=" + listCursor, admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void searchHashtags_partialName_spansEveryStatus() {
        TestUser admin = createUser("hashtag_search_admin", "admin");
        insertHashtag("needleactive", "active");
        insertHashtag("needlebanned", "banned");
        insertHashtag("unrelated", "active");

        ResponseEntity<Map> response =
                getWithAuth("/api/v1/admin/hashtags/search?q=needle&limit=50", admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(namesOf(response)).containsExactlyInAnyOrder("needleactive", "needlebanned");
    }

    @Test
    void createHashtag_bannedTerm_persistsTheRowAndWritesAnAuditRow() {
        TestUser admin = createUser("hashtag_create_admin", "admin");

        ResponseEntity<Map> response =
                postWithAuth(
                        "/api/v1/admin/hashtags",
                        Map.of("name", "#PreBanned", "status", "banned", "note", "ahead of it"),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> row = hashtagRow("prebanned");
        assertThat(row.get("status")).isEqualTo("banned");
        assertThat(row.get("status_note")).isEqualTo("ahead of it");
        assertThat(row.get("status_by")).isEqualTo(admin.id());
        assertThat(row.get("status_at")).isNotNull();
        assertThat(auditActionTypes()).containsExactly("create_hashtag");
    }

    @Test
    void createHashtag_existingName_returnsConflict() {
        TestUser admin = createUser("hashtag_dupe_admin", "admin");
        insertHashtag("takentag", "active");

        ResponseEntity<Map> response =
                postWithAuth(
                        "/api/v1/admin/hashtags",
                        Map.of("name", "takentag", "status", "banned", "note", "n"),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("HASHTAG_ALREADY_EXISTS");
        assertThat(auditActionTypes()).isEmpty();
    }

    @Test
    void createHashtag_deletedStatus_returnsBadRequest() {
        TestUser admin = createUser("hashtag_createdel_admin", "admin");

        ResponseEntity<Map> response =
                postWithAuth(
                        "/api/v1/admin/hashtags",
                        Map.of("name", "borndead", "status", "deleted", "note", "n"),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateHashtag_bodyCarryingAName_isRejected() {
        TestUser admin = createUser("hashtag_rename_admin", "admin");
        UUID hashtagId = insertHashtag("renameme", "active");

        ResponseEntity<Map> response =
                patchWithAuth(
                        "/api/v1/admin/hashtags/" + hashtagId,
                        Map.of("status", "banned", "note", "n", "name", "renamed"),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(hashtagRow("renameme").get("status")).isEqualTo("active");
        assertThat(auditActionTypes()).isEmpty();
    }

    @Test
    void updateHashtag_ban_recordsTheDecisionAndWritesABanAuditRow() {
        TestUser admin = createUser("hashtag_ban_admin", "admin");
        UUID hashtagId = insertHashtag("banme", "active");

        ResponseEntity<Map> response =
                patchWithAuth(
                        "/api/v1/admin/hashtags/" + hashtagId,
                        Map.of("status", "banned", "note", "coordinated abuse"),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> row = hashtagRow("banme");
        assertThat(row.get("status")).isEqualTo("banned");
        assertThat(row.get("status_note")).isEqualTo("coordinated abuse");
        assertThat(row.get("status_by")).isEqualTo(admin.id());
        assertThat(row.get("status_at")).isNotNull();
        assertThat(auditActionTypes()).containsExactly("ban_hashtag");
        assertThat(auditTargetEntityIds()).containsExactly(hashtagId);
    }

    @Test
    void updateHashtag_ban_purgesTheTrendingRowsWithinTheSameTransaction() {
        TestUser admin = createUser("hashtag_trend_admin", "admin");
        UUID hashtagId = insertHashtag("trendingnow", "active");
        insertTrending(hashtagId);

        ResponseEntity<Map> response =
                patchWithAuth(
                        "/api/v1/admin/hashtags/" + hashtagId,
                        Map.of("status", "banned", "note", "n"),
                        admin);

        // Read straight after the response, with no job run in between: the purge must already be
        // committed alongside the status change rather than waiting for the next snapshot.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countTrending(hashtagId)).isZero();
    }

    @Test
    void updateHashtag_ban_enqueuesAnIndexSyncEvent() {
        TestUser admin = createUser("hashtag_sync_admin", "admin");
        UUID hashtagId = insertHashtag("syncme", "active");

        patchWithAuth(
                "/api/v1/admin/hashtags/" + hashtagId,
                Map.of("status", "banned", "note", "n"),
                admin);

        assertThat(outboxEventTypesFor(hashtagId)).containsExactly("hashtag.index.upsert.v1");
    }

    @Test
    void updateHashtag_statusAlreadyHeld_returnsConflict() {
        TestUser admin = createUser("hashtag_noop_admin", "admin");
        UUID hashtagId = insertHashtag("alreadybanned", "banned");

        ResponseEntity<Map> response =
                patchWithAuth(
                        "/api/v1/admin/hashtags/" + hashtagId,
                        Map.of("status", "banned", "note", "n"),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("ADMIN_INVALID_TRANSITION");
        assertThat(auditActionTypes()).isEmpty();
    }

    @Test
    void updateHashtag_unban_returnsTheTagToActiveAndRecordsAnUnbanRow() {
        TestUser admin = createUser("hashtag_unban_admin", "admin");
        UUID hashtagId = insertHashtag("unbanme", "banned");

        ResponseEntity<Map> response =
                patchWithAuth(
                        "/api/v1/admin/hashtags/" + hashtagId,
                        Map.of("status", "active", "note", "appeal upheld"),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(hashtagRow("unbanme").get("status")).isEqualTo("active");
        assertThat(auditActionTypes()).containsExactly("unban_hashtag");
    }

    @Test
    void deleteHashtag_leavesTheRowItsAssociationsAndItsCounterIntact() {
        TestUser admin = createUser("hashtag_delete_admin", "admin");
        TestUser author = createUser("hashtag_delete_author", "user");
        UUID hashtagId = insertHashtag("deleteme", "active");
        UUID postId = insertPost(author.id());
        insertPostHashtag(postId, hashtagId);
        int countBefore = postCountOf(hashtagId);

        ResponseEntity<Map> response =
                deleteWithAuth(
                        "/api/v1/admin/hashtags/" + hashtagId,
                        Map.of("reason", "typo duplicate"),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countBefore).isEqualTo(1);
        assertThat(hashtagRow("deleteme").get("status")).isEqualTo("deleted");
        assertThat(countPostHashtags(hashtagId)).isEqualTo(1);
        assertThat(postCountOf(hashtagId)).isEqualTo(countBefore);
        assertThat(auditActionTypes()).containsExactly("delete_hashtag");
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

    private UUID insertHashtag(String name, String status) {
        return insertHashtag(name, status, null);
    }

    private UUID insertHashtag(String name, String status, String createdAt) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO hashtags (name, status, created_at) "
                        + "VALUES (?, CAST(? AS hashtag_status), "
                        + "COALESCE(CAST(? AS timestamptz), NOW())) RETURNING id",
                UUID.class,
                name,
                status,
                createdAt);
    }

    private UUID insertPost(UUID authorId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO posts (user_id, caption, post_type, status) "
                        + "VALUES (?, 'caption', 'text', 'published') RETURNING id",
                UUID.class,
                authorId);
    }

    private void insertPostHashtag(UUID postId, UUID hashtagId) {
        jdbcTemplate.update(
                "INSERT INTO post_hashtags (post_id, hashtag_id) VALUES (?, ?)", postId, hashtagId);
    }

    private void insertTrending(UUID hashtagId) {
        jdbcTemplate.update(
                "INSERT INTO hashtag_trending "
                        + "(hashtag_id, period_start, period_end, post_count, rank) "
                        + "VALUES (?, NOW(), NOW(), 5, 1)",
                hashtagId);
    }

    private Map<String, Object> hashtagRow(String name) {
        return jdbcTemplate.queryForMap("SELECT * FROM hashtags WHERE name = ?", name);
    }

    private int postCountOf(UUID hashtagId) {
        return jdbcTemplate.queryForObject(
                "SELECT post_count FROM hashtags WHERE id = ?", Integer.class, hashtagId);
    }

    private long countPostHashtags(UUID hashtagId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_hashtags WHERE hashtag_id = ?", Long.class, hashtagId);
    }

    private long countTrending(UUID hashtagId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM hashtag_trending WHERE hashtag_id = ?",
                Long.class,
                hashtagId);
    }

    private List<String> auditActionTypes() {
        return jdbcTemplate.queryForList(
                "SELECT CAST(action_type AS text) FROM admin_actions ORDER BY created_at",
                String.class);
    }

    private List<UUID> auditTargetEntityIds() {
        return jdbcTemplate.queryForList(
                "SELECT target_entity_id FROM admin_actions ORDER BY created_at", UUID.class);
    }

    private List<String> outboxEventTypesFor(UUID aggregateId) {
        return jdbcTemplate.queryForList(
                "SELECT event_type FROM outbox_events WHERE aggregate_id = ? ORDER BY created_at",
                String.class,
                aggregateId);
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

    private ResponseEntity<Map> deleteWithAuth(String path, Object body, TestUser user) {
        return rest.exchange(
                path, HttpMethod.DELETE, new HttpEntity<>(body, authHeaders(user)), Map.class);
    }

    private HttpHeaders authHeaders(TestUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> contentOf(ResponseEntity<Map> response) {
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        return (List<Map<String, Object>>) data.get("content");
    }

    private static List<String> namesOf(ResponseEntity<Map> response) {
        return contentOf(response).stream().map(row -> (String) row.get("name")).toList();
    }

    private static List<String> idsOf(ResponseEntity<Map> response) {
        return contentOf(response).stream().map(row -> (String) row.get("id")).toList();
    }

    private static List<String> statusesOf(ResponseEntity<Map> response) {
        return contentOf(response).stream().map(row -> (String) row.get("status")).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pageInfoOf(ResponseEntity<Map> response) {
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        return (Map<String, Object>) data.get("pageInfo");
    }

    @Test
    void unauthenticatedRequest_isRejectedOnEveryRegistryOperation() {
        UUID any = UUID.randomUUID();

        assertThat(rest.getForEntity("/api/v1/admin/hashtags", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(
                        rest.getForEntity("/api/v1/admin/hashtags/search?q=abc", Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(
                        rest.exchange(
                                        "/api/v1/admin/hashtags/" + any,
                                        HttpMethod.PATCH,
                                        new HttpEntity<>(Map.of("status", "banned")),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void undeclaredQueryParameter_isRejectedOnEveryRegistryListing() {
        TestUser admin = createUser("hashtag_bogus_admin", "admin");

        assertThat(getWithAuth("/api/v1/admin/hashtags?bogus=1", admin).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(
                        getWithAuth("/api/v1/admin/hashtags/search?q=abc&bogus=1", admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
