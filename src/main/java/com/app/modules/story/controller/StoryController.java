package com.app.modules.story.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
import com.app.modules.story.api.StoryApi;
import com.app.modules.story.dto.request.CreateStoryRequest;
import com.app.modules.story.dto.response.StoryFeedItemResponse;
import com.app.modules.story.dto.response.StoryLikeActionResponse;
import com.app.modules.story.dto.response.StoryResponse;
import com.app.modules.story.dto.response.StoryViewActionResponse;
import com.app.modules.story.dto.response.StoryViewerResponse;
import com.app.modules.story.service.StoryLikeService;
import com.app.modules.story.service.StoryService;
import com.app.modules.story.service.StoryViewService;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/** HTTP surface for story creation, feed tray, reads, and deletion. */
@RestController
public class StoryController extends BaseController implements StoryApi {

    private final StoryService storyService;
    private final StoryViewService storyViewService;
    private final StoryLikeService storyLikeService;

    public StoryController(
            StoryService storyService,
            StoryViewService storyViewService,
            StoryLikeService storyLikeService) {
        this.storyService = storyService;
        this.storyViewService = storyViewService;
        this.storyLikeService = storyLikeService;
    }

    /** Creates a story for the authenticated user; returns 201 with the story. */
    @Override
    @PostMapping(ApiConstants.Stories.ROOT)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<StoryResponse>> createStory(
            @Valid @RequestBody CreateStoryRequest request) {
        StoryResponse body = storyService.createStory(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(ApiSuccessCode.CREATED, body));
    }

    /** Returns the authenticated user's story feed tray grouped by author. */
    @Override
    @GetMapping(ApiConstants.Stories.ROOT + ApiConstants.Stories.FEED)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<List<StoryFeedItemResponse>>> getStoryFeed() {
        List<StoryFeedItemResponse> body =
                storyService.getStoryFeed(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Returns active stories from suggested accounts the caller does not follow. */
    @Override
    @GetMapping(ApiConstants.Stories.ROOT + ApiConstants.Stories.DISCOVERY)
    @StrictQueryParameters
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<List<StoryFeedItemResponse>>> discoverStories(
            @RequestParam(defaultValue = "8") @Min(1) @Max(20) int limit) {
        List<StoryFeedItemResponse> body =
                storyService.discoverStories(SecurityUtils.getCurrentUserId(), limit);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Lists a user's active stories, gated by block and private-account rules. */
    @Override
    @GetMapping(ApiConstants.Stories.ROOT + ApiConstants.Stories.USER_STORIES)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<List<StoryResponse>>> listUserStories(
            @PathVariable("userId") UUID userId) {
        List<StoryResponse> body =
                storyService.listUserStories(SecurityUtils.getCurrentUserId(), userId);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Returns one active story visible to the authenticated user. */
    @Override
    @GetMapping(ApiConstants.Stories.ROOT + ApiConstants.Stories.BY_ID)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<StoryResponse>> getStoryById(
            @PathVariable("storyId") UUID storyId) {
        StoryResponse body = storyService.getStoryById(SecurityUtils.getCurrentUserId(), storyId);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Soft-deletes a story owned by the authenticated user. */
    @Override
    @DeleteMapping(ApiConstants.Stories.ROOT + ApiConstants.Stories.BY_ID)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<Void>> deleteStory(@PathVariable("storyId") UUID storyId) {
        storyService.deleteStory(SecurityUtils.getCurrentUserId(), storyId);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK));
    }

    /** Records a deduplicated view of the story for the authenticated user. */
    @Override
    @PostMapping(ApiConstants.Stories.ROOT + ApiConstants.Stories.VIEWS)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<StoryViewActionResponse>> recordView(
            @PathVariable("storyId") UUID storyId) {
        StoryViewActionResponse body =
                storyViewService.recordView(SecurityUtils.getCurrentUserId(), storyId);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Lists the story's viewers for its owner, newest view first. */
    @Override
    @GetMapping(ApiConstants.Stories.ROOT + ApiConstants.Stories.VIEWS)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<StoryViewerResponse>>> listViewers(
            @PathVariable("storyId") UUID storyId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        CursorPageResponse<StoryViewerResponse> body =
                storyViewService.listViewers(
                        SecurityUtils.getCurrentUserId(), storyId, cursor, limit);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Likes an active story visible to the authenticated user; returns 201 with the new state. */
    @Override
    @PostMapping(ApiConstants.Stories.ROOT + ApiConstants.Stories.LIKES)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<StoryLikeActionResponse>> likeStory(
            @PathVariable("storyId") UUID storyId) {
        StoryLikeActionResponse body =
                storyLikeService.likeStory(SecurityUtils.getCurrentUserId(), storyId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(ApiSuccessCode.CREATED, body));
    }

    /** Removes the authenticated user's like from a story. */
    @Override
    @DeleteMapping(ApiConstants.Stories.ROOT + ApiConstants.Stories.LIKES)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<StoryLikeActionResponse>> unlikeStory(
            @PathVariable("storyId") UUID storyId) {
        StoryLikeActionResponse body =
                storyLikeService.unlikeStory(SecurityUtils.getCurrentUserId(), storyId);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }
}
