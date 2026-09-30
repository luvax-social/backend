package com.app.modules.social.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.modules.social.entity.Follow;
import com.app.modules.social.enums.FollowStatus;
import com.app.testsupport.TestContainerImages;

@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FollowRepositoryIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private final FollowRepository followRepository;
    private final BlockRepository blockRepository;
    private final JdbcClient jdbcClient;

    FollowRepositoryIT(
            FollowRepository followRepository,
            BlockRepository blockRepository,
            JdbcClient jdbcClient) {
        this.followRepository = followRepository;
        this.blockRepository = blockRepository;
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void insertAcceptedFollow_persistsRowAndTriggerUpdatesCounters() {
        UUID followerId = insertUser("alice", false);
        UUID followingId = insertUser("bob", false);

        Follow follow = followRepository.insert(followerId, followingId, FollowStatus.ACCEPTED);

        assertThat(follow.getCreatedAt()).isNotNull();
        assertThat(follow.getStatus()).isEqualTo(FollowStatus.ACCEPTED);
        assertThat(userCounts(followerId)).containsEntry("following_count", 1);
        assertThat(userCounts(followingId)).containsEntry("follower_count", 1);
    }

    @Test
    void insertPendingFollow_doesNotUpdateCounters() {
        UUID followerId = insertUser("carol", false);
        UUID followingId = insertUser("dave", true);

        Follow follow = followRepository.insert(followerId, followingId, FollowStatus.PENDING);

        assertThat(follow.getStatus()).isEqualTo(FollowStatus.PENDING);
        assertThat(userCounts(followerId)).containsEntry("following_count", 0);
        assertThat(userCounts(followingId)).containsEntry("follower_count", 0);
    }

    @Test
    void insertDuplicateFollow_failsPrimaryKeyConstraint() {
        UUID followerId = insertUser("erin", false);
        UUID followingId = insertUser("frank", false);
        followRepository.insert(followerId, followingId, FollowStatus.ACCEPTED);

        assertThatThrownBy(
                        () ->
                                followRepository.insert(
                                        followerId, followingId, FollowStatus.ACCEPTED))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void blockExistsBetween_returnsTrueForEitherDirection() {
        UUID blockerId = insertUser("grace", false);
        UUID blockedId = insertUser("heidi", false);
        jdbcClient
                .sql("INSERT INTO blocks(blocker_id, blocked_id) VALUES (:blockerId, :blockedId)")
                .param("blockerId", blockerId)
                .param("blockedId", blockedId)
                .update();

        assertThat(blockRepository.existsBetween(blockerId, blockedId)).isTrue();
        assertThat(blockRepository.existsBetween(blockedId, blockerId)).isTrue();
    }

    private UUID insertUser(String username, boolean isPrivate) {
        return jdbcClient
                .sql(
                        """
						INSERT INTO users(username, email, display_name, is_private)
						VALUES (:username, :email, :displayName, :isPrivate)
						RETURNING id
						""")
                .param("username", username)
                .param("email", username + "@example.com")
                .param("displayName", username)
                .param("isPrivate", isPrivate)
                .query(UUID.class)
                .single();
    }

    private Map<String, Object> userCounts(UUID userId) {
        return jdbcClient
                .sql(
                        """
						SELECT follower_count, following_count
						FROM users
						WHERE id = :userId
						""")
                .param("userId", userId)
                .query()
                .singleRow();
    }
}
