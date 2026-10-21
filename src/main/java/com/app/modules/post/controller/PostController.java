package com.app.modules.post.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import com.app.modules.post.api.PostApi;
import com.app.modules.post.dto.request.CreatePostRequest;
import com.app.modules.post.dto.request.PostStatusTransitionRequest;
import com.app.modules.post.dto.request.UpdatePostCaptionRequest;
import com.app.modules.post.dto.response.FeedPostResponse;
import com.app.modules.post.dto.response.PostEditHistoryResponse;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.dto.response.PostViewResponse;
import com.app.modules.post.enums.FeedSurface;
import com.app.modules.post.service.PostSearchService;
import com.app.modules.post.service.PostService;
import com.app.modules.post.service.PostViewService;
import com.app.modules.post.validation.PostTypeFilter;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/** HTTP surface for post creation, retrieval, lifecycle, edit history, and search. */
@RestController
public class PostController extends BaseController implements PostApi {

    private final PostService postService;
    private final PostSearchService postSearchService;
    private final PostViewService postViewService;

    public PostController(
            PostService postService,
            PostSearchService postSearchService,
            PostViewService postViewService) {
        this.postService = postService;
        this.postSearchService = postSearchService;
        this.postViewService = postViewService;
    }

    /** Creates a post for the authenticated user; returns 201 with the created post. */
    @Override
    @PostMapping
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<PostResponse>> createPost(
            @Valid @RequestBody CreatePostRequest request) {
        PostResponse body = postService.createPost(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(ApiSuccessCode.CREATED, body));
    }

    /** Returns a single post visible to the authenticated viewer. */
    @Override
    @GetMapping(ApiConstants.Posts.BY_ID)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<PostResponse>> getPostById(
            @PathVariable("postId") UUID postId) {
        PostResponse body = postService.getPostById(SecurityUtils.getCurrentUserId(), postId);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Replaces the caption of an owned post; requires authentication as the owner. */
    @Override
    @PatchMapping(ApiConstants.Posts.BY_ID)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<PostResponse>> updateCaption(
            @PathVariable("postId") UUID postId,
            @Valid @RequestBody UpdatePostCaptionRequest request) {
        PostResponse body =
                postService.updateCaption(SecurityUtils.getCurrentUserId(), postId, request);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Transitions the post lifecycle status; owner only, or admin for removal. */
    @Override
    @PatchMapping(ApiConstants.Posts.STATUS)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<PostResponse>> transitionStatus(
            @PathVariable("postId") UUID postId,
            @Valid @RequestBody PostStatusTransitionRequest request) {
        PostResponse body =
                postService.transitionStatus(SecurityUtils.getCurrentUserId(), postId, request);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Soft-deletes a post; owner or admin only; returns 204. */
    @Override
    @DeleteMapping(ApiConstants.Posts.BY_ID)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<Void> deletePost(@PathVariable("postId") UUID postId) {
        postService.deletePost(SecurityUtils.getCurrentUserId(), postId);
        return ResponseEntity.noContent().build();
    }

    /** Returns the chronological following feed for the authenticated viewer. */
    @Override
    @GetMapping(ApiConstants.Posts.FEED)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<FeedPostResponse>>> getFeed(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        CursorPageResponse<FeedPostResponse> body =
                postService.getFeed(
                        SecurityUtils.getCurrentUserId(), cursor, limit, FeedSurface.FOLLOWING);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Lists a user's published posts visible to the authenticated viewer. */
    @Override
    @GetMapping(ApiConstants.Posts.USER_POSTS)
    @StrictQueryParameters
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<PostResponse>>> listUserPosts(
            @PathVariable("userId") UUID userId,
            @RequestParam(value = "type", required = false) List<String> type,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        CursorPageResponse<PostResponse> body =
                postService.listUserPosts(
                        SecurityUtils.getCurrentUserId(),
                        userId,
                        PostTypeFilter.parse(type),
                        cursor,
                        limit);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Lists the caption edit history of an owned post. */
    @Override
    @GetMapping(ApiConstants.Posts.HISTORY)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<PostEditHistoryResponse>>> listEditHistory(
            @PathVariable("postId") UUID postId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        CursorPageResponse<PostEditHistoryResponse> body =
                postService.listEditHistory(
                        SecurityUtils.getCurrentUserId(), postId, cursor, limit);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Records that the authenticated viewer has seen a post; returns 202. */
    @Override
    @PostMapping(ApiConstants.Posts.VIEW)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<PostViewResponse>> recordView(
            @PathVariable("postId") UUID postId) {
        PostViewResponse body =
                postViewService.recordView(SecurityUtils.getCurrentUserId(), postId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(ApiSuccessCode.ACCEPTED, body));
    }

    /** Searches published posts by caption; degrades to an empty page when search is down. */
    @Override
    @GetMapping(ApiConstants.Posts.SEARCH)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<PostResponse>>> searchPosts(
            @RequestParam("q") String q,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        CursorPageResponse<PostResponse> body =
                postSearchService.searchPosts(SecurityUtils.getCurrentUserId(), q, cursor, limit);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }
}
