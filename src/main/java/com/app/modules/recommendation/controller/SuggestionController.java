package com.app.modules.recommendation.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.app.common.ApiConstants;
import com.app.common.base.BaseController;
import com.app.common.enums.ApiSuccessCode;
import com.app.common.response.ApiResponse;
import com.app.common.security.util.SecurityUtils;
import com.app.common.web.StrictQueryParameters;
import com.app.modules.recommendation.api.SuggestionApi;
import com.app.modules.recommendation.dto.response.SuggestedUserResponse;
import com.app.modules.recommendation.service.SuggestionService;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/** People you may know. */
@RestController
public class SuggestionController extends BaseController implements SuggestionApi {

    private final SuggestionService suggestionService;

    public SuggestionController(SuggestionService suggestionService) {
        this.suggestionService = suggestionService;
    }

    /**
     * Lists accounts the caller may know, filtered at read time.
     *
     * <p>Answers the verified cold-start list when nothing personalised survives the filters, so a
     * brand new account is never shown an empty rail.
     */
    @Override
    @PreAuthorize("isAuthenticated()")
    @GetMapping(ApiConstants.Recommendations.SUGGESTIONS)
    @StrictQueryParameters
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<List<SuggestedUserResponse>>> suggestions(
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK,
                        suggestionService.suggestionsFor(SecurityUtils.getCurrentUserId(), limit)));
    }

    /**
     * Permanently removes one account from the caller's suggestions.
     *
     * <p>Not a block: the dismissed account keeps appearing in search, on profiles and everywhere
     * else, and is never told. Idempotent, so a repeated dismissal is not an error.
     */
    @Override
    @PreAuthorize("isAuthenticated()")
    @PostMapping(ApiConstants.Recommendations.SUGGESTION_DISMISS)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<Void>> dismiss(@PathVariable UUID userId) {
        suggestionService.dismiss(SecurityUtils.getCurrentUserId(), userId);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, null));
    }
}
