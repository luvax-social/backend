package com.app.modules.post.controller;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.app.common.ApiConstants;
import com.app.common.base.BaseController;
import com.app.common.enums.ApiSuccessCode;
import com.app.common.response.ApiResponse;
import com.app.common.security.util.SecurityUtils;
import com.app.modules.post.api.PostCreationApi;
import com.app.modules.post.dto.request.ReplaceCollaboratorsRequest;
import com.app.modules.post.dto.request.ReplacePostTagsRequest;
import com.app.modules.post.dto.request.SetPostPlaceRequest;
import com.app.modules.post.dto.request.UpdateAltTextRequest;
import com.app.modules.post.dto.request.UpdatePostContentRequest;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.service.PostCollaborationService;
import com.app.modules.post.service.PostOwnerService;
import com.app.modules.post.service.PostTagService;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/** HTTP surface for editing the content, alt text, place, tags and co-authors of a post. */
@RestController
public class PostCreationController extends BaseController implements PostCreationApi {

    private final PostOwnerService postOwnerService;
    private final PostTagService postTagService;
    private final PostCollaborationService postCollaborationService;

    public PostCreationController(
            PostOwnerService postOwnerService,
            PostTagService postTagService,
            PostCollaborationService postCollaborationService) {
        this.postOwnerService = postOwnerService;
        this.postTagService = postTagService;
        this.postCollaborationService = postCollaborationService;
    }

    /** Replaces the content of an owned draft or scheduled post; returns 200 with the post. */
    @Override
    @PutMapping(ApiConstants.Posts.CONTENT)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<PostResponse>> replaceContent(
            @PathVariable("postId") UUID postId,
            @Valid @RequestBody UpdatePostContentRequest request) {
        PostResponse body =
                postOwnerService.replaceContent(SecurityUtils.getCurrentUserId(), postId, request);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Sets or clears the alt text of one media item of an owned post. */
    @Override
    @PatchMapping(ApiConstants.Posts.ALT_TEXT)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<PostResponse>> updateAltText(
            @PathVariable("postId") UUID postId,
            @PathVariable("postMediaId") UUID postMediaId,
            @Valid @RequestBody UpdateAltTextRequest request) {
        PostResponse body =
                postOwnerService.updateAltText(
                        SecurityUtils.getCurrentUserId(), postId, postMediaId, request);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Sets or clears the place of an owned post. */
    @Override
    @PutMapping(ApiConstants.Posts.PLACE)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<PostResponse>> setPlace(
            @PathVariable("postId") UUID postId, @Valid @RequestBody SetPostPlaceRequest request) {
        PostResponse body =
                postOwnerService.setPlace(SecurityUtils.getCurrentUserId(), postId, request);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Replaces the people tagged on an owned post. */
    @Override
    @PutMapping(ApiConstants.Posts.TAGS)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<PostResponse>> replaceTags(
            @PathVariable("postId") UUID postId,
            @Valid @RequestBody ReplacePostTagsRequest request) {
        PostResponse body =
                postOwnerService.replaceTags(SecurityUtils.getCurrentUserId(), postId, request);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Approves a pending tag of the caller; returns 204. */
    @Override
    @PostMapping(ApiConstants.Posts.TAG_APPROVE)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<Void> approveTag(
            @PathVariable("postId") UUID postId, @PathVariable("tagId") UUID tagId) {
        postTagService.approve(SecurityUtils.getCurrentUserId(), postId, tagId);
        return ResponseEntity.noContent().build();
    }

    /** Removes a tag as the post owner or the tagged person; returns 204. */
    @Override
    @DeleteMapping(ApiConstants.Posts.TAG_BY_ID)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<Void> removeTag(
            @PathVariable("postId") UUID postId, @PathVariable("tagId") UUID tagId) {
        postTagService.remove(SecurityUtils.getCurrentUserId(), postId, tagId);
        return ResponseEntity.noContent().build();
    }

    /** Replaces the co-authors invited to an owned post. */
    @Override
    @PutMapping(ApiConstants.Posts.COLLABORATORS)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<PostResponse>> replaceCollaborators(
            @PathVariable("postId") UUID postId,
            @Valid @RequestBody ReplaceCollaboratorsRequest request) {
        PostResponse body =
                postOwnerService.replaceCollaborators(
                        SecurityUtils.getCurrentUserId(), postId, request);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Accepts the invitation of the caller to co-author a published post; returns 204. */
    @Override
    @PostMapping(ApiConstants.Posts.COLLABORATION_ACCEPT)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<Void> acceptCollaboration(@PathVariable("postId") UUID postId) {
        postCollaborationService.accept(SecurityUtils.getCurrentUserId(), postId);
        return ResponseEntity.noContent().build();
    }

    /** Declines the invitation of the caller to co-author a published post; returns 204. */
    @Override
    @PostMapping(ApiConstants.Posts.COLLABORATION_DECLINE)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<Void> declineCollaboration(@PathVariable("postId") UUID postId) {
        postCollaborationService.decline(SecurityUtils.getCurrentUserId(), postId);
        return ResponseEntity.noContent().build();
    }

    /** Ends the co-authorship of the caller on a post; returns 204. */
    @Override
    @DeleteMapping(ApiConstants.Posts.COLLABORATION)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<Void> leaveCollaboration(@PathVariable("postId") UUID postId) {
        postCollaborationService.leave(SecurityUtils.getCurrentUserId(), postId);
        return ResponseEntity.noContent().build();
    }
}
