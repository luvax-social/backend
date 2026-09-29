package com.app.modules.report.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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

import com.app.modules.report.entity.Report;
import com.app.testsupport.TestContainerImages;

/**
 * Guards against keyset row loss when reports share a boundary {@code created_at}. Pages with a
 * size that cuts into the tie-group and asserts every report is returned exactly once; a dropped
 * row proves a strict-inequality regression, a duplicated row a non-strict one. JPQL cannot express
 * a row-value comparison, so {@link ReportRepository#findAllBeforeCursor} expands the {@code
 * (createdAt, id) < (cursor, cursorId)} comparison into an equivalent {@code OR} form; this test
 * proves that expansion is correct.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ReportKeysetRowLossIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private static final OffsetDateTime SHARED_INSTANT =
            OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final int PAGE_SIZE = 2;

    private final ReportRepository reportRepository;
    private final JdbcClient jdbcClient;

    ReportKeysetRowLossIT(ReportRepository reportRepository, JdbcClient jdbcClient) {
        this.reportRepository = reportRepository;
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void reports_tieGroupOnCreatedAt_pagesEveryRowExactlyOnce() {
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID reporter = insertUser("reporter_" + i);
            UUID reportId = insertReport(reporter, SHARED_INSTANT);
            expected.add(reportId);
        }

        List<UUID> seen = new ArrayList<>();
        List<Report> page = reportRepository.findFirstReports(PAGE_SIZE);
        int guard = 0;
        while (!page.isEmpty() && guard++ < 100) {
            page.forEach(r -> seen.add(r.getId()));
            Report last = page.get(page.size() - 1);
            page =
                    reportRepository.findAllBeforeCursor(
                            last.getCreatedAt(), last.getId(), PAGE_SIZE);
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
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

    private UUID insertReport(UUID reporterId, OffsetDateTime createdAt) {
        return jdbcClient
                .sql(
                        "INSERT INTO reports(reporter_id, report_type, report_reason, entity_id,"
                                + " created_at)"
                                + " VALUES (:reporterId, 'user', 'spam', :entityId, :createdAt)"
                                + " RETURNING id")
                .param("reporterId", reporterId)
                .param("entityId", UUID.randomUUID())
                .param("createdAt", createdAt)
                .query(UUID.class)
                .single();
    }
}
