package com.app.modules.support.controller;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

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
import com.app.common.security.util.IpExtractor;
import com.app.common.security.util.SecurityUtils;
import com.app.common.vocabulary.dto.response.SupportCategoryVocabularyResponse;
import com.app.common.vocabulary.service.VocabularyService;
import com.app.common.web.StrictQueryParameters;
import com.app.modules.support.api.SupportApi;
import com.app.modules.support.dto.request.CreateSupportTicketRequest;
import com.app.modules.support.dto.request.PublicSupportTicketRequest;
import com.app.modules.support.dto.request.SignedAppealRequest;
import com.app.modules.support.dto.response.AppealLinkResponse;
import com.app.modules.support.dto.response.SupportTicketResponse;
import com.app.modules.support.service.SupportTicketService;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/**
 * The user-facing half of the support centre.
 *
 * <p>Three of these endpoints are anonymous, and that is the point of the module. {@code
 * TokenPrincipalResolverImpl} admits only {@code ACTIVE} accounts, so a banned or suspended user -
 * the population most likely to need support - cannot reach an authenticated endpoint at all. The
 * appeal, public-form and confirmation paths therefore carry their own controls rather than relying
 * on a session: a single-use token for the first, Turnstile plus email confirmation for the second.
 *
 * <p>None of the anonymous paths issues a session, a token pair or a refresh token row.
 */
@RestController
public class SupportController extends BaseController implements SupportApi {

    private final SupportTicketService supportTicketService;
    private final IpExtractor ipExtractor;
    private final VocabularyService vocabularyService;

    public SupportController(
            SupportTicketService supportTicketService,
            IpExtractor ipExtractor,
            VocabularyService vocabularyService) {
        this.supportTicketService = supportTicketService;
        this.ipExtractor = ipExtractor;
        this.vocabularyService = vocabularyService;
    }

    /** Opens a support ticket for the authenticated account. */
    @PreAuthorize("isAuthenticated()")
    @Override
    @PostMapping(ApiConstants.Support.TICKETS)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<SupportTicketResponse>> createTicket(
            @Valid @RequestBody CreateSupportTicketRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.CREATED,
                        supportTicketService.createAuthenticated(
                                SecurityUtils.getCurrentUserId(), request)));
    }

    /** Lists the authenticated account's own support tickets, newest first. */
    @PreAuthorize("isAuthenticated()")
    @Override
    @GetMapping(ApiConstants.Support.TICKETS)
    @StrictQueryParameters
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<List<SupportTicketResponse>>> listOwnTickets(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        supportTicketService.listOwn(SecurityUtils.getCurrentUserId(), limit)));
    }

    /** Reads one of the authenticated account's own support tickets. */
    @PreAuthorize("isAuthenticated()")
    @Override
    @GetMapping(ApiConstants.Support.TICKET_BY_ID)
    @StrictQueryParameters
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<SupportTicketResponse>> getOwnTicket(
            @PathVariable("ticketId") UUID ticketId) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        supportTicketService.getOwn(SecurityUtils.getCurrentUserId(), ticketId)));
    }

    /**
     * Opens an appeal by redeeming the single-use token from a moderation notice.
     *
     * <p>Anonymous by necessity: the account this authorises is banned or suspended and cannot
     * authenticate. Redeeming the token creates exactly one ticket and mints no session.
     */
    @Override
    @PostMapping(ApiConstants.Support.APPEAL)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<SupportTicketResponse>> createAppeal(
            @Valid @RequestBody SignedAppealRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.CREATED,
                        supportTicketService.createFromSignedLink(request)));
    }

    /**
     * Accepts a public support request, held invisible to staff until the address is confirmed.
     *
     * <p>Returns no ticket. Echoing one back would tell an anonymous caller that a submission
     * succeeded for an address they may not own, and the ticket is not real until confirmed.
     */
    @Override
    @PostMapping(ApiConstants.Support.PUBLIC_TICKET)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<Void>> createPublicTicket(
            @Valid @RequestBody PublicSupportTicketRequest request,
            HttpServletRequest servletRequest) {
        supportTicketService.createPublic(request, ipExtractor.extract(servletRequest));
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, null));
    }

    /**
     * Reports whether an appeal link is still redeemable, without redeeming it.
     *
     * <p>Anonymous for the same reason as the appeal itself. Read-only by construction: the landing
     * screen calls this on mount, so redeeming here would spend the link merely by opening the page
     * - and a mail client prefetching the URL would spend it before the reader saw it at all.
     *
     * <p>Answers the appeal category and nothing else. A caller holding the token learns only what
     * the form was going to tell them anyway; a caller without one learns nothing, because the
     * token is 32 random bytes and this route carries its own per-client rate limit.
     */
    @Override
    @GetMapping(ApiConstants.Support.APPEAL_VALIDATE)
    @StrictQueryParameters
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AppealLinkResponse>> validateAppealLink(
            @RequestParam("token") @NotBlank String token) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK, supportTicketService.describeSignedLink(token)));
    }

    /**
     * Lists the categories the public form may offer, without a session.
     *
     * <p>The config vocabulary requires authentication, and the submitter here has none - that is
     * the whole premise of this path. Without this the anonymous form would need a client-side copy
     * of the category table, which drifts the moment a category is added or disabled.
     *
     * <p>Returns strictly less than the authenticated vocabulary: support categories only, and only
     * those flagged enabled and public-form. It exposes display metadata and no account data.
     */
    @Override
    @GetMapping(ApiConstants.Support.PUBLIC_CATEGORIES)
    @StrictQueryParameters
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<List<SupportCategoryVocabularyResponse>>>
            listPublicCategories() {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK, vocabularyService.getPublicSupportCategories()));
    }

    /** Confirms a public submission and moves it into the staff queue. */
    @Override
    @PostMapping(ApiConstants.Support.CONFIRM)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<Void>> confirmPublicTicket(
            @RequestParam("token") @NotBlank String token) {
        supportTicketService.confirmPublic(token);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, null));
    }
}
