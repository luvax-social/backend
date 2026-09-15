package com.app.modules.report.service.impl;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.pagination.Cursor;
import com.app.common.pagination.CursorCodec;
import com.app.common.pagination.CursorScope;
import com.app.common.pagination.TimeCursors;
import com.app.common.response.CursorPageResponse;
import com.app.common.turnstile.AuthTurnstileGuard;
import com.app.common.turnstile.TurnstileSurface;
import com.app.modules.report.dto.request.CreateReportRequest;
import com.app.modules.report.dto.request.UpdateReportStatusRequest;
import com.app.modules.report.dto.response.ReportResponse;
import com.app.modules.report.dto.response.ReportSummaryResponse;
import com.app.modules.report.entity.Report;
import com.app.modules.report.enums.ReportStatus;
import com.app.modules.report.enums.ReportType;
import com.app.modules.report.mapper.ReportMapper;
import com.app.modules.report.repository.ReportRepository;
import com.app.modules.report.service.ReportService;
import com.app.modules.users.enums.UserRole;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ReportServiceImpl implements ReportService {

    /** The open part of the lifecycle, and the whole of a moderator's queue. */
    private static final List<ReportStatus> MODERATOR_STATUSES = ReportStatus.OPEN_QUEUE;

    private static final List<ReportStatus> ACTIVE_DUPLICATE_STATUSES =
            List.of(ReportStatus.PENDING, ReportStatus.REVIEWING, ReportStatus.ESCALATED);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ReportRepository reportRepository;
    private final ReportMapper reportMapper;
    private final AuthTurnstileGuard turnstileGuard;
    private final TransactionTemplate transactionTemplate;

    public ReportServiceImpl(
            ReportRepository reportRepository,
            ReportMapper reportMapper,
            AuthTurnstileGuard turnstileGuard,
            TransactionTemplate transactionTemplate) {
        this.reportRepository = reportRepository;
        this.reportMapper = reportMapper;
        this.turnstileGuard = turnstileGuard;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public ReportResponse submitReport(
            UUID reporterId, CreateReportRequest request, String clientIp) {
        // Before the existence, self-report and duplicate checks, so a rejected challenge reads
        // nothing and writes nothing.
        turnstileGuard.require(request.turnstileToken(), clientIp, TurnstileSurface.REPORT);

        // TransactionTemplate rather than @Transactional on this method: the siteverify call above
        // can take up to the configured read timeout, and the same-class pattern used by
        // AuthServiceImpl keeps that outbound wait off a pooled database connection.
        return transactionTemplate.execute(status -> persistReport(reporterId, request));
    }

    private ReportResponse persistReport(UUID reporterId, CreateReportRequest request) {
        UUID ownerId = validateEntityExists(request.reportType(), request.entityId());
        if (reporterId.equals(ownerId)) {
            throw new AppException(ApiErrorCode.REPORT_SELF_NOT_ALLOWED);
        }
        validateDuplicateReport(reporterId, request.reportType(), request.entityId());

        Report report =
                Report.builder()
                        .reporterId(reporterId)
                        .reportType(request.reportType())
                        .reportReason(request.reportReason())
                        .entityId(request.entityId())
                        .description(request.description())
                        .status(ReportStatus.PENDING)
                        .build();
        try {
            // The pre-check above cannot close the race between two concurrent submissions. The
            // partial unique index on active reports is the authoritative guard; flush here so the
            // violation surfaces as a duplicate rather than a late 500.
            return reportMapper.toResponse(reportRepository.saveAndFlush(report));
        } catch (DataIntegrityViolationException ex) {
            throw new AppException(ApiErrorCode.REPORT_DUPLICATE);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<ReportSummaryResponse> listReports(
            UserRole actorRole,
            ReportStatus status,
            ReportType reportType,
            String cursor,
            int size) {
        boolean moderator = actorRole == UserRole.MODERATOR;
        String scope = moderator ? CursorScope.REPORTS_MODERATOR : CursorScope.REPORTS;
        int pageSize = normalizeLimit(size);
        ReportCursor decoded = decodeCursor(cursor, scope);
        int queryLimit = pageSize + 1;
        if (moderator && status != null && !MODERATOR_STATUSES.contains(status)) {
            // Empty rather than forbidden. A moderator asking for resolved reports learns
            // nothing either way, and an error would confirm that rows exist behind the
            // filter, which is the disclosure the narrowing exists to prevent.
            return emptyPage(cursor != null);
        }
        List<ReportStatus> statuses = moderator && status == null ? MODERATOR_STATUSES : null;
        var reports =
                decoded.isEmpty()
                        ? findFirstReportPage(statuses, status, reportType, queryLimit)
                        : findReportPageAfterCursor(
                                statuses, status, reportType, decoded, queryLimit);
        return toSummaryPage(reports, pageSize, cursor != null, scope);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<ReportSummaryResponse> getPendingReports(String cursor, int size) {
        int pageSize = normalizeLimit(size);
        ReportCursor decoded = decodeCursor(cursor, CursorScope.PENDING_REPORTS);
        int queryLimit = pageSize + 1;
        var reports =
                decoded.isEmpty()
                        ? reportRepository.findFirstReportsByStatusOldestFirst(
                                ReportStatus.PENDING, queryLimit)
                        : reportRepository.findAllByStatusAfterCursor(
                                ReportStatus.PENDING,
                                decoded.createdAt(),
                                decoded.id(),
                                queryLimit);
        return toSummaryPage(reports, pageSize, cursor != null, CursorScope.PENDING_REPORTS);
    }

    @Override
    @Transactional(readOnly = true)
    public ReportResponse getReport(UserRole actorRole, UUID actorId, UUID reportId) {
        Report report = findReport(reportId);
        if (!mayRead(actorRole, actorId, report)) {
            // Not found rather than forbidden. The listing already hides these rows, and answering
            // 403 here would tell a moderator which identifiers name a real report, which is the
            // disclosure hiding them was for.
            throw new AppException(ApiErrorCode.REPORT_NOT_FOUND);
        }
        return reportMapper.toResponse(report);
    }

    @Override
    @Transactional
    public ReportResponse updateStatus(
            UUID reportId, UUID reviewerId, UpdateReportStatusRequest request) {
        Report report = findReport(reportId);
        validateTransition(report.getStatus(), request.status());

        report.setStatus(request.status());
        report.setReviewedBy(reviewerId);
        report.setReviewedAt(OffsetDateTime.now());
        return reportMapper.toResponse(reportRepository.save(report));
    }

    private UUID validateEntityExists(ReportType reportType, UUID entityId) {
        return reportRepository
                .findOwnerId(reportType, entityId)
                .orElseThrow(() -> new AppException(ApiErrorCode.REPORT_TARGET_NOT_FOUND));
    }

    private void validateDuplicateReport(UUID reporterId, ReportType reportType, UUID entityId) {
        if (reportRepository.existsByReporterIdAndReportTypeAndEntityIdAndStatusIn(
                reporterId, reportType, entityId, ACTIVE_DUPLICATE_STATUSES)) {
            throw new AppException(ApiErrorCode.REPORT_DUPLICATE);
        }
    }

    // An administrator reads everything. A moderator reads its own queue, and keeps read access to
    // a report it escalated so it can follow the case it handed up. Nothing else: leaving the
    // direct read open let every moderator read every closed and escalated report by identifier
    // while the listing that was meant to hide them filtered them out.
    private static boolean mayRead(UserRole actorRole, UUID actorId, Report report) {
        if (actorRole != UserRole.MODERATOR) {
            return true;
        }
        return MODERATOR_STATUSES.contains(report.getStatus())
                || (report.getEscalatedBy() != null && report.getEscalatedBy().equals(actorId));
    }

    private Report findReport(UUID reportId) {
        return reportRepository
                .findById(reportId)
                .orElseThrow(() -> new AppException(ApiErrorCode.REPORT_NOT_FOUND));
    }

    // This endpoint reaches only the non-terminal part of the lifecycle. Claiming a report for
    // triage is not a moderation decision and needs no audit row, so it stays here. Closing one is,
    // and it belongs to the admin module, which writes the admin_actions row in the same
    // transaction as the status change. Allowing a close here as well would give the same audit row
    // two writers and leave one of them silent, which is the defect this narrowing removes.
    private void validateTransition(ReportStatus current, ReportStatus target) {
        boolean valid =
                switch (current) {
                    case PENDING -> target == ReportStatus.REVIEWING;
                    // ESCALATED is here rather than absent because the switch is exhaustive. It
                    // behaves like a terminal state to this endpoint on purpose: a report handed up
                    // to an administrator must not be pulled back into the moderator queue.
                    case REVIEWING, RESOLVED, DISMISSED, ESCALATED -> false;
                };
        if (!valid) {
            throw new AppException(ApiErrorCode.REPORT_INVALID_TRANSITION);
        }
    }

    // statuses is non-null only for a moderator that asked for no particular status, where the
    // listing is narrowed to the open ones. status and statuses are never both set.
    private List<Report> findFirstReportPage(
            List<ReportStatus> statuses, ReportStatus status, ReportType reportType, int limit) {
        if (statuses != null) {
            return reportType != null
                    ? reportRepository.findFirstReportsByStatusInAndReportType(
                            statuses, reportType, limit)
                    : reportRepository.findFirstReportsByStatusIn(statuses, limit);
        }
        if (status != null && reportType != null) {
            return reportRepository.findFirstReportsByStatusAndReportType(
                    status, reportType, limit);
        }
        if (status != null) {
            return reportRepository.findFirstReportsByStatus(status, limit);
        }
        if (reportType != null) {
            return reportRepository.findFirstReportsByReportType(reportType, limit);
        }
        return reportRepository.findFirstReports(limit);
    }

    private List<Report> findReportPageAfterCursor(
            List<ReportStatus> statuses,
            ReportStatus status,
            ReportType reportType,
            ReportCursor cursor,
            int limit) {
        if (statuses != null) {
            return reportType != null
                    ? reportRepository.findAllByStatusInAndReportTypeBeforeCursor(
                            statuses, reportType, cursor.createdAt(), cursor.id(), limit)
                    : reportRepository.findAllByStatusInBeforeCursor(
                            statuses, cursor.createdAt(), cursor.id(), limit);
        }
        if (status != null && reportType != null) {
            return reportRepository.findAllByStatusAndReportTypeBeforeCursor(
                    status, reportType, cursor.createdAt(), cursor.id(), limit);
        }
        if (status != null) {
            return reportRepository.findAllByStatusBeforeCursor(
                    status, cursor.createdAt(), cursor.id(), limit);
        }
        if (reportType != null) {
            return reportRepository.findAllByReportTypeBeforeCursor(
                    reportType, cursor.createdAt(), cursor.id(), limit);
        }
        return reportRepository.findAllBeforeCursor(cursor.createdAt(), cursor.id(), limit);
    }

    private CursorPageResponse<ReportSummaryResponse> emptyPage(boolean hasPreviousPage) {
        return CursorPageResponse.of(Collections.emptyList(), false, null, null, hasPreviousPage);
    }

    private CursorPageResponse<ReportSummaryResponse> toSummaryPage(
            java.util.List<Report> reports, int pageSize, boolean hasPreviousPage, String scope) {
        boolean hasNextPage = reports.size() > pageSize;
        java.util.List<Report> pageReports = hasNextPage ? reports.subList(0, pageSize) : reports;
        if (pageReports.isEmpty()) {
            return CursorPageResponse.<ReportSummaryResponse>builder()
                    .content(Collections.emptyList())
                    .pageInfo(
                            CursorPageResponse.PageInfo.builder()
                                    .hasNextPage(false)
                                    .hasPreviousPage(hasPreviousPage)
                                    .startCursor(null)
                                    .endCursor(null)
                                    .build())
                    .build();
        }
        java.util.List<ReportSummaryResponse> content =
                reportMapper.toSummaryResponseList(pageReports);
        return CursorPageResponse.<ReportSummaryResponse>builder()
                .content(content)
                .pageInfo(
                        CursorPageResponse.PageInfo.builder()
                                .hasNextPage(hasNextPage)
                                .hasPreviousPage(hasPreviousPage)
                                .startCursor(encodeCursor(pageReports.get(0), scope))
                                .endCursor(
                                        encodeCursor(
                                                pageReports.get(pageReports.size() - 1), scope))
                                .build())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<ReportSummaryResponse> getMyEscalations(
            UUID actorId, String cursor, int size) {
        int pageSize = normalizeLimit(size);
        ReportCursor decoded = decodeCursor(cursor, CursorScope.REPORTS_ESCALATED_BY_ME);
        int queryLimit = pageSize + 1;
        List<Report> reports =
                decoded.isEmpty()
                        ? reportRepository.findFirstEscalatedBy(actorId, queryLimit)
                        : reportRepository.findEscalatedByAfterCursor(
                                actorId, decoded.createdAt(), decoded.id(), queryLimit);
        return toEscalationPage(
                reports, pageSize, cursor != null, CursorScope.REPORTS_ESCALATED_BY_ME);
    }

    // A near-copy of toSummaryPage with one difference that cannot be parameterised away: this
    // listing is ordered by escalated_at, so its cursor has to carry escalated_at. Encoding
    // created_at here would produce a cursor that names a position in a different ordering.
    private CursorPageResponse<ReportSummaryResponse> toEscalationPage(
            List<Report> reports, int pageSize, boolean hasPreviousPage, String scope) {
        boolean hasNextPage = reports.size() > pageSize;
        List<Report> pageReports = hasNextPage ? reports.subList(0, pageSize) : reports;
        if (pageReports.isEmpty()) {
            return CursorPageResponse.<ReportSummaryResponse>builder()
                    .content(Collections.emptyList())
                    .pageInfo(
                            CursorPageResponse.PageInfo.builder()
                                    .hasNextPage(false)
                                    .hasPreviousPage(hasPreviousPage)
                                    .startCursor(null)
                                    .endCursor(null)
                                    .build())
                    .build();
        }
        return CursorPageResponse.<ReportSummaryResponse>builder()
                .content(reportMapper.toSummaryResponseList(pageReports))
                .pageInfo(
                        CursorPageResponse.PageInfo.builder()
                                .hasNextPage(hasNextPage)
                                .hasPreviousPage(hasPreviousPage)
                                .startCursor(encodeEscalationCursor(pageReports.get(0), scope))
                                .endCursor(
                                        encodeEscalationCursor(
                                                pageReports.get(pageReports.size() - 1), scope))
                                .build())
                .build();
    }

    private String encodeEscalationCursor(Report report, String scope) {
        return CursorCodec.encode(
                new Cursor(TimeCursors.toMicros(report.getEscalatedAt()), report.getId()), scope);
    }

    private int normalizeLimit(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private String encodeCursor(Report report, String scope) {
        return CursorCodec.encode(
                new Cursor(TimeCursors.toMicros(report.getCreatedAt()), report.getId()), scope);
    }

    private ReportCursor decodeCursor(String cursor, String scope) {
        Cursor decoded = CursorCodec.decode(cursor, scope);
        if (decoded == null) {
            return new ReportCursor(null, null);
        }
        return new ReportCursor(TimeCursors.fromMicros(decoded.sortValueMicros()), decoded.id());
    }

    private record ReportCursor(OffsetDateTime createdAt, UUID id) {

        boolean isEmpty() {
            return createdAt == null || id == null;
        }
    }
}
