package com.app.modules.users.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

import com.app.common.response.CursorPageResponse;
import com.app.common.response.UserListItemResponse;
import com.app.modules.mail.service.MailService;
import com.app.modules.users.service.UserSearchService;
import com.app.testsupport.TestContainerImages;

/**
 * Proves user search excludes self, non-active, deleted, and blocked accounts (in either direction
 * - the stealth block model requires a blocker to be indistinguishable from a nonexistent user, and
 * excluding an account the viewer themselves blocked leaks nothing since {@code GET
 * /social/blocked} is a complete, independent path to find and unblock them), pages
 * deterministically under a follower-count tie, resolves relationships in a constant number of
 * queries, and refuses anonymous callers.
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
@AutoConfigureTestRestTemplate
class UserSearchIT {

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
        r.add("JWT_SECRET", () -> "user-search-it-secret-32-chars-minimum!!!!!");
        r.add("JWT_ISSUER", () -> "https://user-search.it.local");
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

    @Autowired private UserSearchService userSearchService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private TestRestTemplate restTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM blocks");
        jdbcTemplate.update("DELETE FROM follows");
        jdbcTemplate.update("DELETE FROM user_settings");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void searchUsers_excludesTheViewerThemselves() {
        UUID viewer = insertUser("janeviewer", "active", false, 10);
        UUID other = insertUser("janeother", "active", false, 5);

        List<UUID> ids = idsOf(userSearchService.searchUsers(viewer, "jane", null, 20));

        assertThat(ids).containsExactly(other).doesNotContain(viewer);
    }

    @Test
    void searchUsers_excludesNonActiveAccounts() {
        UUID viewer = insertUser("viewer", "active", false, 0);
        UUID active = insertUser("janeactive", "active", false, 5);
        insertUser("janebanned", "banned", false, 900);
        insertUser("janesuspended", "suspended", false, 800);
        insertUser("janedeactivated", "deactivated", false, 700);

        List<UUID> ids = idsOf(userSearchService.searchUsers(viewer, "jane", null, 20));

        // The excluded three all have far higher follower counts, so they would sort first if the
        // status filter were missing.
        assertThat(ids).containsExactly(active);
    }

    @Test
    void searchUsers_excludesSoftDeletedAccounts() {
        UUID viewer = insertUser("viewer", "active", false, 0);
        UUID alive = insertUser("janealive", "active", false, 1);
        insertUser("janeghost", "active", true, 999);

        List<UUID> ids = idsOf(userSearchService.searchUsers(viewer, "jane", null, 20));

        assertThat(ids).containsExactly(alive);
    }

    @Test
    void searchUsers_excludesAccountThatBlockedTheViewer() {
        // The leak the stealth block model closes: a blocker must not appear in the blocked
        // viewer's search results at all, filtered in the query itself rather than flagged.
        UUID viewer = insertUser("viewer", "active", false, 0);
        UUID blocker = insertUser("janeblocker", "active", false, 5);
        UUID ordinary = insertUser("janeordinary", "active", false, 4);
        block(blocker, viewer);

        List<UUID> ids = idsOf(userSearchService.searchUsers(viewer, "jane", null, 20));

        assertThat(ids).containsExactly(ordinary).doesNotContain(blocker);
    }

    @Test
    void searchUsers_excludesAccountTheViewerBlocked() {
        // Filtering both directions is safe here specifically because GET /social/blocked
        // remains a complete, independent path for the viewer to find and unblock this account.
        UUID viewer = insertUser("viewer", "active", false, 0);
        UUID blocked = insertUser("janeblocked", "active", false, 5);
        UUID ordinary = insertUser("janeordinary", "active", false, 4);
        block(viewer, blocked);

        List<UUID> ids = idsOf(userSearchService.searchUsers(viewer, "jane", null, 20));

        assertThat(ids).containsExactly(ordinary).doesNotContain(blocked);
    }

    @Test
    void searchUsers_matchIsCaseInsensitiveSubstring() {
        UUID viewer = insertUser("viewer", "active", false, 0);
        UUID mixedCase = insertUser("JaneDoe", "active", false, 5);

        assertThat(idsOf(userSearchService.searchUsers(viewer, "jane", null, 20)))
                .containsExactly(mixedCase);
        assertThat(idsOf(userSearchService.searchUsers(viewer, "JANE", null, 20)))
                .containsExactly(mixedCase);
        assertThat(idsOf(userSearchService.searchUsers(viewer, "aned", null, 20)))
                .containsExactly(mixedCase);
    }

    @Test
    void searchUsers_ordersByFollowerCountDescending() {
        UUID viewer = insertUser("viewer", "active", false, 0);
        UUID low = insertUser("janelow", "active", false, 1);
        UUID high = insertUser("janehigh", "active", false, 500);
        UUID mid = insertUser("janemid", "active", false, 50);

        assertThat(idsOf(userSearchService.searchUsers(viewer, "jane", null, 20)))
                .containsExactly(high, mid, low);
    }

    @Test
    void searchUsers_tiedFollowerCount_pagesWithoutLossOrRepetition() {
        UUID viewer = insertUser("viewer", "active", false, 0);
        Set<UUID> expected = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            // Every candidate shares one follower_count, so ordering rests entirely on the
            // username and id tiebreakers. Without them the offset window is nondeterministic.
            expected.add(insertUser("janetied" + i, "active", false, 7));
        }

        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            CursorPageResponse<UserListItemResponse> response =
                    userSearchService.searchUsers(viewer, "janetied", cursor, 5);
            seen.addAll(idsOf(response));
            if (!response.getPageInfo().isHasNextPage()) {
                break;
            }
            cursor = response.getPageInfo().getEndCursor();
        }

        assertThat(seen).hasSize(12);
        assertThat(new HashSet<>(seen)).isEqualTo(expected);
    }

    @Test
    void searchUsers_relationshipQueryCountIsConstantAcrossThreePageSizes() {
        UUID viewer = insertUser("viewer", "active", false, 0);
        for (int i = 0; i < 12; i++) {
            UUID other = insertUser("janeuser" + i, "active", false, 100 - i);
            if (i % 2 == 0) {
                block(viewer, other);
            }
        }

        Statistics stats = statistics();
        userSearchService.searchUsers(viewer, "janeuser", null, 2);

        stats.clear();
        userSearchService.searchUsers(viewer, "janeuser", null, 2);
        long size2 = stats.getPrepareStatementCount();

        stats.clear();
        userSearchService.searchUsers(viewer, "janeuser", null, 6);
        long size6 = stats.getPrepareStatementCount();

        stats.clear();
        userSearchService.searchUsers(viewer, "janeuser", null, 12);
        long size12 = stats.getPrepareStatementCount();

        assertThat(size6).isEqualTo(size2);
        assertThat(size12).isEqualTo(size2);
    }

    @Test
    void searchUsers_anonymousCaller_isRefused() {
        insertUser("janedoe", "active", false, 5);

        // Pins the SecurityConfig ordering. The `/users/{userId}` permitAll matcher also matches
        // `/users/search`, so without an explicit authenticated() rule registered earlier this
        // endpoint would silently serve anonymous callers.
        ResponseEntity<String> response =
                restTemplate.getForEntity("/api/v1/users/search?q=jane", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void searchUsers_singleCharacterQuery_isRefusedOverHttp() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/api/v1/users/search?q=a", String.class);

        // Refused before authentication even matters, so a one-character enumeration sweep cannot
        // be mounted regardless of credentials.
        assertThat(response.getStatusCode()).isIn(HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED);
    }

    private static List<UUID> idsOf(CursorPageResponse<UserListItemResponse> page) {
        return idsOf(page.getContent());
    }

    private static List<UUID> idsOf(List<UserListItemResponse> content) {
        return content.stream().map(i -> i.user().id()).toList();
    }

    private static UserListItemResponse rowFor(List<UserListItemResponse> content, UUID id) {
        return content.stream()
                .filter(i -> i.user().id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + id));
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private UUID insertUser(String username, String status, boolean deleted, int followerCount) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users(username, email, display_name, status, is_verified,"
                        + " follower_count, deleted_at)"
                        + " VALUES (?, ?, ?, ?::user_status, false, ?, ?) RETURNING id",
                UUID.class,
                username,
                username + "@example.com",
                username,
                status,
                followerCount,
                deleted ? java.time.OffsetDateTime.now() : null);
    }

    private void block(UUID blocker, UUID blocked) {
        jdbcTemplate.update(
                "INSERT INTO blocks(blocker_id, blocked_id) VALUES (?, ?)", blocker, blocked);
    }
}
