package com.app.modules.report.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.turnstile.AuthTurnstileGuard;
import com.app.modules.report.dto.request.CreateReportRequest;
import com.app.modules.report.dto.request.UpdateReportStatusRequest;
import com.app.modules.report.dto.response.ReportResponse;
import com.app.modules.report.dto.response.ReportSummaryResponse;
import com.app.modules.report.entity.Report;
import com.app.modules.report.enums.ReportReason;
import com.app.modules.report.enums.ReportStatus;
import com.app.modules.report.enums.ReportType;
import com.app.modules.report.mapper.ReportMapper;
import com.app.modules.report.repository.ReportRepository;
import com.app.modules.users.enums.UserRole;

@ExtendWith(MockitoExtension.class)
class ReportServiceImplTest {

    private static final String TURNSTILE_TOKEN = "turnstile-token";
    private static final String CLIENT_IP = "4.5.6.7";

    @Mock private ReportRepository reportRepository;
    @Mock private ReportMapper reportMapper;
    @Mock private AuthTurnstileGuard turnstileGuard;
    @Mock private TransactionTemplate transactionTemplate;

    private ReportServiceImpl service;

    @BeforeEach
    void setUp() {
        // Execute TransactionTemplate callbacks directly (no real PlatformTransactionManager).
        lenient()
                .when(transactionTemplate.execute(any(TransactionCallback.class)))
                .thenAnswer(
                        inv -> {
                            TransactionCallback<?> cb = inv.getArgument(0);
                            return cb.doInTransaction(null);
                        });
        service =
                new ReportServiceImpl(
                        reportRepository, reportMapper, turnstileGuard, transactionTemplate);
    }

    @Test
    void submitReport_validTarget_persistsPendingReport() {
        UUID reporterId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        CreateReportRequest request =
                new CreateReportRequest(
                        ReportType.POST,
                        ReportReason.SPAM,
                        entityId,
                        "Repeated advertisements",
                        TURNSTILE_TOKEN);
        ReportResponse expected = response(ReportStatus.PENDING);
        when(reportRepository.findOwnerId(ReportType.POST, entityId))
                .thenReturn(Optional.of(ownerId));
        when(reportRepository.saveAndFlush(any(Report.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(reportMapper.toResponse(any(Report.class))).thenReturn(expected);

        ReportResponse result = service.submitReport(reporterId, request, CLIENT_IP);

        assertThat(result).isEqualTo(expected);
        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getReporterId()).isEqualTo(reporterId);
        assertThat(captor.getValue().getStatus()).isEqualTo(ReportStatus.PENDING);
    }

    @Test
    void submitReport_missingTarget_throwsTargetNotFound() {
        UUID entityId = UUID.randomUUID();
        when(reportRepository.findOwnerId(ReportType.USER, entityId)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.submitReport(
                                        UUID.randomUUID(),
                                        new CreateReportRequest(
                                                ReportType.USER,
                                                ReportReason.HARASSMENT,
                                                entityId,
                                                null,
                                                TURNSTILE_TOKEN),
                                        CLIENT_IP))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.REPORT_TARGET_NOT_FOUND);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void submitReport_selfOwnedTarget_throwsSelfNotAllowed() {
        UUID reporterId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        when(reportRepository.findOwnerId(ReportType.POST, entityId))
                .thenReturn(Optional.of(reporterId));

        assertThatThrownBy(
                        () ->
                                service.submitReport(
                                        reporterId,
                                        new CreateReportRequest(
                                                ReportType.POST,
                                                ReportReason.OTHER,
                                                entityId,
                                                null,
                                                TURNSTILE_TOKEN),
                                        CLIENT_IP))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.REPORT_SELF_NOT_ALLOWED);
    }

    @Test
    void submitReport_duplicateTarget_throwsConflict() {
        UUID reporterId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        when(reportRepository.findOwnerId(ReportType.COMMENT, entityId))
                .thenReturn(Optional.of(UUID.randomUUID()));
        var activeStatuses =
                List.of(ReportStatus.PENDING, ReportStatus.REVIEWING, ReportStatus.ESCALATED);
        when(reportRepository.existsByReporterIdAndReportTypeAndEntityIdAndStatusIn(
                        reporterId, ReportType.COMMENT, entityId, activeStatuses))
                .thenReturn(true);

        assertThatThrownBy(
                        () ->
                                service.submitReport(
                                        reporterId,
                                        new CreateReportRequest(
                                                ReportType.COMMENT,
                                                ReportReason.HATE_SPEECH,
                                                entityId,
                                                null,
                                                TURNSTILE_TOKEN),
                                        CLIENT_IP))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.REPORT_DUPLICATE);
    }

