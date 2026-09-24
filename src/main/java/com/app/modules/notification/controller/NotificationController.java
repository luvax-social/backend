package com.app.modules.notification.controller;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.app.common.ApiConstants;
import com.app.common.base.BaseController;
import com.app.common.enums.ApiErrorCode;
import com.app.common.enums.ApiSuccessCode;
import com.app.common.exception.AppException;
import com.app.common.response.ApiResponse;
import com.app.common.security.util.SecurityUtils;
import com.app.modules.notification.api.NotificationApi;
import com.app.modules.notification.dto.request.AdvanceSeenRequest;
import com.app.modules.notification.dto.request.ReadAllRequest;
import com.app.modules.notification.dto.response.NotificationPageResponse;
import com.app.modules.notification.dto.response.NotificationReadStateResponse;
import com.app.modules.notification.dto.response.NotificationStateResponse;
import com.app.modules.notification.dto.response.ReadAllResponse;
import com.app.modules.notification.entity.enums.NotificationFilter;
import com.app.modules.notification.service.NotificationService;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/** REST endpoints for the activity feed; every endpoint requires an authenticated bearer token. */
@RestController
public class NotificationController extends BaseController implements NotificationApi {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** Returns a page of the caller's feed for a filter, with head on the first page. */
    @Override
    @GetMapping
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<NotificationPageResponse>> listNotifications(
            @RequestParam(value = "filter", required = false) String filter,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") @Min(1) @Max(100) int limit) {
        NotificationFilter parsed = NotificationFilter.fromQueryValue(filter);
        if (parsed == null) {
            throw new AppException(ApiErrorCode.BAD_REQUEST, "Unknown notification filter");
        }
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        notificationService.listFeed(
                                SecurityUtils.getCurrentUserId(), parsed, cursor, limit)));
    }

    /** Returns the caller's badge, watermarks and pending follow-request summary. */
    @Override
    @GetMapping(ApiConstants.Notifications.STATE)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<NotificationStateResponse>> getState() {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        notificationService.getState(SecurityUtils.getCurrentUserId())));
    }

    /** Advances the caller's seen watermark and returns the new state. */
    @Override
    @PostMapping(ApiConstants.Notifications.SEEN)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<NotificationStateResponse>> advanceSeen(
            @Valid @RequestBody AdvanceSeenRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        notificationService.advanceSeen(
                                SecurityUtils.getCurrentUserId(), request)));
    }

    /** Marks one of the caller's notifications read. */
    @Override
    @PutMapping(ApiConstants.Notifications.READ)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<NotificationReadStateResponse>> markRead(
            @PathVariable("notificationId") UUID notificationId) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        notificationService.markRead(
                                SecurityUtils.getCurrentUserId(), notificationId)));
    }

    /** Marks one of the caller's notifications unread. */
    @Override
    @DeleteMapping(ApiConstants.Notifications.READ)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<NotificationReadStateResponse>> markUnread(
            @PathVariable("notificationId") UUID notificationId) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        notificationService.markUnread(
                                SecurityUtils.getCurrentUserId(), notificationId)));
    }

    /** Marks read every notification of the caller's at or below the rendered bound. */
    @Override
    @PatchMapping(ApiConstants.Notifications.READ_ALL)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<ReadAllResponse>> markReadUpTo(
            @Valid @RequestBody ReadAllRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        notificationService.markReadUpTo(
                                SecurityUtils.getCurrentUserId(), request.upTo())));
    }

    /** Removes one of the caller's notifications from their feed. */
    @Override
    @DeleteMapping(ApiConstants.Notifications.BY_ID)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<Void> delete(@PathVariable("notificationId") UUID notificationId) {
        notificationService.delete(SecurityUtils.getCurrentUserId(), notificationId);
        return ResponseEntity.noContent().build();
    }
}
