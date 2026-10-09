package com.app.modules.admin.controller;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import com.app.common.security.util.SecurityUtils;
import com.app.common.web.StrictQueryParameters;
import com.app.modules.admin.api.AdminHiddenWordApi;
import com.app.modules.admin.dto.request.AdminDeleteHiddenWordRequest;
import com.app.modules.admin.dto.request.AdminHiddenWordRequest;
import com.app.modules.admin.service.AdminHiddenWordService;
import com.app.modules.social.dto.response.AdminHiddenWordResponse;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/**
 * REST endpoints for the platform-wide hidden word list.
 *
 * <p>The class-level {@code @PreAuthorize} is the first of two independent role gates. These paths
 * sit under {@code /api/v1/admin/} but outside the {@code /api/v1/admin/users/**} sub-tree, so the
 * matcher that applies in {@code SecurityConfig} is the broader {@code /api/v1/admin/**} rule,
 * which admits a moderator. Changing what a whole audience is shown is an administrator's decision,
 * so the narrowing happens here, exactly as for the hashtag registry.
 *
 * <p>The second gate is {@code AdminAuthorizationService.assertActorIsAdministrator}, called by
 * every method of {@code AdminHiddenWordServiceImpl}.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminHiddenWordController extends BaseController implements AdminHiddenWordApi {

    private final AdminHiddenWordService adminHiddenWordService;

    public AdminHiddenWordController(AdminHiddenWordService adminHiddenWordService) {
        this.adminHiddenWordService = adminHiddenWordService;
    }

    /** Returns a cursor page of the platform-wide hidden words, optionally narrowed by a term. */
    @Override
    @GetMapping(ApiConstants.Admin.HIDDEN_WORDS)
    @StrictQueryParameters
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<AdminHiddenWordResponse>>> listHiddenWords(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        adminHiddenWordService.list(
                                SecurityUtils.getCurrentUserId(), query, cursor, limit)));
    }

    /** Adds a phrase to the platform-wide list and records the audit row. */
    @Override
    @PostMapping(ApiConstants.Admin.HIDDEN_WORDS)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminHiddenWordResponse>> createHiddenWord(
            @Valid @RequestBody AdminHiddenWordRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        ApiResponse.success(
                                ApiSuccessCode.CREATED,
                                adminHiddenWordService.create(
                                        SecurityUtils.getCurrentUserId(), request)));
    }

    /** Replaces the phrase of one entry and records the audit row. */
    @Override
    @PatchMapping(ApiConstants.Admin.HIDDEN_WORD_BY_ID)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminHiddenWordResponse>> updateHiddenWord(
            @PathVariable("hiddenWordId") UUID hiddenWordId,
            @Valid @RequestBody AdminHiddenWordRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        adminHiddenWordService.update(
                                SecurityUtils.getCurrentUserId(), hiddenWordId, request)));
    }

    /** Removes one entry from the platform-wide list and records the audit row. */
    @Override
    @DeleteMapping(ApiConstants.Admin.HIDDEN_WORD_BY_ID)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<Void> deleteHiddenWord(
            @PathVariable("hiddenWordId") UUID hiddenWordId,
            @Valid @RequestBody AdminDeleteHiddenWordRequest request) {
        adminHiddenWordService.delete(SecurityUtils.getCurrentUserId(), hiddenWordId, request);
        return ResponseEntity.noContent().build();
    }
}
