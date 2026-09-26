package com.app.modules.hashtag.controller;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
import com.app.common.response.PageResponse;
import com.app.common.security.util.SecurityUtils;
import com.app.common.web.StrictQueryParameters;
import com.app.modules.hashtag.api.HashtagApi;
import com.app.modules.hashtag.dto.response.HashtagDetailResponse;
import com.app.modules.hashtag.dto.response.HashtagResponse;
import com.app.modules.hashtag.dto.response.HashtagTrendingResponse;
import com.app.modules.hashtag.dto.response.TrendingPreviewResponse;
import com.app.modules.hashtag.service.HashtagLookupService;
import com.app.modules.hashtag.service.HashtagSearchService;
import com.app.modules.hashtag.service.HashtagTrendingService;
import com.app.modules.hashtag.service.PersonalisedTrendingService;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/** HTTP surface for hashtag search and trending endpoints. */
@RestController
public class HashtagController extends BaseController implements HashtagApi {

    private static final String PLATFORM_SCOPE = "platform";

    private final HashtagSearchService hashtagSearchService;
    private final HashtagTrendingService hashtagTrendingService;
    private final HashtagLookupService hashtagLookupService;
    private final PersonalisedTrendingService personalisedTrendingService;

    public HashtagController(
            HashtagSearchService hashtagSearchService,
            HashtagTrendingService hashtagTrendingService,
            HashtagLookupService hashtagLookupService,
            PersonalisedTrendingService personalisedTrendingService) {
        this.hashtagSearchService = hashtagSearchService;
        this.hashtagTrendingService = hashtagTrendingService;
        this.hashtagLookupService = hashtagLookupService;
        this.personalisedTrendingService = personalisedTrendingService;
    }

    /** Fuzzy hashtag name search; falls back to pg_trgm when Elasticsearch is unavailable. */
    @Override
    @GetMapping(ApiConstants.Hashtags.SEARCH)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<HashtagResponse>>> search(
            String q, String cursor, int limit) {
        CursorPageResponse<HashtagResponse> result = hashtagSearchService.search(q, cursor, limit);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, result));
    }

    /** Resolves a hashtag name to its record; 404s when the hashtag is out of circulation. */
    @Override
    @GetMapping(ApiConstants.Hashtags.BY_NAME)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<HashtagDetailResponse>> getByName(String name) {
        HashtagDetailResponse result = hashtagLookupService.getByName(name);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, result));
    }

    /** Returns trending hashtags ranked for the caller; falls back to the platform list. */
    @Override
    @GetMapping(ApiConstants.Hashtags.TRENDING_FOR_YOU)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<PageResponse<HashtagTrendingResponse>>> trendingForYou(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        PageResponse<HashtagTrendingResponse> result =
                personalisedTrendingService.getPersonalisedTrending(
                        SecurityUtils.getCurrentUserId(), PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, result));
    }

    /** Returns the latest trending hashtag snapshot. */
    @Override
    @GetMapping(ApiConstants.Hashtags.TRENDING)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<PageResponse<HashtagTrendingResponse>>> trending(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        // Ordering is always rank ASC within the latest period (HashtagTrendingServiceImpl);
        // an unsorted Pageable keeps a client-supplied sort field from reaching the repository
        // query and failing with a PropertyReferenceException for fields HashtagTrending lacks.
        Pageable pageable = PageRequest.of(page, size);
        PageResponse<HashtagTrendingResponse> result = hashtagTrendingService.getTrending(pageable);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, result));
    }

    /** Returns trending hashtags with one post cover each, for the in-feed trending card. */
    @Override
    @PreAuthorize("isAuthenticated()")
    @GetMapping(ApiConstants.Hashtags.TRENDING_PREVIEWS)
    @StrictQueryParameters
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<List<TrendingPreviewResponse>>> trendingPreviews(
            @RequestParam(defaultValue = "3") @Min(1) @Max(10) int size,
            @RequestParam(defaultValue = "for-you") String scope) {
        Pageable pageable = PageRequest.of(0, size);
        // The source is chosen here rather than inside the service because the personalised
        // service already depends on the trending service; reaching the other way would close a
        // dependency cycle. The two existing trending endpoints pick their source the same way.
        List<HashtagTrendingResponse> entries =
                PLATFORM_SCOPE.equals(scope)
                        ? hashtagTrendingService.getTrending(pageable).getContent()
                        : personalisedTrendingService
                                .getPersonalisedTrending(SecurityUtils.getCurrentUserId(), pageable)
                                .getContent();
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK, hashtagTrendingService.attachCovers(entries)));
    }
}
