package com.app.modules.social.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
import com.app.modules.mail.service.MailService;
import com.app.modules.social.service.SocialService;
import com.app.testsupport.TestContainerImages;

/**
 * Proves blocked-list membership, direction, viewer state, the deleted-user placeholder, and that a
 * tie group sharing one {@code created_at} pages without loss or repetition.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "app.outbox.publisher.enabled=false",
            "app.post.seed.enabled=false",
            "app.hashtag.seed.enabled=false"
        })
@Testcontainers
class BlockedListIT {

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
        r.add("JWT_SECRET", () -> "blocked-list-it-secret-32-chars-minimum!!!!");
        r.add("JWT_ISSUER", () -> "https://blocked-list.it.local");
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
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM blocks");
        jdbcTemplate.update("DELETE FROM follows");
        jdbcTemplate.update("DELETE FROM user_settings");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void getBlockedUsers_returnsOnlyBlockedUsers_newestFirst() {
        UUID viewer = insertUser("viewer");
        UUID blockedA = insertUser("blockedA");
        UUID blockedB = insertUser("blockedB");
        UUID blockedC = insertUser("blockedC");
        insertUser("unrelated");
        block(viewer, blockedA, minutesAgo(3));
        block(viewer, blockedB, minutesAgo(2));
        block(viewer, blockedC, minutesAgo(1));

        List<UserListItemResponse> content =
                socialService.getBlockedUsers(viewer, null, 20).getContent();

        assertThat(content).hasSize(3);
        assertThat(content.stream().map(i -> i.user().id()).toList())
                .containsExactly(blockedC, blockedB, blockedA);
    }

    @Test
    void getBlockedUsers_excludesIncomingBlocks() {
        UUID viewer = insertUser("viewer");
        UUID blockedByViewer = insertUser("blockedByViewer");
        UUID blockedTheViewer = insertUser("blockedTheViewer");
        block(viewer, blockedByViewer, minutesAgo(2));
        block(blockedTheViewer, viewer, minutesAgo(1));

        List<UserListItemResponse> content =
                socialService.getBlockedUsers(viewer, null, 20).getContent();

        assertThat(content).hasSize(1);
        assertThat(content.get(0).user().id()).isEqualTo(blockedByViewer);
    }

    @Test
    void getBlockedUsers_isBlockingAlwaysTrue() {
        // No isBlockedBy field exists on the wire at all under the stealth block model, mutual
        // block or not - only "the viewer blocks this account" is ever disclosed.
        UUID viewer = insertUser("viewer");
        UUID oneWay = insertUser("oneWay");
        UUID mutual = insertUser("mutual");
        block(viewer, oneWay, minutesAgo(2));
        block(viewer, mutual, minutesAgo(1));
        block(mutual, viewer, minutesAgo(1));

        List<UserListItemResponse> content =
                socialService.getBlockedUsers(viewer, null, 20).getContent();

        UserListItemResponse oneWayRow = rowFor(content, oneWay);
        UserListItemResponse mutualRow = rowFor(content, mutual);
        assertThat(oneWayRow.viewerState().isBlocking()).isTrue();
        assertThat(mutualRow.viewerState().isBlocking()).isTrue();
    }

    @Test
    void getBlockedUsers_blockingDropsFollowEdges_soIsFollowingIsFalse() {
        UUID viewer = insertUser("viewer");
        UUID target = insertUser("target");
        follow(viewer, target, "accepted", minutesAgo(10));

        socialService.blockUser(viewer, target);
        List<UserListItemResponse> content =
                socialService.getBlockedUsers(viewer, null, 20).getContent();

        assertThat(content).hasSize(1);
        assertThat(content.get(0).viewerState().isFollowing()).isFalse();
        assertThat(content.get(0).viewerState().isBlocking()).isTrue();
    }

    @Test
    void getBlockedUsers_softDeletedBlockedAccount_returnsPlaceholderAndKeepsPageLength() {
        UUID viewer = insertUser("viewer");
        UUID alive = insertUser("alive");
        UUID ghost = insertUser("ghost");
        block(viewer, alive, minutesAgo(2));
        block(viewer, ghost, minutesAgo(1));
        jdbcTemplate.update("UPDATE users SET deleted_at = NOW() WHERE id = ?", ghost);

        List<UserListItemResponse> content =
                socialService.getBlockedUsers(viewer, null, 20).getContent();

        assertThat(content).hasSize(2);
        UserListItemResponse ghostRow = rowFor(content, ghost);
        assertThat(ghostRow.user().username()).isNull();
        assertThat(ghostRow.user().displayName()).isEqualTo("Deleted user");
        assertThat(rowFor(content, alive).user().username()).isEqualTo("alive");
    }

    @Test
    void getBlockedUsers_tieGroupSharingOneCreatedAt_pagesWithoutLossOrRepetition() {
        UUID viewer = insertUser("viewer");
        OffsetDateTime shared = OffsetDateTime.now().minusMinutes(5);
        Set<UUID> expected = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            UUID blocked = insertUser("blocked" + i);
            expected.add(blocked);
            // Every row shares one created_at. A naive `created_at <` predicate would drop the
            // whole tie group; the (created_at, blocked_id) row-value comparison must not.
            block(viewer, blocked, shared);
        }

        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            CursorPageResponse<UserListItemResponse> response =
                    socialService.getBlockedUsers(viewer, cursor, 5);
            response.getContent().forEach(i -> seen.add(i.user().id()));
            if (!response.getPageInfo().isHasNextPage()) {
                break;
            }
            cursor = response.getPageInfo().getEndCursor();
        }

        assertThat(seen).hasSize(12);
        assertThat(new HashSet<>(seen)).isEqualTo(expected);
    }

    @Test
    void getBlockedUsers_noBlocks_returnsEmptyPage() {
        UUID viewer = insertUser("viewer");

        CursorPageResponse<UserListItemResponse> response =
                socialService.getBlockedUsers(viewer, null, 20);

        assertThat(response.getContent()).isEmpty();
        assertThat(response.getPageInfo().isHasNextPage()).isFalse();
        assertThat(response.getPageInfo().getEndCursor()).isNull();
    }

    @Test
    void getBlockedUsers_limitIsClampedToOneHundred() {
        UUID viewer = insertUser("viewer");
        for (int i = 0; i < 3; i++) {
            block(viewer, insertUser("blocked" + i), minutesAgo(i + 1));
        }

        assertThat(socialService.getBlockedUsers(viewer, null, 5_000).getContent()).hasSize(3);
        assertThat(socialService.getBlockedUsers(viewer, null, 0).getContent()).hasSize(3);
    }

    private static UserListItemResponse rowFor(List<UserListItemResponse> content, UUID id) {
        return content.stream()
                .filter(i -> i.user().id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + id));
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

    private void block(UUID blocker, UUID blocked, OffsetDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO blocks(blocker_id, blocked_id, created_at) VALUES (?, ?, ?)",
                blocker,
                blocked,
                createdAt);
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
}
