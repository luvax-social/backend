package com.app.modules.support.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.app.common.ApiConstants;
import com.app.common.base.BaseController;
import com.app.common.enums.ApiSuccessCode;
import com.app.common.response.ApiResponse;
import com.app.common.security.util.SecurityUtils;
import com.app.modules.support.api.VerificationApi;
import com.app.modules.support.dto.request.CreateVerificationRequest;
import com.app.modules.support.dto.response.VerificationCategoryResponse;
import com.app.modules.support.dto.response.VerificationStateResponse;
import com.app.modules.support.service.VerificationService;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/**
 * The requester-facing half of verification.
 *
 * <p>Unlike the rest of the support centre, every endpoint here is authenticated. A verification
 * request is about an account, so it needs one; there is no anonymous path and no signed link,
 * because there is nothing to contest until a decision exists.
 */
@RestController
public class VerificationController extends BaseController implements VerificationApi {

    private final VerificationService verificationService;

    public VerificationController(VerificationService verificationService) {
        this.verificationService = verificationService;
    }

    /** Lists the verification categories a requester can choose, each with its glyph key. */
    @PreAuthorize("isAuthenticated()")
    @Override
    @GetMapping(ApiConstants.Support.VERIFICATION_CATEGORIES)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<List<VerificationCategoryResponse>>> listCategories() {
        return ResponseEntity.ok(
                ApiResponse.success(ApiSuccessCode.OK, verificationService.listCategories()));
    }

    /**
     * Returns the calling account's verification state: the badge, the outstanding request, or
     * neither.
     */
    @PreAuthorize("isAuthenticated()")
    @Override
    @GetMapping(ApiConstants.Support.VERIFICATION_ME)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<VerificationStateResponse>> myState() {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        verificationService.myState(SecurityUtils.getCurrentUserId())));
    }

    /**
     * Submits a verification request for the calling account.
     *
     * <p>Refused with {@code VERIFICATION_INSUFFICIENT_EVIDENCE} when fewer than three of the seven
     * evidence fields carry text. The server is the contract; a client checking first only spares
     * the round trip.
     */
    @PreAuthorize("isAuthenticated()")
    @Override
    @PostMapping(ApiConstants.Support.VERIFICATION_REQUESTS)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<VerificationStateResponse>> submit(
            @Valid @RequestBody CreateVerificationRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.CREATED,
                        verificationService.submit(SecurityUtils.getCurrentUserId(), request)));
    }
}
