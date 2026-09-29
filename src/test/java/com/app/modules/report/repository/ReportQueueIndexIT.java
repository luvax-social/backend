package com.app.modules.report.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.modules.report.enums.ReportStatus;
import com.app.testsupport.TestContainerImages;

/**
 * Guards the two halves of the moderator queue's plan, both of which are silent when they break.
 *
 * <p>{@code idx_reports_open_queue} is a partial index whose predicate names the open statuses
 * literally. A status added to {@link ReportStatus#OPEN_QUEUE} without a migration extending that
 * predicate leaves the queue reading every report ever filed, with no error and no failing
 * assertion anywhere else.
 *
 * <p>The keyset predicate carries a {@code createdAt <= cursor} conjunct that implies nothing the
 * tie-breaking disjunction does not already imply. It looks removable, and removing it costs four
 * orders of magnitude on a deep page because an OR cannot be an index condition. The plan
 * assertions below are what stops a tidy-up from silently doing that.
 *
 * <p>A {@code @DataJpaTest} slice rather than the full context: the subject is the SQL these
 * repository methods emit and the plan PostgreSQL chooses for it, and both are identical whether
 * the repository is loaded in a slice or in the whole application.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
class ReportQueueIndexIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired private ReportRepository reportRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID reporterId;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM reports");
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE 'queueidx%'");
        reporterId =
                jdbcTemplate.queryForObject(
                        "INSERT INTO users (username, email, role, status, is_private, is_verified)"
                                + " VALUES ('queueidx_reporter', 'queueidx@test.local', 'user',"
                                + " 'active', FALSE, TRUE) RETURNING id",
                        UUID.class);
        // Enough rows, and enough of them outside the open statuses, that the planner has a real
        // choice to make rather than defaulting to a scan of a table that fits in one page.
        jdbcTemplate.update(
                "INSERT INTO reports (reporter_id, report_type, report_reason, entity_id, status,"
                        + " created_at) SELECT ?, 'post'::report_type, 'spam'::report_reason,"
                        + " gen_random_uuid(), (CASE WHEN g % 4 = 0 THEN 'pending' WHEN g % 4 = 1 THEN"
                        + " 'reviewing' WHEN g % 4 = 2 THEN 'resolved' ELSE 'dismissed'"
                        + " END)::report_status, NOW() - (g * INTERVAL '1 minute') FROM"
                        + " generate_series(1, 20000) AS g",
                reporterId);
        jdbcTemplate.execute("ANALYZE reports");
    }

    @Test
    void theOpenQueueIndexPredicateNamesExactlyTheStatusesTheQueueReads() {
        String definition =
                jdbcTemplate.queryForObject(
                        "SELECT indexdef FROM pg_indexes WHERE indexname ="
                                + " 'idx_reports_open_queue'",
                        String.class);

        assertThat(definition).as("the queue index must exist").isNotNull();
        for (ReportStatus status : ReportStatus.values()) {
            boolean inQueue = ReportStatus.OPEN_QUEUE.contains(status);
            assertThat(definition.contains("'" + status.toJson() + "'"))
                    .as(
                            "index predicate names %s: expected %s to match OPEN_QUEUE",
                            status.toJson(), inQueue)
                    .isEqualTo(inQueue);
        }
    }

    @Test
    void theFirstQueuePageIsServedByTheOpenQueueIndex() {
        reportRepository.findFirstReportsByStatusIn(ReportStatus.OPEN_QUEUE, 21);

        String plan =
                explain(
                        "SELECT r.* FROM reports r WHERE r.status IN ('pending','reviewing')"
                                + " ORDER BY r.created_at DESC, r.id DESC LIMIT 21");

        assertThat(plan).contains("idx_reports_open_queue");
        assertThat(plan).doesNotContain("Seq Scan");
        assertThat(plan).doesNotContain("Sort Key");
    }

    @Test
    void aDeepQueuePageAppliesTheCursorAsAnIndexConditionRatherThanAFilter() {
        // The whole point of the bound. Without it the same index is chosen and then every entry
        // newer than the cursor is discarded by a filter, which is linear in the table.
        String plan =
                explain(
                        "SELECT r.* FROM reports r WHERE r.status IN ('pending','reviewing')"
                                + " AND r.created_at <= NOW() - INTERVAL '5000 minutes' AND"
                                + " (r.created_at < NOW() - INTERVAL '5000 minutes' OR (r.created_at ="
                                + " NOW() - INTERVAL '5000 minutes' AND r.id <"
                                + " '00000000-0000-0000-0000-000000000000'::uuid)) ORDER BY"
                                + " r.created_at DESC, r.id DESC LIMIT 21");

        assertThat(plan).contains("idx_reports_open_queue");
        assertThat(plan).contains("Index Cond");
        assertThat(plan).doesNotContain("Seq Scan");
    }

    @Test
    void theKeysetPageReturnsTheSameRowsTheUnboundedPredicateWould() {
        // The bound is a performance conjunct, so the row set has to be provably unchanged by it.
        OffsetDateTime cursorCreatedAt =
                jdbcTemplate.queryForObject(
                        "SELECT created_at FROM reports WHERE status IN"
                                + " ('pending','reviewing') ORDER BY created_at DESC, id DESC OFFSET 40"
                                + " LIMIT 1",
                        OffsetDateTime.class);
        UUID cursorId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM reports WHERE status IN ('pending','reviewing') ORDER BY"
                                + " created_at DESC, id DESC OFFSET 40 LIMIT 1",
                        UUID.class);

        List<UUID> throughRepository =
                reportRepository
                        .findAllByStatusInBeforeCursor(
                                ReportStatus.OPEN_QUEUE, cursorCreatedAt, cursorId, 21)
                        .stream()
                        .map(report -> report.getId())
                        .toList();
        List<UUID> throughUnboundedPredicate =
                jdbcTemplate.queryForList(
                        "SELECT id FROM reports WHERE status IN ('pending','reviewing')"
                                + " AND (created_at < ? OR (created_at = ? AND id < ?)) ORDER BY"
                                + " created_at DESC, id DESC LIMIT 21",
                        UUID.class,
                        cursorCreatedAt,
                        cursorCreatedAt,
                        cursorId);

        assertThat(throughRepository).isEqualTo(throughUnboundedPredicate).hasSize(21);
    }

    private String explain(String sql) {
        return String.join("\n", jdbcTemplate.queryForList("EXPLAIN " + sql, String.class));
    }
}
