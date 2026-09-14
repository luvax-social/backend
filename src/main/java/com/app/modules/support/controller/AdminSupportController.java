package com.app.modules.support.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

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
import com.app.common.security.util.SecurityUtils;
import com.app.common.web.StrictQueryParameters;
import com.app.modules.support.api.AdminSupportApi;
import com.app.modules.support.dto.request.EscalateSupportTicketRequest;
import com.app.modules.support.dto.request.RespondSupportTicketRequest;
import com.app.modules.support.dto.response.SupportTicketStaffResponse;
import com.app.modules.support.enums.SupportTicketStatus;
import com.app.modules.support.service.SupportTicketService;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/**
 * The staff half of the support centre.
 *
 * <p>The class-level annotation admits both staff roles, and it is the first of two independent
 * gates. The second is {@code SupportAuthorizationService}, which every method below reaches
 * through the service. That second gate is where the rule that actually matters lives: a moderator
 * may read an appeal and may escalate it, but may not answer or close one, because unban,
 * unsuspend, revoke-warning and revoke-strike are all administrator-only actions and a moderator
 * closing an appeal would be recording a verdict they cannot carry out.
 *
 * <p>The narrowing is deliberately not expressed as a per-method {@code @PreAuthorize}: it depends
 * on the ticket's category, which an annotation cannot see.
 */
@RestController
@PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
public class AdminSupportController extends BaseController implements AdminSupportApi {

    private final SupportTicketService supportTicketService;

    public AdminSupportController(SupportTicketService supportTicketService) {
        this.supportTicketService = supportTicketService;
    }

    /**
     * Lists the staff queue, optionally filtered by status; never shows unconfirmed submissions.
     */
    @Override
    @GetMapping(ApiConstants.Admin.SUPPORT_TICKETS)
    @StrictQueryParameters
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<List<SupportTicketStaffResponse>>> listTickets(
            @RequestParam(required = false) SupportTicketStatus status,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        supportTicketService.listForStaff(
                                SecurityUtils.getCurrentUserId(), status, limit)));
    }

    /** Reads one ticket as staff, including its internal note. */
    @Override
    @GetMapping(ApiConstants.Admin.SUPPORT_TICKET_BY_ID)
    @StrictQueryParameters
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<SupportTicketStaffResponse>> getTicket(
            @PathVariable("ticketId") UUID ticketId) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        supportTicketService.getForStaff(
                                SecurityUtils.getCurrentUserId(), ticketId)));
    }

    /** Claims an unassigned ticket for the authenticated staff member. */
    @Override
    @PostMapping(ApiConstants.Admin.SUPPORT_TICKET_CLAIM)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<SupportTicketStaffResponse>> claimTicket(
            @PathVariable("ticketId") UUID ticketId) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        supportTicketService.claim(SecurityUtils.getCurrentUserId(), ticketId)));
    }

    /**
     * Answers a ticket and closes it, or closes it as rejected.
     *
     * <p>Refused with {@code SUPPORT_APPEAL_REQUIRES_ADMIN} when a moderator attempts either on an
     * appeal.
     */
    @Override
    @PostMapping(ApiConstants.Admin.SUPPORT_TICKET_RESPOND)
    @StrictQueryParameters
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<SupportTicketStaffResponse>> respondToTicket(
            @PathVariable("ticketId") UUID ticketId,
            @RequestParam(defaultValue = "false") boolean reject,
            @Valid @RequestBody RespondSupportTicketRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        supportTicketService.respond(
                                SecurityUtils.getCurrentUserId(), ticketId, request, reject)));
    }

    /** Hands a ticket up to an administrator. Permitted to both staff roles. */
    @Override
    @PatchMapping(ApiConstants.Admin.SUPPORT_TICKET_ESCALATE)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<SupportTicketStaffResponse>> escalateTicket(
            @PathVariable("ticketId") UUID ticketId,
            @Valid @RequestBody EscalateSupportTicketRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        supportTicketService.escalate(
                                SecurityUtils.getCurrentUserId(), ticketId, request)));
    }
}
