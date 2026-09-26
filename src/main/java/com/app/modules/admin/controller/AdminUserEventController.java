package com.app.modules.admin.controller;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.app.common.ApiConstants;
import com.app.common.base.BaseController;
import com.app.common.enums.ApiSuccessCode;
import com.app.common.response.ApiResponse;
import com.app.common.response.CursorPageResponse;
import com.app.common.security.util.SecurityUtils;
import com.app.common.web.StrictQueryParameters;
import com.app.modules.admin.api.AdminUserEventApi;
import com.app.modules.admin.service.AdminUserEventService;
import com.app.modules.recommendation.dto.response.UserEventResponse;
import com.app.modules.recommendation.enums.UserEventType;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/**
 * REST endpoint for the administrative behavioural activity log.
 *
 * <p>The class-level {@code @PreAuthorize} is the first of two independent role gates. This path
 * sits under {@code /api/v1/admin/} but outside the {@code /api/v1/admin/users/**} sub-tree, so the
 * matcher that applies in {@code SecurityConfig} is the broader {@code /api/v1/admin/**} rule,
 * which admits a moderator. Reading what any account did is an administrator's privilege, so the
 * narrowing happens here, the same way the hashtag registry does it.
 *
 * <p>The second gate is {@code AdminAuthorizationService.assertActorIsAdministrator}, called by
 * {@code AdminUserEventServiceImpl.listUserEvents}. Deleting this annotation no longer opens the
 * endpoint, which is why the read takes an actor id.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserEventController extends BaseController implements AdminUserEventApi {

    private final AdminUserEventService adminUserEventService;

    public AdminUserEventController(AdminUserEventService adminUserEventService) {
        this.adminUserEventService = adminUserEventService;
    }

    /** Returns a cursor page of behavioural events inside a mandatory bounded window. */
    @Override
    @GetMapping(ApiConstants.Admin.USER_EVENTS)
    @StrictQueryParameters
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<UserEventResponse>>> listUserEvents(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to,
            @RequestParam(required = false) UserEventType eventType,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return page(
                adminUserEventService.listUserEvents(
                        SecurityUtils.getCurrentUserId(),
                        userId,
                        from,
                        to,
                        eventType,
                        cursor,
                        limit));
    }

    private ResponseEntity<ApiResponse<CursorPageResponse<UserEventResponse>>> page(
            CursorPageResponse<UserEventResponse> response) {
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, response));
    }
}
