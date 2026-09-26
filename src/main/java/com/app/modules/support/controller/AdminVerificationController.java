package com.app.modules.support.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.app.common.ApiConstants;
import com.app.common.base.BaseController;
import com.app.common.enums.ApiSuccessCode;
import com.app.common.response.ApiResponse;
import com.app.common.security.util.SecurityUtils;
import com.app.common.web.StrictQueryParameters;
import com.app.modules.support.api.AdminVerificationApi;
import com.app.modules.support.dto.request.VerificationDecisionRequest;
import com.app.modules.support.dto.response.VerificationQueueItemResponse;
import com.app.modules.support.enums.SupportTicketStatus;
import com.app.modules.support.service.VerificationService;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/**
 * The staff-facing verification review queue.
 *
 * <p>Both roles are admitted at the perimeter, and both may decide. Verification is a discretionary
 * grant rather than an enforcement action, so the narrowing that keeps unban and unsuspend
 * administrator-only does not apply; a moderator granting a badge records no verdict they cannot
 * carry out. The annotation is the outer gate only, and the service gates again on the
 * actor-and-target rules and on holding the ticket's claim.
 */
@RestController
@PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
public class AdminVerificationController extends BaseController implements AdminVerificationApi {

    private final VerificationService verificationService;

    public AdminVerificationController(VerificationService verificationService) {
        this.verificationService = verificationService;
    }

    /** Lists verification requests for review, newest first, optionally filtered by status. */
    @Override
    @GetMapping(ApiConstants.Admin.VERIFICATION_QUEUE)
    @StrictQueryParameters
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<List<VerificationQueueItemResponse>>> queue(
            @RequestParam(required = false) SupportTicketStatus status,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        verificationService.queue(
                                SecurityUtils.getCurrentUserId(), status, limit)));
    }

    /** Returns one verification request with the requester's full grant history. */
    @Override
    @GetMapping(ApiConstants.Admin.VERIFICATION_REQUEST_BY_ID)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<VerificationQueueItemResponse>> getRequest(
            @PathVariable UUID ticketId) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        verificationService.getForStaff(
                                SecurityUtils.getCurrentUserId(), ticketId)));
    }

    /** Approves a verification request, granting the badge and mailing the requester. */
    @Override
    @PostMapping(ApiConstants.Admin.VERIFICATION_APPROVE)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<VerificationQueueItemResponse>> approve(
            @PathVariable UUID ticketId, @Valid @RequestBody VerificationDecisionRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        verificationService.approve(
                                SecurityUtils.getCurrentUserId(), ticketId, request)));
    }

    /** Rejects a verification request, granting nothing and mailing the requester the reason. */
    @Override
    @PostMapping(ApiConstants.Admin.VERIFICATION_REJECT)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<VerificationQueueItemResponse>> reject(
            @PathVariable UUID ticketId, @Valid @RequestBody VerificationDecisionRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        verificationService.reject(
                                SecurityUtils.getCurrentUserId(), ticketId, request)));
    }

    /**
     * Withdraws an account's verified badge.
     *
     * <p>Not routed through a ticket, because a revocation is a decision about an account rather
     * than an answer to a request. The grant row is kept with its revocation recorded, so the next
     * reviewer can see what was granted and why it was taken back.
     */
    @Override
    @PostMapping(ApiConstants.Admin.VERIFICATION_REVOKE)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<Void>> revoke(
            @PathVariable UUID userId, @Valid @RequestBody VerificationDecisionRequest request) {
        verificationService.revoke(SecurityUtils.getCurrentUserId(), userId, request);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, null));
    }
}
