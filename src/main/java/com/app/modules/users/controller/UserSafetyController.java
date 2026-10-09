package com.app.modules.users.controller;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.app.common.ApiConstants;
import com.app.common.base.BaseController;
import com.app.common.enums.ApiSuccessCode;
import com.app.common.response.ApiResponse;
import com.app.common.security.util.SecurityUtils;
import com.app.modules.social.dto.request.CreateHiddenWordRequest;
import com.app.modules.social.dto.response.HiddenWordEntryResponse;
import com.app.modules.social.dto.response.HiddenWordListResponse;
import com.app.modules.social.service.HiddenWordService;
import com.app.modules.users.api.UserSafetyApi;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/** REST endpoints for the interaction-safety controls an account keeps about itself. */
@RestController
public class UserSafetyController extends BaseController implements UserSafetyApi {

    private final HiddenWordService hiddenWordService;

    public UserSafetyController(HiddenWordService hiddenWordService) {
        this.hiddenWordService = hiddenWordService;
    }

    /** Returns the authenticated user's personal hidden words, newest first. */
    @Override
    @GetMapping(ApiConstants.Users.ME_HIDDEN_WORDS)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<HiddenWordListResponse>> listMyHiddenWords() {
        UUID userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(
                ApiResponse.success(ApiSuccessCode.OK, hiddenWordService.listPersonal(userId)));
    }

    /** Adds a phrase to the authenticated user's personal hidden words and returns the entry. */
    @Override
    @PostMapping(ApiConstants.Users.ME_HIDDEN_WORDS)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<HiddenWordEntryResponse>> addMyHiddenWord(
            @Valid @RequestBody CreateHiddenWordRequest request) {
        UUID userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        ApiResponse.success(
                                ApiSuccessCode.CREATED,
                                hiddenWordService.addPersonal(userId, request.phrase())));
    }

    /** Removes one entry from the authenticated user's personal hidden words. */
    @Override
    @DeleteMapping(ApiConstants.Users.ME_HIDDEN_WORD_BY_ID)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<Void> removeMyHiddenWord(@PathVariable UUID entryId) {
        UUID userId = SecurityUtils.getCurrentUserId();
        hiddenWordService.removePersonal(userId, entryId);
        return ResponseEntity.noContent().build();
    }
}
