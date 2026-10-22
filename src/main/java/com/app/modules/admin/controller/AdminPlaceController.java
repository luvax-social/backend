package com.app.modules.admin.controller;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

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
import com.app.modules.admin.api.AdminPlaceApi;
import com.app.modules.admin.dto.request.AdminUpdatePlaceRequest;
import com.app.modules.admin.dto.response.AdminActionResponse;
import com.app.modules.admin.service.AdminPlaceService;
import com.app.modules.post.place.dto.response.PlaceAdminResponse;
import com.app.modules.post.place.enums.PlaceStatus;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/**
 * REST endpoints for the administrative places registry.
 *
 * <p>The class-level {@code @PreAuthorize} is the first of two independent role gates, exactly as
 * on the hashtag registry: the paths sit under the broader {@code /api/v1/admin/**} matcher, which
 * admits a moderator, so the narrowing to administrators happens here and again in {@code
 * AdminPlaceServiceImpl}.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminPlaceController extends BaseController implements AdminPlaceApi {

    private final AdminPlaceService adminPlaceService;

    public AdminPlaceController(AdminPlaceService adminPlaceService) {
        this.adminPlaceService = adminPlaceService;
    }

    /** Returns a cursor page of places spanning every lifecycle status. */
    @Override
    @GetMapping(ApiConstants.Admin.PLACES)
    @StrictQueryParameters
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<PlaceAdminResponse>>> listPlaces(
            @RequestParam(required = false) PlaceStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        adminPlaceService.listPlaces(
                                SecurityUtils.getCurrentUserId(), status, cursor, limit)));
    }

    /** Returns a cursor page of matching places spanning every lifecycle status. */
    @Override
    @GetMapping(ApiConstants.Admin.PLACE_SEARCH)
    @StrictQueryParameters
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<PlaceAdminResponse>>> searchPlaces(
            @RequestParam("q") String query,
            @RequestParam(required = false) PlaceStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        adminPlaceService.searchPlaces(
                                SecurityUtils.getCurrentUserId(), query, status, cursor, limit)));
    }

    /** Moves a place to the requested lifecycle state and writes the audit row. */
    @Override
    @PatchMapping(ApiConstants.Admin.PLACE_BY_ID)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<AdminActionResponse>> updatePlace(
            @PathVariable("placeId") UUID placeId,
            @Valid @RequestBody AdminUpdatePlaceRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        adminPlaceService.updatePlace(
                                SecurityUtils.getCurrentUserId(), placeId, request)));
    }
}
