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
import com.app.modules.admin.api.AdminHashtagApi;
import com.app.modules.admin.dto.request.AdminCreateHashtagRequest;
import com.app.modules.admin.dto.request.AdminDeleteHashtagRequest;
import com.app.modules.admin.dto.request.AdminHashtagPinRequest;
import com.app.modules.admin.dto.request.AdminUpdateHashtagRequest;
import com.app.modules.admin.dto.response.AdminActionResponse;
import com.app.modules.admin.service.AdminHashtagService;
import com.app.modules.hashtag.dto.response.HashtagAdminResponse;
import com.app.modules.hashtag.enums.HashtagStatus;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/**
 * REST endpoints for the administrative hashtag registry.
 *
 * <p>The class-level {@code @PreAuthorize} is the first of two independent role gates. These paths
 * sit under {@code /api/v1/admin/} but outside the {@code /api/v1/admin/users/**} sub-tree, so the
 * matcher that applies in {@code SecurityConfig} is the broader {@code /api/v1/admin/**} rule,
 * which admits a moderator. Managing the hashtag registry is an administrator's decision, so the
 * narrowing happens here.
 *
 * <p>The second gate is {@code AdminAuthorizationService.assertActorIsAdministrator}, called by
 * every method of {@code AdminHashtagServiceImpl}. Deleting this annotation no longer opens the
 * endpoint, which is why every read here takes an actor id rather than only the writes.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminHashtagController extends BaseController implements AdminHashtagApi {

    private final AdminHashtagService adminHashtagService;

    public AdminHashtagController(AdminHashtagService adminHashtagService) {
        this.adminHashtagService = adminHashtagService;
    }

    /** Returns a cursor page of hashtags spanning every lifecycle status. */
    @Override
    @GetMapping(ApiConstants.Admin.HASHTAGS)
    @StrictQueryParameters
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<HashtagAdminResponse>>> listHashtags(
            @RequestParam(required = false) HashtagStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return page(
                adminHashtagService.listHashtags(
                        SecurityUtils.getCurrentUserId(), status, cursor, limit));
    }

    /** Returns a cursor page of matching hashtags spanning every lifecycle status. */
    @Override
    @GetMapping(ApiConstants.Admin.HASHTAG_SEARCH)
    @StrictQueryParameters
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<HashtagAdminResponse>>> searchHashtags(
            @RequestParam("q") String query,
            @RequestParam(required = false) HashtagStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return page(
                adminHashtagService.searchHashtags(
                        SecurityUtils.getCurrentUserId(), query, status, cursor, limit));
    }

    /** Creates a hashtag directly in the requested lifecycle state. */
    @Override
    @PostMapping(ApiConstants.Admin.HASHTAGS)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminActionResponse>> createHashtag(
            @Valid @RequestBody AdminCreateHashtagRequest request) {
        AdminActionResponse response =
                adminHashtagService.createHashtag(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(ApiSuccessCode.CREATED, response));
    }

    /** Moves a hashtag to the requested lifecycle state. */
    @Override
    @PatchMapping(ApiConstants.Admin.HASHTAG_BY_ID)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminActionResponse>> updateHashtag(
            @PathVariable("hashtagId") UUID hashtagId,
            @Valid @RequestBody AdminUpdateHashtagRequest request) {
        return action(
                adminHashtagService.updateHashtag(
                        SecurityUtils.getCurrentUserId(), hashtagId, request));
    }

    /** Marks a hashtag deleted, leaving its row and its post associations in place. */
    @Override
    @DeleteMapping(ApiConstants.Admin.HASHTAG_BY_ID)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminActionResponse>> deleteHashtag(
            @PathVariable("hashtagId") UUID hashtagId,
            @Valid @RequestBody AdminDeleteHashtagRequest request) {
        return action(
                adminHashtagService.deleteHashtag(
                        SecurityUtils.getCurrentUserId(), hashtagId, request));
    }

    /** Pins a hashtag platform-wide so it leads the trending list. */
    @Override
    @PostMapping(ApiConstants.Admin.HASHTAG_PIN)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminActionResponse>> pinHashtag(
            @PathVariable("hashtagId") UUID hashtagId,
            @Valid @RequestBody AdminHashtagPinRequest request) {
        return action(
                adminHashtagService.pinHashtag(
                        SecurityUtils.getCurrentUserId(), hashtagId, request.note()));
    }

    /** Removes a hashtag's platform-wide pin. */
    @Override
    @DeleteMapping(ApiConstants.Admin.HASHTAG_PIN)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminActionResponse>> unpinHashtag(
            @PathVariable("hashtagId") UUID hashtagId,
            @Valid @RequestBody AdminHashtagPinRequest request) {
        return action(
                adminHashtagService.unpinHashtag(
                        SecurityUtils.getCurrentUserId(), hashtagId, request.note()));
    }

    private ResponseEntity<ApiResponse<AdminActionResponse>> action(AdminActionResponse response) {
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, response));
    }

    private ResponseEntity<ApiResponse<CursorPageResponse<HashtagAdminResponse>>> page(
            CursorPageResponse<HashtagAdminResponse> response) {
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, response));
    }
}
