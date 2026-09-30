package com.app.modules.social.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.modules.social.entity.Follow;
import com.app.testsupport.TestContainerImages;

/**
 * Guards against keyset row loss when accepted follows share a boundary {@code created_at}. Pages
 * with a size that cuts into the tie-group and asserts every follow is returned exactly once; a
 * dropped row proves a strict-inequality regression, a duplicated row a non-strict one. No blocks
 * exist, so the block-exclusion subqueries pass every row.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FollowKeysetRowLossIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private static final OffsetDateTime SHARED_INSTANT =
            OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final int PAGE_SIZE = 2;

    private final FollowRepository followRepository;
    private final JdbcClient jdbcClient;

    FollowKeysetRowLossIT(FollowRepository followRepository, JdbcClient jdbcClient) {
        this.followRepository = followRepository;
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void followers_tieGroupOnCreatedAt_pagesEveryRowExactlyOnce() {
        UUID viewer = insertUser("viewer");
        UUID target = insertUser("target");
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID follower = insertUser("follower_" + i);
            insertFollow(follower, target, SHARED_INSTANT);
            expected.add(follower);
        }

        List<UUID> seen = new ArrayList<>();
        List<Follow> page = followRepository.findFirstFollowers(target, viewer, page());
        int guard = 0;
        while (!page.isEmpty() && guard++ < 100) {
            page.forEach(f -> seen.add(f.getId().getFollowerId()));
            Follow last = page.get(page.size() - 1);
            page =
                    followRepository.findFollowersBefore(
                            target,
                            viewer,
                            last.getCreatedAt(),
                            last.getId().getFollowerId(),
                            page());
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void following_tieGroupOnCreatedAt_pagesEveryRowExactlyOnce() {
        UUID viewer = insertUser("viewer");
        UUID follower = insertUser("follower");
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID followee = insertUser("followee_" + i);
            insertFollow(follower, followee, SHARED_INSTANT);
            expected.add(followee);
        }

        List<UUID> seen = new ArrayList<>();
        List<Follow> page = followRepository.findFirstFollowing(follower, viewer, page());
        int guard = 0;
        while (!page.isEmpty() && guard++ < 100) {
            page.forEach(f -> seen.add(f.getId().getFollowingId()));
            Follow last = page.get(page.size() - 1);
            page =
                    followRepository.findFollowingBefore(
                            follower,
                            viewer,
                            last.getCreatedAt(),
                            last.getId().getFollowingId(),
                            page());
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void pendingRequests_tieGroupOnCreatedAt_pagesEveryRowExactlyOnce() {
        UUID target = insertUser("target");
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID requester = insertUser("requester_" + i);
            insertPendingFollow(requester, target, SHARED_INSTANT);
            expected.add(requester);
        }

        List<UUID> seen = new ArrayList<>();
        List<Follow> page = followRepository.findFirstPendingRequests(target, page());
        int guard = 0;
        while (!page.isEmpty() && guard++ < 100) {
            page.forEach(f -> seen.add(f.getId().getFollowerId()));
            Follow last = page.get(page.size() - 1);
            page =
                    followRepository.findPendingRequestsBefore(
                            target, last.getCreatedAt(), last.getId().getFollowerId(), page());
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static PageRequest page() {
        return PageRequest.of(0, PAGE_SIZE);
    }

    private UUID insertUser(String username) {
        return jdbcClient
                .sql(
                        """
						INSERT INTO users(username, email, display_name)
						VALUES (:username, :email, :displayName)
						RETURNING id
						""")
                .param("username", username)
                .param("email", username + "@example.com")
                .param("displayName", username)
                .query(UUID.class)
                .single();
    }

    private void insertFollow(UUID followerId, UUID followingId, OffsetDateTime createdAt) {
        jdbcClient
                .sql(
                        "INSERT INTO follows(follower_id, following_id, status, created_at)"
                                + " VALUES (:followerId, :followingId, 'accepted', :createdAt)")
                .param("followerId", followerId)
                .param("followingId", followingId)
                .param("createdAt", createdAt)
                .update();
    }

    private void insertPendingFollow(UUID followerId, UUID followingId, OffsetDateTime createdAt) {
        jdbcClient
                .sql(
                        "INSERT INTO follows(follower_id, following_id, status, created_at)"
                                + " VALUES (:followerId, :followingId, 'pending', :createdAt)")
                .param("followerId", followerId)
                .param("followingId", followingId)
                .param("createdAt", createdAt)
                .update();
    }
}