    @Test
    void listReports_filteredCursor_mapsSummaryContent() {
        Report report =
                Report.builder()
                        .id(UUID.randomUUID())
                        .status(ReportStatus.PENDING)
                        .createdAt(OffsetDateTime.now())
                        .build();
        ReportSummaryResponse mapped = summary(ReportStatus.PENDING);
        when(reportRepository.findFirstReportsByStatusAndReportType(
                        ReportStatus.PENDING, ReportType.POST, 21))
                .thenReturn(List.of(report));
        when(reportMapper.toSummaryResponseList(List.of(report))).thenReturn(List.of(mapped));

        var result =
                service.listReports(
                        UserRole.ADMIN, ReportStatus.PENDING, ReportType.POST, null, 20);

        assertThat(result.getContent()).containsExactly(mapped);
        assertThat(result.getPageInfo().isHasNextPage()).isFalse();
    }

    @Test
    void listReports_moderatorWithNoStatusFilter_narrowsToTheOpenStatuses() {
        when(reportRepository.findFirstReportsByStatusIn(
                        List.of(ReportStatus.PENDING, ReportStatus.REVIEWING), 21))
                .thenReturn(List.of());

        var result = service.listReports(UserRole.MODERATOR, null, null, null, 20);

        assertThat(result.getContent()).isEmpty();
        verify(reportRepository)
                .findFirstReportsByStatusIn(
                        List.of(ReportStatus.PENDING, ReportStatus.REVIEWING), 21);
        verify(reportRepository, never()).findFirstReports(anyInt());
    }

