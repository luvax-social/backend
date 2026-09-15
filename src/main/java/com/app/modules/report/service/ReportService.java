package com.app.modules.report.service;

import java.util.UUID;

import com.app.common.exception.AppException;
import com.app.common.response.CursorPageResponse;
import com.app.modules.report.dto.request.CreateReportRequest;
import com.app.modules.report.dto.request.UpdateReportStatusRequest;
import com.app.modules.report.dto.response.ReportResponse;
import com.app.modules.report.dto.response.ReportSummaryResponse;
import com.app.modules.report.enums.ReportStatus;
import com.app.modules.report.enums.ReportType;
import com.app.modules.users.enums.UserRole;

public interface ReportService {

    /**
     * Submits a report after verifying the Turnstile challenge and validating target existence,
     * ownership, and uniqueness.
     *
     * <p>Verification runs before any of those checks and outside the write transaction, so a
     * rejected challenge writes no row and holds no database connection while Cloudflare answers.
     *
     * @param reporterId authenticated reporter identifier
     * @param request report target, reason and Turnstile token
     * @param clientIp the caller's address as resolved by {@code IpExtractor}, or null
     * @return persisted pending report
     * @throws AppException with {@code AUTH_CAPTCHA_FAILED} when the challenge is rejected, or when
     *     the target is missing, self-owned, or already reported
     */
    ReportResponse submitReport(UUID reporterId, CreateReportRequest request, String clientIp);

    /**
     * Lists reports for moderation with optional status and target-type filters.
     *
     * <p>A moderator's queue is the open part of the lifecycle: pending and reviewing. Asking for a
     * closed or escalated status returns an empty page rather than an error, which is the stealth
     * model this codebase uses elsewhere and which avoids confirming that rows exist behind the
     * filter.
     *
     * <p>An administrator's queue is unchanged and includes escalated reports.
     *
     * <p>The cursor is scoped per role. Filtering by role without doing so would let a moderator
     * replay an administrator's cursor and page into rows its own listing never produces.
     *
     * <p>The role is a parameter rather than a lookup so this module keeps importing no other. It
     * is the caller's real role either way: the principal it comes from is rebuilt from the account
     * row on every request, not from a token claim.
     *
     * @param actorRole role of the caller, which decides the visible statuses
     * @param status optional lifecycle status filter
     * @param reportType optional target-type filter
     * @param cursor opaque cursor from the prior page
     * @param size requested page size
     * @return matching report page
     */
    CursorPageResponse<ReportSummaryResponse> listReports(
            UserRole actorRole,
            ReportStatus status,
            ReportType reportType,
            String cursor,
            int size);

    /**
     * Lists pending reports in FIFO order for moderator triage.
     *
     * @param cursor opaque cursor from the prior page
     * @param size requested page size
     * @return pending report page ordered oldest first
     */
    CursorPageResponse<ReportSummaryResponse> getPendingReports(String cursor, int size);

    /**
     * Cursor page of the reports the caller escalated, newest escalation first.
     *
     * <p>Escalating takes a report out of every queue a moderator can read, so without this the
     * only way back to it is a link from the moderator's own audit rows, which works only because
     * those rows happen to carry the report identifier.
     *
     * <p>Always the caller's own escalations, never anyone else's, and that includes an
     * administrator: the full escalated queue already serves that need. Carries no status filter,
     * because a report an administrator has since resolved is exactly the outcome the moderator
     * escalated in order to follow.
     *
     * @param actorId the caller, whose escalations are listed
     * @param cursor opaque cursor from the previous page; null for the first page
     * @param size requested page size, normalized to 1 to 100 with a default of 20
     * @return the page
     */
    CursorPageResponse<ReportSummaryResponse> getMyEscalations(
            UUID actorId, String cursor, int size);

    /**
     * Returns a report by identifier for moderation review.
     *
     * <p>A moderator reaches the open part of the lifecycle, pending and reviewing, and any report
     * it escalated itself. Every other report answers as if it did not exist, which is the same
     * stealth model the listing uses: a 403 would confirm that the row is there. An administrator
     * reaches every report.
     *
     * <p>The role and the caller identity are parameters rather than lookups so this module keeps
     * importing no other. They are the caller's real role and id either way: the principal they
     * come from is rebuilt from the account row on every request, not from a token claim.
     *
     * @param actorRole role of the caller, which decides what it may read
     * @param actorId identity of the caller, matched against the report's escalating moderator
     * @param reportId report identifier
     * @return report details
     * @throws AppException when the report does not exist, or the caller may not read it
     */
    ReportResponse getReport(UserRole actorRole, UUID actorId, UUID reportId);

    /**
     * Applies a valid moderation lifecycle transition and records reviewer metadata.
     *
     * @param reportId report identifier
     * @param reviewerId authenticated moderator or administrator identifier
     * @param request target status and optional resolution note
     * @return updated report
     * @throws AppException when the report is missing, the transition is invalid, or a terminal
     *     transition lacks a resolution note
     */
    ReportResponse updateStatus(UUID reportId, UUID reviewerId, UpdateReportStatusRequest request);
}
