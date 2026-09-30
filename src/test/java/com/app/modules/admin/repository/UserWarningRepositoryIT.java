package com.app.modules.admin.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.testsupport.TestContainerImages;

/**
 * Covers the composition of the three conditions that decide whether a warning still counts.
 *
 * <p>The predicate is one SQL statement, so it is exercised here rather than as a unit test. All
 * four combinations of "inside or outside the retention window" and "before or after the most
 * recent active strike" are asserted, because a wrong composition changes how fast accounts are
 * banned and nothing else would fail.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
class UserWarningRepositoryIT {

    private static final OffsetDateTime NO_STRIKE_YET =
            OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Autowired private UserWarningRepository userWarningRepository;
    @Autowired private JdbcClient jdbcClient;

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void countActiveWarnings_recentAndAfterStrike_counts() {
        UUID user = insertUser("win_a");
        insertStrike(user, 1, daysAgo(30));
        insertWarning(user, daysAgo(10), false);

        assertThat(count(user)).isOne();
    }

    @Test
    void countActiveWarnings_recentButBeforeStrike_doesNotCount() {
        UUID user = insertUser("win_b");
        insertWarning(user, daysAgo(20), false);
        insertStrike(user, 1, daysAgo(10));

        assertThat(count(user)).isZero();
    }

    @Test
    void countActiveWarnings_expiredButAfterStrike_doesNotCount() {
        UUID user = insertUser("win_c");
        insertStrike(user, 1, daysAgo(200));
        insertWarning(user, daysAgo(120), false);

        assertThat(count(user)).isZero();
    }

    @Test
    void countActiveWarnings_expiredAndBeforeStrike_doesNotCount() {
        UUID user = insertUser("win_d");
        insertWarning(user, daysAgo(200), false);
        insertStrike(user, 1, daysAgo(120));

        assertThat(count(user)).isZero();
    }

    @Test
    void countActiveWarnings_revokedWarning_doesNotCount() {
        UUID user = insertUser("win_e");
        insertWarning(user, daysAgo(5), true);

        assertThat(count(user)).isZero();
    }

    @Test
    void countActiveWarnings_noStrikeEver_countsEveryRecentWarning() {
        UUID user = insertUser("win_f");
        insertWarning(user, daysAgo(5), false);
        insertWarning(user, daysAgo(80), false);
        insertWarning(user, daysAgo(95), false);

        assertThat(count(user)).isEqualTo(2);
    }

    @Test
    void countActiveWarnings_revokedStrike_stopsResettingTheCount() {
        UUID user = insertUser("win_g");
        insertWarning(user, daysAgo(40), false);
        insertStrike(user, 1, daysAgo(30));
        insertWarning(user, daysAgo(10), false);

        assertThat(count(user)).isOne();

        jdbcClient
                .sql("UPDATE user_strikes SET revoked_at = NOW() WHERE user_id = :id")
                .param("id", user)
                .update();

        // With the strike revoked there is no reset point left, so the older warning counts again.
        assertThat(count(user)).isEqualTo(2);
    }

    @Test
    void findActiveWarningIds_returnsExactlyTheCountedRows() {
        UUID user = insertUser("win_h");
        insertStrike(user, 1, daysAgo(30));
        UUID counted = insertWarning(user, daysAgo(10), false);
        insertWarning(user, daysAgo(40), false);
        insertWarning(user, daysAgo(5), true);

        assertThat(userWarningRepository.findActiveWarningIds(user, windowStart(), NO_STRIKE_YET))
                .containsExactly(counted);
    }

    private long count(UUID userId) {
        return userWarningRepository.countActiveWarnings(userId, windowStart(), NO_STRIKE_YET);
    }

    private static OffsetDateTime windowStart() {
        return OffsetDateTime.now(ZoneOffset.UTC).minusDays(90);
    }

    private static OffsetDateTime daysAgo(int days) {
        return OffsetDateTime.now(ZoneOffset.UTC).minusDays(days);
    }

    private UUID insertUser(String prefix) {
        UUID id = UUID.randomUUID();
        String username = prefix + "_" + id.toString().substring(0, 8);
        jdbcClient
                .sql(
                        "INSERT INTO users (id, username, email, role, status) "
                                + "VALUES (:id, :username, :email, 'user', 'active')")
                .param("id", id)
                .param("username", username)
                .param("email", username + "@test.local")
                .update();
        return id;
    }

    private UUID insertAuditRow(UUID userId) {
        UUID id = UUID.randomUUID();
        jdbcClient
                .sql(
                        "INSERT INTO admin_actions (id, action_type, target_user_id) "
                                + "VALUES (:id, 'warn_user', :userId)")
                .param("id", id)
                .param("userId", userId)
                .update();
        return id;
    }

    private UUID insertWarning(UUID userId, OffsetDateTime createdAt, boolean revoked) {
        UUID id = UUID.randomUUID();
        jdbcClient
                .sql(
                        "INSERT INTO user_warnings"
                                + " (id, user_id, reason_key, note, admin_action_id, revoked_at,"
                                + " created_at)"
                                + " VALUES (:id, :userId, 'spam', 'seeded', :auditId, :revokedAt,"
                                + " :createdAt)")
                .param("id", id)
                .param("userId", userId)
                .param("auditId", insertAuditRow(userId))
                .param("revokedAt", revoked ? OffsetDateTime.now(ZoneOffset.UTC) : null)
                .param("createdAt", createdAt)
                .update();
        return id;
    }

    private void insertStrike(UUID userId, int number, OffsetDateTime createdAt) {
        jdbcClient
                .sql(
                        "INSERT INTO user_strikes"
                                + " (user_id, strike_number, admin_action_id, created_at)"
                                + " VALUES (:userId, :number, :auditId, :createdAt)")
                .param("userId", userId)
                .param("number", number)
                .param("auditId", insertAuditRow(userId))
                .param("createdAt", createdAt)
                .update();
    }
}
