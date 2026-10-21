package com.app.modules.social.controller;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.app.common.base.BaseController;
import com.app.common.enums.ApiSuccessCode;
import com.app.common.response.ApiResponse;
import com.app.common.response.CursorPageResponse;
import com.app.common.security.util.SecurityUtils;
import com.app.modules.social.api.MuteApi;
import com.app.modules.social.dto.request.MuteRequest;
import com.app.modules.social.dto.response.MuteResponse;
import com.app.modules.social.service.MuteService;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/**
 * Mute endpoints: set, remove and list the accounts whose posts or stories the authenticated caller
 * has muted.
 *
 * <p>All endpoints require an authenticated caller, resolved via {@link
 * SecurityUtils#getCurrentUserId()}.
 */
@RestController
public class MuteController extends BaseController implements MuteApi {

    private final MuteService muteService;

    public MuteController(MuteService muteService) {
        this.muteService = muteService;
    }

    @Override
    public ResponseEntity<ApiResponse<MuteResponse>> setMute(
            @PathVariable("targetUserId") UUID targetUserId,
            @Valid @RequestBody MuteRequest request) {
        MuteResponse body =
                muteService.setMute(
                        SecurityUtils.getCurrentUserId(),
                        targetUserId,
                        request.mutePosts(),
                        request.muteStories());

        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    @Override
    public ResponseEntity<Void> unmute(UUID targetUserId) {
        muteService.unmute(SecurityUtils.getCurrentUserId(), targetUserId);

        return ResponseEntity.noContent().build();
    }

    @Override
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<MuteResponse>>> listMutes(
            String cursor, int limit) {
        CursorPageResponse<MuteResponse> body =
                muteService.listMutes(SecurityUtils.getCurrentUserId(), cursor, limit);

        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }
}
