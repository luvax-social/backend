package com.app.modules.report.controller;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.app.common.ApiConstants;
import com.app.common.base.BaseController;
import com.app.common.enums.ApiSuccessCode;
import com.app.common.response.ApiResponse;
import com.app.common.response.CursorPageResponse;
import com.app.common.security.util.IpExtractor;
import com.app.common.security.util.SecurityUtils;
import com.app.common.web.StrictQueryParameters;
import com.app.modules.report.api.ReportApi;
import com.app.modules.report.dto.request.CreateReportRequest;
import com.app.modules.report.dto.request.UpdateReportStatusRequest;
import com.app.modules.report.dto.response.ReportResponse;
import com.app.modules.report.dto.response.ReportSummaryResponse;
import com.app.modules.report.enums.ReportStatus;
import com.app.modules.report.enums.ReportType;
import com.app.modules.report.service.ReportService;
import com.app.modules.users.enums.UserRole;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/** REST endpoints for authenticated report submission and role-restricted moderation review. */
@RestController
public class ReportController extends BaseController implements ReportApi {

    private final ReportService reportService;
    private final IpExtractor ipExtractor;

    public ReportController(ReportService reportService, IpExtractor ipExtractor) {
        this.reportService = reportService;
        this.ipExtractor = ipExtractor;
    }

    /** Submits a validated report for the authenticated caller and returns the pending record. */
    @Override
    @PostMapping
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<ReportResponse>> submitReport(
            @Valid @RequestBody CreateReportRequest request, HttpServletRequest httpRequest) {
        ReportResponse response =
                reportService.submitReport(
                        SecurityUtils.getCurrentUserId(),
                        request,
                        ipExtractor.extract(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(ApiSuccessCode.CREATED, response));
    }

    /** Returns a filtered report page to authenticated moderators and administrators. */
    @Override
    @GetMapping
    // Both filters here are the reason this endpoint is strict. A misspelled status or reportType
    // would otherwise be dropped and answered with an unfiltered page, which a reviewer then works
    // through believing it is the set they asked for.
    @StrictQueryParameters
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<ReportSummaryResponse>>> listReports(
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(required = false) ReportType reportType,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        reportService.listReports(
                                UserRole.fromJson(SecurityUtils.getCurrentUserRole()),
                                status,
                                reportType,
                                cursor,
                                limit)));
    }

    /** Returns pending reports in FIFO order to authenticated moderators and administrators. */
    @Override
    @GetMapping(ApiConstants.Reports.PENDING)
    @StrictQueryParameters
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<ReportSummaryResponse>>> getPendingReports(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK, reportService.getPendingReports(cursor, limit)));
    }

    /** Returns the reports the calling moderator or administrator escalated. */
    @Override
    @GetMapping(ApiConstants.Reports.ESCALATED_BY_ME)
    @StrictQueryParameters
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<ReportSummaryResponse>>> getMyEscalations(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        reportService.getMyEscalations(
                                SecurityUtils.getCurrentUserId(), cursor, limit)));
    }

    /** Returns one report to an authenticated moderator or administrator. */
    @Override
    @GetMapping(ApiConstants.Reports.BY_ID)
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<ReportResponse>> getReport(
            @PathVariable("reportId") UUID reportId) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        reportService.getReport(
                                UserRole.fromJson(SecurityUtils.getCurrentUserRole()),
                                SecurityUtils.getCurrentUserId(),
                                reportId)));
    }

    /** Applies a lifecycle transition and records the authenticated moderator as reviewer. */
    @Override
    @PatchMapping(ApiConstants.Reports.STATUS)
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<ReportResponse>> updateStatus(
            @PathVariable("reportId") UUID reportId,
            @Valid @RequestBody UpdateReportStatusRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        reportService.updateStatus(
                                reportId, SecurityUtils.getCurrentUserId(), request)));
    }
}
