package com.app.modules.admin.controller;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.app.common.ApiConstants;
import com.app.common.base.BaseController;
import com.app.common.enums.ApiSuccessCode;
import com.app.common.response.ApiResponse;
import com.app.common.response.CursorPageResponse;
import com.app.common.security.util.SecurityUtils;
import com.app.common.web.StrictQueryParameters;
import com.app.modules.admin.api.AdminApi;
import com.app.modules.admin.dto.request.AdminActionRequest;
import com.app.modules.admin.dto.request.AdminEscalateReportRequest;
import com.app.modules.admin.dto.request.AdminSuspendUserRequest;
import com.app.modules.admin.dto.response.AdminActionResponse;
import com.app.modules.admin.dto.response.AdminActionSummaryResponse;
import com.app.modules.admin.dto.response.AdminPostRestoreResponse;
import com.app.modules.admin.dto.response.AdminReportTargetResponse;
import com.app.modules.admin.dto.response.EscalatedReportCountResponse;
import com.app.modules.admin.enums.AdminActionType;
import com.app.modules.admin.service.AdminReportTargetService;
import com.app.modules.admin.service.AdminService;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/** REST endpoints for role-restricted moderation workflows and audit-history retrieval. */
@RestController
@PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
public class AdminController extends BaseController implements AdminApi {

    private final AdminService adminService;
    private final AdminReportTargetService adminReportTargetService;

    public AdminController(
            AdminService adminService, AdminReportTargetService adminReportTargetService) {
        this.adminService = adminService;
        this.adminReportTargetService = adminReportTargetService;
    }

    /** Bans a user for the authenticated administrator. */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping(ApiConstants.Admin.BAN_USER)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminActionResponse>> banUser(
            @PathVariable("userId") UUID userId, @Valid @RequestBody AdminActionRequest request) {
        return ok(adminService.banUser(SecurityUtils.getCurrentUserId(), userId, request));
    }

    /** Unbans a user for the authenticated administrator. */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping(ApiConstants.Admin.UNBAN_USER)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminActionResponse>> unbanUser(
            @PathVariable("userId") UUID userId, @Valid @RequestBody AdminActionRequest request) {
        return ok(adminService.unbanUser(SecurityUtils.getCurrentUserId(), userId, request));
    }

    /** Suspends a user for the authenticated administrator. */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping(ApiConstants.Admin.SUSPEND_USER)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminActionResponse>> suspendUser(
            @PathVariable("userId") UUID userId,
            @Valid @RequestBody AdminSuspendUserRequest request) {
        return ok(adminService.suspendUser(SecurityUtils.getCurrentUserId(), userId, request));
    }

    /** Unsuspends a user for the authenticated administrator. */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping(ApiConstants.Admin.UNSUSPEND_USER)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminActionResponse>> unsuspendUser(
            @PathVariable("userId") UUID userId, @Valid @RequestBody AdminActionRequest request) {
        return ok(adminService.unsuspendUser(SecurityUtils.getCurrentUserId(), userId, request));
    }

    /** Removes a post for the authenticated moderator or administrator. */
    @Override
    @PatchMapping(ApiConstants.Admin.REMOVE_POST)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminActionResponse>> removePost(
            @PathVariable("postId") UUID postId, @Valid @RequestBody AdminActionRequest request) {
        return ok(adminService.removePost(SecurityUtils.getCurrentUserId(), postId, request));
    }

    /** Restores a post for the authenticated moderator or administrator. */
    @Override
    @PatchMapping(ApiConstants.Admin.RESTORE_POST)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminPostRestoreResponse>> restorePost(
            @PathVariable("postId") UUID postId, @Valid @RequestBody AdminActionRequest request) {
        return ok(adminService.restorePost(SecurityUtils.getCurrentUserId(), postId, request));
    }

    /** Removes a comment for the authenticated moderator or administrator. */
    @Override
    @PatchMapping(ApiConstants.Admin.REMOVE_COMMENT)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminActionResponse>> removeComment(
            @PathVariable("commentId") UUID commentId,
            @Valid @RequestBody AdminActionRequest request) {
        return ok(adminService.removeComment(SecurityUtils.getCurrentUserId(), commentId, request));
    }

    /** Restores a comment for the authenticated moderator or administrator. */
    @Override
    @PatchMapping(ApiConstants.Admin.RESTORE_COMMENT)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminActionResponse>> restoreComment(
            @PathVariable("commentId") UUID commentId,
            @Valid @RequestBody AdminActionRequest request) {
        return ok(
                adminService.restoreComment(SecurityUtils.getCurrentUserId(), commentId, request));
    }

    /** Removes a story for the authenticated moderator or administrator. */
    @Override
    @PatchMapping(ApiConstants.Admin.REMOVE_STORY)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminActionResponse>> removeStory(
            @PathVariable("storyId") UUID storyId, @Valid @RequestBody AdminActionRequest request) {
        return ok(adminService.removeStory(SecurityUtils.getCurrentUserId(), storyId, request));
    }

    /** Restores a story for the authenticated moderator or administrator. */
    @Override
    @PatchMapping(ApiConstants.Admin.RESTORE_STORY)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminActionResponse>> restoreStory(
            @PathVariable("storyId") UUID storyId, @Valid @RequestBody AdminActionRequest request) {
        return ok(adminService.restoreStory(SecurityUtils.getCurrentUserId(), storyId, request));
    }