    @Test
    void listReports_moderatorAskingForResolved_returnsAnEmptyPageWithoutQuerying() {
        var result = service.listReports(UserRole.MODERATOR, ReportStatus.RESOLVED, null, null, 20);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getPageInfo().isHasNextPage()).isFalse();
        verify(reportRepository, never()).findFirstReportsByStatus(any(), anyInt());
    }

    @Test
    void listReports_moderatorAskingForDismissed_returnsAnEmptyPageWithoutQuerying() {
        var result =
                service.listReports(UserRole.MODERATOR, ReportStatus.DISMISSED, null, null, 20);

        assertThat(result.getContent()).isEmpty();
        verify(reportRepository, never()).findFirstReportsByStatus(any(), anyInt());
    }

    @Test
    void listReports_moderatorAskingForEscalated_returnsAnEmptyPageWithoutQuerying() {
        var result =
                service.listReports(UserRole.MODERATOR, ReportStatus.ESCALATED, null, null, 20);

        assertThat(result.getContent()).isEmpty();
        verify(reportRepository, never()).findFirstReportsByStatus(any(), anyInt());
    }

    @Test
    void listReports_moderatorAskingForReviewing_queriesThatStatus() {
        when(reportRepository.findFirstReportsByStatus(ReportStatus.REVIEWING, 21))
                .thenReturn(List.of());

        service.listReports(UserRole.MODERATOR, ReportStatus.REVIEWING, null, null, 20);

        verify(reportRepository).findFirstReportsByStatus(ReportStatus.REVIEWING, 21);
    }

    @Test
    void listReports_administratorWithNoStatusFilter_narrowsNothing() {
        when(reportRepository.findFirstReports(21)).thenReturn(List.of());

        service.listReports(UserRole.ADMIN, null, null, null, 20);

        verify(reportRepository).findFirstReports(21);
    }

    @Test
    void listReports_administratorAskingForEscalated_queriesThatStatus() {
        when(reportRepository.findFirstReportsByStatus(ReportStatus.ESCALATED, 21))
                .thenReturn(List.of());

        service.listReports(UserRole.ADMIN, ReportStatus.ESCALATED, null, null, 20);

        verify(reportRepository).findFirstReportsByStatus(ReportStatus.ESCALATED, 21);
    }

    @Test
    void updateStatus_everyTransition_permitsOnlyPendingToReviewing() {
        // The whole matrix rather than the arms that changed. Every other pair must be
        // refused, including the ones that were never allowed, so a future arm added to the
        // switch cannot quietly widen this endpoint back out.
        for (ReportStatus current : ReportStatus.values()) {
            for (ReportStatus target : ReportStatus.values()) {
                boolean expectedAllowed =
                        current == ReportStatus.PENDING && target == ReportStatus.REVIEWING;
                UUID reportId = UUID.randomUUID();
                Report report = Report.builder().id(reportId).status(current).build();
                when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
                if (expectedAllowed) {
                    when(reportRepository.save(report)).thenReturn(report);
                    when(reportMapper.toResponse(report)).thenReturn(response(target));
                }

                UpdateReportStatusRequest request = new UpdateReportStatusRequest(target, "note");
                if (expectedAllowed) {
                    service.updateStatus(reportId, UUID.randomUUID(), request);
                    assertThat(report.getStatus()).isEqualTo(target);
                } else {
                    assertThatThrownBy(
                                    () ->
                                            service.updateStatus(
                                                    reportId, UUID.randomUUID(), request))
                            .as("%s -> %s must be refused", current, target)
                            .isInstanceOf(AppException.class)
                            .extracting(ex -> ((AppException) ex).getErrorCode())
                            .isEqualTo(ApiErrorCode.REPORT_INVALID_TRANSITION);
                }
            }
        }
    }

    @Test
    void updateStatus_escalatedReport_throwsInvalidTransition() {
        UUID reportId = UUID.randomUUID();
        Report report = Report.builder().id(reportId).status(ReportStatus.ESCALATED).build();
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(
                        () ->
                                service.updateStatus(
                                        reportId,
                                        UUID.randomUUID(),
                                        new UpdateReportStatusRequest(
                                                ReportStatus.REVIEWING, null)))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.REPORT_INVALID_TRANSITION);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void getPendingReports_firstPage_queriesPendingQueueInFifoOrder() {
        Report report =
                Report.builder()
                        .id(UUID.randomUUID())
                        .status(ReportStatus.PENDING)
                        .createdAt(OffsetDateTime.now())
                        .build();
        ReportSummaryResponse mapped = summary(ReportStatus.PENDING);
        when(reportRepository.findFirstReportsByStatusOldestFirst(ReportStatus.PENDING, 21))
                .thenReturn(List.of(report));
        when(reportMapper.toSummaryResponseList(List.of(report))).thenReturn(List.of(mapped));

        var result = service.getPendingReports(null, 20);

        assertThat(result.getContent()).containsExactly(mapped);
        assertThat(result.getPageInfo().isHasPreviousPage()).isFalse();
    }

    @Test
    void getReport_missingReport_throwsNotFound() {
        UUID reportId = UUID.randomUUID();
        when(reportRepository.findById(reportId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getReport(UserRole.MODERATOR, UUID.randomUUID(), reportId))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.REPORT_NOT_FOUND);
    }

    @Test
    void getReport_moderatorAndClosedReportItDidNotEscalate_throwsNotFound() {
        UUID reportId = UUID.randomUUID();
        Report report =
                Report.builder()
                        .id(reportId)
                        .status(ReportStatus.RESOLVED)
                        .escalatedBy(UUID.randomUUID())
                        .build();
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.getReport(UserRole.MODERATOR, UUID.randomUUID(), reportId))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.REPORT_NOT_FOUND);
    }

    @Test
    void getReport_moderatorAndReportItEscalated_returnsReport() {
        UUID reportId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        Report report =
                Report.builder()
                        .id(reportId)
                        .status(ReportStatus.ESCALATED)
                        .escalatedBy(moderatorId)
                        .build();
        ReportResponse mapped = response(ReportStatus.ESCALATED);
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(reportMapper.toResponse(report)).thenReturn(mapped);

        assertThat(service.getReport(UserRole.MODERATOR, moderatorId, reportId)).isEqualTo(mapped);
    }

    @Test
    void getReport_moderatorAndOpenReport_returnsReport() {
        for (ReportStatus status : List.of(ReportStatus.PENDING, ReportStatus.REVIEWING)) {
            UUID reportId = UUID.randomUUID();
            Report report = Report.builder().id(reportId).status(status).build();
            ReportResponse mapped = response(status);
            when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
            when(reportMapper.toResponse(report)).thenReturn(mapped);

            assertThat(service.getReport(UserRole.MODERATOR, UUID.randomUUID(), reportId))
                    .as("moderator reading a %s report", status)
                    .isEqualTo(mapped);
        }
    }

    @Test
    void getReport_administrator_readsEveryStatus() {
        for (ReportStatus status : ReportStatus.values()) {
            UUID reportId = UUID.randomUUID();
            Report report =
                    Report.builder()
                            .id(reportId)
                            .status(status)
                            .escalatedBy(UUID.randomUUID())
                            .build();
            ReportResponse mapped = response(status);
            when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
            when(reportMapper.toResponse(report)).thenReturn(mapped);

            assertThat(service.getReport(UserRole.ADMIN, UUID.randomUUID(), reportId))
                    .as("administrator reading a %s report", status)
                    .isEqualTo(mapped);
        }
    }

    @Test
    void updateStatus_pendingToReviewing_recordsReviewerMetadata() {
        UUID reportId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        Report report = Report.builder().id(reportId).status(ReportStatus.PENDING).build();
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(reportRepository.save(report)).thenReturn(report);
        when(reportMapper.toResponse(report)).thenReturn(response(ReportStatus.REVIEWING));

        service.updateStatus(
                reportId, reviewerId, new UpdateReportStatusRequest(ReportStatus.REVIEWING, null));

        assertThat(report.getStatus()).isEqualTo(ReportStatus.REVIEWING);
        assertThat(report.getReviewedBy()).isEqualTo(reviewerId);
        assertThat(report.getReviewedAt()).isNotNull();
        assertThat(report.getResolutionNote()).isNull();
    }

    @Test
    void updateStatus_pendingToResolved_throwsInvalidTransition() {
        UUID reportId = UUID.randomUUID();
        Report report = Report.builder().id(reportId).status(ReportStatus.PENDING).build();
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(
                        () ->
                                service.updateStatus(
                                        reportId,
                                        UUID.randomUUID(),
                                        new UpdateReportStatusRequest(
                                                ReportStatus.RESOLVED, "Handled")))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.REPORT_INVALID_TRANSITION);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void updateStatus_pendingToDismissed_throwsInvalidTransition() {
        UUID reportId = UUID.randomUUID();
        Report report = Report.builder().id(reportId).status(ReportStatus.PENDING).build();
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(
                        () ->
                                service.updateStatus(
                                        reportId,
                                        UUID.randomUUID(),
                                        new UpdateReportStatusRequest(
                                                ReportStatus.DISMISSED, "Not actionable")))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.REPORT_INVALID_TRANSITION);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void updateStatus_reviewingToResolved_throwsInvalidTransition() {
        UUID reportId = UUID.randomUUID();
        Report report = Report.builder().id(reportId).status(ReportStatus.REVIEWING).build();
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(
                        () ->
                                service.updateStatus(
                                        reportId,
                                        UUID.randomUUID(),
                                        new UpdateReportStatusRequest(
                                                ReportStatus.RESOLVED, "Handled")))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.REPORT_INVALID_TRANSITION);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void updateStatus_reviewingToDismissed_throwsInvalidTransition() {
        UUID reportId = UUID.randomUUID();
        Report report = Report.builder().id(reportId).status(ReportStatus.REVIEWING).build();
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(
                        () ->
                                service.updateStatus(
                                        reportId,
                                        UUID.randomUUID(),
                                        new UpdateReportStatusRequest(
                                                ReportStatus.DISMISSED, "Not actionable")))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.REPORT_INVALID_TRANSITION);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void updateStatus_terminalReport_throwsInvalidTransition() {
        UUID reportId = UUID.randomUUID();
        Report report = Report.builder().id(reportId).status(ReportStatus.RESOLVED).build();
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(
                        () ->
                                service.updateStatus(
                                        reportId,
                                        UUID.randomUUID(),
                                        new UpdateReportStatusRequest(
                                                ReportStatus.DISMISSED, "Not actionable")))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.REPORT_INVALID_TRANSITION);
    }

    private static ReportResponse response(ReportStatus status) {
        return new ReportResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ReportType.POST,
                ReportReason.SPAM,
                UUID.randomUUID(),
                null,
                status,
                null,
                null,
                null,
                null);
    }

    private static ReportSummaryResponse summary(ReportStatus status) {
        return new ReportSummaryResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ReportType.POST,
                ReportReason.SPAM,
                UUID.randomUUID(),
                status,
                null);
    }
}
