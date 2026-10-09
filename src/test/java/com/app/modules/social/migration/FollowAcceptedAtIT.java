package com.app.modules.social.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
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

import com.app.testsupport.TestContainerImages;

@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FollowAcceptedAtIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private final JdbcClient jdbc;

    FollowAcceptedAtIT(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void insertAccepted_setsAcceptedAtToNow() {
        UUID followerId = insertUser("accepted-follower");
        UUID followingId = insertUser("accepted-following");

        insertFollow(followerId, followingId, "accepted", false);

        FollowTimes row = followTimes(followerId, followingId);
        OffsetDateTime acceptedAt = row.acceptedAt();
        assertThat(acceptedAt).isNotNull();
        assertThat(acceptedAt)
                .isBetween(databaseNow().minusSeconds(5), databaseNow().plusSeconds(5));
    }

    @Test
    void approvePending_setsAcceptedAtAtApprovalNotCreation() {
        UUID followerId = insertUser("approved-follower");
        UUID followingId = insertUser("approved-following");
        insertFollow(followerId, followingId, "pending", true);
        OffsetDateTime createdAt = followTimes(followerId, followingId).createdAt();

        updateStatus(followerId, followingId, "accepted");

        FollowTimes row = followTimes(followerId, followingId);
        assertThat(row.createdAt()).isEqualTo(createdAt);
        assertThat(row.acceptedAt())
                .isBetween(databaseNow().minusSeconds(5), databaseNow().plusSeconds(5))
                .isAfter(createdAt.plusDays(29));
    }

    @Test
    void pendingRow_hasNullAcceptedAt() {
        UUID followerId = insertUser("pending-follower");
        UUID followingId = insertUser("pending-following");

        insertFollow(followerId, followingId, "pending", false);

        assertThat(followTimes(followerId, followingId).acceptedAt()).isNull();
    }

    @Test
    void sameStatusUpdate_doesNotMoveAcceptedAt() {
        UUID followerId = insertUser("unchanged-follower");
        UUID followingId = insertUser("unchanged-following");
        insertFollow(followerId, followingId, "accepted", false);
        jdbc.sql(
                        "UPDATE follows SET accepted_at = NOW() - INTERVAL '2 days' WHERE follower_id = :followerId AND following_id = :followingId")
                .param("followerId", followerId)
                .param("followingId", followingId)
                .update();
        OffsetDateTime acceptedAt = followTimes(followerId, followingId).acceptedAt();

        updateStatus(followerId, followingId, "accepted");

        assertThat(followTimes(followerId, followingId).acceptedAt()).isEqualTo(acceptedAt);
    }

    private UUID insertUser(String username) {
        return jdbc.sql(
                        "INSERT INTO users(username, email, display_name) VALUES (:username, :email, :displayName) RETURNING id")
                .param("username", username)
                .param("email", username + "@example.com")
                .param("displayName", username)
                .query(UUID.class)
                .single();
    }

    private void insertFollow(
            UUID followerId, UUID followingId, String status, boolean oldRequest) {
        jdbc.sql(
                        oldRequest
                                ? "INSERT INTO follows(follower_id, following_id, status, created_at) VALUES (:followerId, :followingId, CAST(:status AS follow_status), NOW() - INTERVAL '30 days')"
                                : "INSERT INTO follows(follower_id, following_id, status) VALUES (:followerId, :followingId, CAST(:status AS follow_status))")
                .param("followerId", followerId)
                .param("followingId", followingId)
                .param("status", status)
                .update();
    }

    private void updateStatus(UUID followerId, UUID followingId, String status) {
        jdbc.sql(
                        "UPDATE follows SET status = CAST(:status AS follow_status) WHERE follower_id = :followerId AND following_id = :followingId")
                .param("status", status)
                .param("followerId", followerId)
                .param("followingId", followingId)
                .update();
    }

    private FollowTimes followTimes(UUID followerId, UUID followingId) {
        return jdbc.sql(
                        "SELECT created_at, accepted_at FROM follows WHERE follower_id = :followerId AND following_id = :followingId")
                .param("followerId", followerId)
                .param("followingId", followingId)
                .query(
                        (rs, rowNum) ->
                                new FollowTimes(
                                        rs.getObject("created_at", OffsetDateTime.class),
                                        rs.getObject("accepted_at", OffsetDateTime.class)))
                .single();
    }

    private OffsetDateTime databaseNow() {
        return jdbc.sql("SELECT NOW()").query(OffsetDateTime.class).single();
    }

    private record FollowTimes(OffsetDateTime createdAt, OffsetDateTime acceptedAt) {}
}
