package com.app.modules.report.repository;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.modules.report.entity.Report;
import com.app.modules.report.enums.ReportReason;
import com.app.modules.report.enums.ReportStatus;
import com.app.modules.report.enums.ReportType;
import com.app.testsupport.TestContainerImages;

@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
class ReportRepositoryIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Autowired private ReportRepository reportRepository;
    @Autowired private JdbcClient jdbcClient;

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void save_duplicateReporterTypeEntity_failsAtDatabaseLayer() {
        UUID reporterId = insertUser("dup_reporter", "dup-reporter@example.com");
        UUID entityId = UUID.randomUUID();
        reportRepository.saveAndFlush(report(reporterId, ReportType.POST, entityId));

        assertThatThrownBy(
                        () ->
                                reportRepository.saveAndFlush(
                                        report(reporterId, ReportType.POST, entityId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_duplicateReporterTypeEntity_afterTerminalReport_isAllowed() {
        UUID reporterId = insertUser("closed_reporter", "closed-reporter@example.com");
        UUID entityId = UUID.randomUUID();
        reportRepository.saveAndFlush(
                report(reporterId, ReportType.POST, entityId, ReportStatus.RESOLVED));

        assertThatCode(
                        () ->
                                reportRepository.saveAndFlush(
                                        report(reporterId, ReportType.POST, entityId)))
                .doesNotThrowAnyException();
    }

    private UUID insertUser(String username, String email) {
        return jdbcClient
                .sql(
                        """
						INSERT INTO users (username, email, role, status)
						VALUES (:username, :email, 'user', 'active')
						RETURNING id
						""")
                .param("username", username)
                .param("email", email)
                .query(UUID.class)
                .single();
    }

    private Report report(UUID reporterId, ReportType reportType, UUID entityId) {
        return report(reporterId, reportType, entityId, ReportStatus.PENDING);
    }

    private Report report(
            UUID reporterId, ReportType reportType, UUID entityId, ReportStatus status) {
        return Report.builder()
                .reporterId(reporterId)
                .reportType(reportType)
                .reportReason(ReportReason.SPAM)
                .entityId(entityId)
                .status(status)
                .build();
    }
}