    /** Removes a message for the authenticated moderator or administrator. */
    @Override
    @PatchMapping(ApiConstants.Admin.REMOVE_MESSAGE)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminActionResponse>> removeMessage(
            @PathVariable("messageId") UUID messageId,
            @Valid @RequestBody AdminActionRequest request) {
        return ok(adminService.removeMessage(SecurityUtils.getCurrentUserId(), messageId, request));
    }

    /** Restores a message for the authenticated moderator or administrator. */
    @Override
    @PatchMapping(ApiConstants.Admin.RESTORE_MESSAGE)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminActionResponse>> restoreMessage(
            @PathVariable("messageId") UUID messageId,
            @Valid @RequestBody AdminActionRequest request) {
        return ok(
                adminService.restoreMessage(SecurityUtils.getCurrentUserId(), messageId, request));
    }

    /** Resolves a report for the authenticated moderator or administrator. */
    @Override
    @PatchMapping(ApiConstants.Admin.RESOLVE_REPORT)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminActionResponse>> resolveReport(
            @PathVariable("reportId") UUID reportId,
            @Valid @RequestBody AdminActionRequest request) {
        return ok(adminService.resolveReport(SecurityUtils.getCurrentUserId(), reportId, request));
    }

    /** Dismisses a report for the authenticated moderator or administrator. */
    @Override
    @PatchMapping(ApiConstants.Admin.DISMISS_REPORT)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminActionResponse>> dismissReport(
            @PathVariable("reportId") UUID reportId,
            @Valid @RequestBody AdminActionRequest request) {
        return ok(adminService.dismissReport(SecurityUtils.getCurrentUserId(), reportId, request));
    }

    /** Returns a filtered cursor page of audit summaries to a moderation actor. */
    @Override
    @GetMapping(ApiConstants.Admin.ACTIONS)
    @StrictQueryParameters
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<AdminActionSummaryResponse>>> getActions(
            @RequestParam(required = false) UUID adminId,
            @RequestParam(required = false) AdminActionType actionType,
            @RequestParam(required = false) UUID targetUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return page(
                adminService.getActions(
                        SecurityUtils.getCurrentUserId(),
                        adminId,
                        actionType,
                        targetUserId,
                        from,
                        to,
                        cursor,
                        limit));
    }

    /** Returns one audit event to an authenticated moderator or administrator. */
    @Override
    @GetMapping(ApiConstants.Admin.ACTION_BY_ID)
    @StrictQueryParameters
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminActionResponse>> getActionById(
            @PathVariable("actionId") UUID actionId) {
        return ok(adminService.getActionById(SecurityUtils.getCurrentUserId(), actionId));
    }

    /** Returns a cursor page of audit summaries for one affected user. */
    @Override
    @GetMapping(ApiConstants.Admin.ACTIONS_FOR_USER)
    @StrictQueryParameters
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<AdminActionSummaryResponse>>>
            getActionsForUser(
                    @PathVariable("userId") UUID userId,
                    @RequestParam(required = false) String cursor,
                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return page(
                adminService.getActionsForUser(
                        SecurityUtils.getCurrentUserId(), userId, cursor, limit));
    }

    /** Escalates a report to an administrator for the authenticated moderator. */
    @Override
    @PatchMapping(ApiConstants.Admin.ESCALATE_REPORT)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminActionResponse>> escalateReport(
            @PathVariable("reportId") UUID reportId,
            @Valid @RequestBody AdminEscalateReportRequest request) {
        return ok(adminService.escalateReport(SecurityUtils.getCurrentUserId(), reportId, request));
    }

    /** Returns the number of reports waiting on an administrator. */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping(ApiConstants.Admin.ESCALATED_REPORT_COUNT)
    @StrictQueryParameters
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<EscalatedReportCountResponse>> countEscalatedReports() {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        adminService.countEscalatedReports(SecurityUtils.getCurrentUserId())));
    }

    /** Returns the reported entity for moderation review, uncacheable by design. */
    @Override
    @GetMapping(ApiConstants.Admin.REPORT_TARGET)
    @StrictQueryParameters
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminReportTargetResponse>> getReportTarget(
            @PathVariable("reportId") UUID reportId) {
        // no-store, not no-cache. The body is content a moderator is allowed to see only
        // because it was reported, and it must not survive in a shared cache or a browser's
        // back-forward store after the report is closed.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        ApiResponse.success(
                                ApiSuccessCode.OK,
                                adminReportTargetService.getReportTarget(
                                        SecurityUtils.getCurrentUserId(), reportId)));
    }

    // Generic rather than typed to AdminActionResponse: the post restore answers a different shape,
    // and a helper that only fits fourteen of the fifteen handlers invites the fifteenth to build
    // its envelope by hand and drift.
    private <T> ResponseEntity<ApiResponse<T>> ok(T response) {
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, response));
    }

    private ResponseEntity<ApiResponse<CursorPageResponse<AdminActionSummaryResponse>>> page(
            CursorPageResponse<AdminActionSummaryResponse> response) {
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, response));
    }
}
