package com.app.modules.post.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.app.common.ApiConstants;
import com.app.common.base.BaseController;
import com.app.common.enums.ApiErrorCode;
import com.app.common.enums.ApiSuccessCode;
import com.app.common.exception.AppException;
import com.app.common.response.ApiResponse;
import com.app.common.response.CursorPageResponse;
import com.app.common.security.util.SecurityUtils;
import com.app.modules.post.api.OwnerPostApi;
import com.app.modules.post.dto.response.CollaborationInviteResponse;
import com.app.modules.post.dto.response.OwnerPostCountsResponse;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.dto.response.TagRequestResponse;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.service.PostCollaborationService;
import com.app.modules.post.service.PostOwnerService;
import com.app.modules.post.service.PostService;
import com.app.modules.post.service.PostTagService;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/** HTTP surface for the signed-in user's own post lists, requests and invitations. */
@RestController
public class OwnerPostController extends BaseController implements OwnerPostApi {

    private final PostOwnerService postOwnerService;
    private final PostService postService;
    private final PostTagService postTagService;
    private final PostCollaborationService postCollaborationService;

    public OwnerPostController(
            PostOwnerService postOwnerService,
            PostService postService,
            PostTagService postTagService,
            PostCollaborationService postCollaborationService) {
        this.postOwnerService = postOwnerService;
        this.postService = postService;
        this.postTagService = postTagService;
        this.postCollaborationService = postCollaborationService;
    }

    /** Lists the drafts, scheduled posts or archived posts of the caller. */
    @Override
    @GetMapping(ApiConstants.Users.ME_POSTS)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<PostResponse>>> listOwnerPosts(
            @RequestParam("status") String status,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        CursorPageResponse<PostResponse> body =
                postOwnerService.listOwnerPosts(
                        SecurityUtils.getCurrentUserId(), parseOwnerStatus(status), cursor, limit);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Counts the drafts, scheduled posts and archived posts of the caller. */
    @Override
    @GetMapping(ApiConstants.Users.ME_POST_COUNTS)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<OwnerPostCountsResponse>> getOwnerPostCounts() {
        OwnerPostCountsResponse body = postOwnerService.counts(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Lists the tags of the caller that wait for approval. */
    @Override
    @GetMapping(ApiConstants.Users.ME_PENDING_TAGS)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<TagRequestResponse>>> listPendingTags(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        CursorPageResponse<TagRequestResponse> body =
                postTagService.listPending(SecurityUtils.getCurrentUserId(), cursor, limit);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Lists the invitations to co-author that the caller has not answered. */
    @Override
    @GetMapping(ApiConstants.Users.ME_COLLABORATION_INVITES)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<CollaborationInviteResponse>>>
            listCollaborationInvites(
                    @RequestParam(value = "cursor", required = false) String cursor,
                    @RequestParam(value = "limit", defaultValue = "20") int limit) {
        CursorPageResponse<CollaborationInviteResponse> body =
                postCollaborationService.listInvites(
                        SecurityUtils.getCurrentUserId(), cursor, limit);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Lists the published posts a user is tagged in, as the caller may see them. */
    @Override
    @GetMapping(ApiConstants.Users.TAGGED_POSTS)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<PostResponse>>> listTaggedPosts(
            @PathVariable("userId") UUID userId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        CursorPageResponse<PostResponse> body =
                postService.listTaggedPosts(
                        SecurityUtils.getCurrentUserId(), userId, cursor, limit);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    private static PostStatus parseOwnerStatus(String status) {
        PostStatus parsed;
        try {
            parsed = PostStatus.fromJson(status);
        } catch (IllegalArgumentException e) {
            parsed = null;
        }
        if (parsed != PostStatus.DRAFT
                && parsed != PostStatus.SCHEDULED
                && parsed != PostStatus.ARCHIVED) {
            throw new AppException(
                    ApiErrorCode.BAD_REQUEST, "status must be draft, scheduled or archived");
        }
        return parsed;
    }
}
