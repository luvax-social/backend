package com.app.modules.comment.controller;

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
import org.springframework.web.bind.annotation.RequestHeader;
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
import com.app.modules.comment.api.CommentApi;
import com.app.modules.comment.dto.request.CreateCommentRequest;
import com.app.modules.comment.dto.request.EditCommentRequest;
import com.app.modules.comment.dto.response.CommentContextResponse;
import com.app.modules.comment.dto.response.CommentDeletionScopeResponse;
import com.app.modules.comment.dto.response.CommentResponse;
import com.app.modules.comment.service.CommentService;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/** HTTP surface for comment creation, nested replies, likes, edit, and soft delete. */
@RestController
public class CommentController extends BaseController implements CommentApi {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    /** Creates a comment or reply for the authenticated user; returns 201 with the comment. */
    @Override
    @PostMapping(ApiConstants.Posts.ROOT + ApiConstants.Posts.COMMENTS)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CommentResponse>> createComment(
            @PathVariable("postId") UUID postId,
            @Valid @RequestBody CreateCommentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (request.postId() != null && !request.postId().equals(postId)) {
            throw new AppException(
                    ApiErrorCode.BAD_REQUEST, "Body post id does not match the path post id");
        }
        CreateCommentRequest resolved =
                new CreateCommentRequest(postId, request.parentId(), request.content());
        CommentResponse body =
                commentService.createComment(
                        SecurityUtils.getCurrentUserId(), resolved, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(ApiSuccessCode.CREATED, body));
    }

    /** Lists approved top-level comments for a post. */
    @Override
    @GetMapping(ApiConstants.Posts.ROOT + ApiConstants.Posts.COMMENTS)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<CommentResponse>>> listTopLevelComments(
            @PathVariable("postId") UUID postId,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        CursorPageResponse<CommentResponse> body =
                commentService.listTopLevelComments(
                        SecurityUtils.getCurrentUserId(), postId, sort, cursor, limit);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Lists approved direct replies to a comment. */
    @Override
    @GetMapping(ApiConstants.Comments.ROOT + ApiConstants.Comments.REPLIES)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<CommentResponse>>> listReplies(
            @PathVariable("commentId") UUID commentId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        CursorPageResponse<CommentResponse> body =
                commentService.listReplies(
                        SecurityUtils.getCurrentUserId(), commentId, cursor, limit);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Returns a comment with its ancestors, top-level first; requires authentication. */
    @Override
    @GetMapping(ApiConstants.Comments.ROOT + ApiConstants.Comments.CONTEXT)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CommentContextResponse>> getContext(
            @PathVariable("commentId") UUID commentId) {
        CommentContextResponse body =
                commentService.getContext(SecurityUtils.getCurrentUserId(), commentId);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Edits an owned comment; requires authentication as the owner. */
    @Override
    @PatchMapping(ApiConstants.Comments.ROOT + ApiConstants.Comments.BY_ID)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CommentResponse>> editComment(
            @PathVariable("commentId") UUID commentId,
            @Valid @RequestBody EditCommentRequest request) {
        CommentResponse body =
                commentService.editComment(SecurityUtils.getCurrentUserId(), commentId, request);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Reports how many comments deleting this comment would remove; owner or admin only. */
    @Override
    @GetMapping(ApiConstants.Comments.ROOT + ApiConstants.Comments.DELETION_SCOPE)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CommentDeletionScopeResponse>> getDeletionScope(
            @PathVariable("commentId") UUID commentId) {
        CommentDeletionScopeResponse body =
                commentService.getDeletionScope(SecurityUtils.getCurrentUserId(), commentId);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Soft-deletes a comment and its subtree; owner or admin only. */
    @Override
    @DeleteMapping(ApiConstants.Comments.ROOT + ApiConstants.Comments.BY_ID)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CommentDeletionScopeResponse>> deleteComment(
            @PathVariable("commentId") UUID commentId) {
        CommentDeletionScopeResponse body =
                commentService.deleteComment(SecurityUtils.getCurrentUserId(), commentId);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Likes a comment for the authenticated user. */
    @Override
    @PostMapping(ApiConstants.Comments.ROOT + ApiConstants.Comments.LIKE)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<Void>> likeComment(
            @PathVariable("commentId") UUID commentId) {
        commentService.likeComment(SecurityUtils.getCurrentUserId(), commentId);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK));
    }

    /** Removes the authenticated user's like from a comment. */
    @Override
    @DeleteMapping(ApiConstants.Comments.ROOT + ApiConstants.Comments.LIKE)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<Void>> unlikeComment(
            @PathVariable("commentId") UUID commentId) {
        commentService.unlikeComment(SecurityUtils.getCurrentUserId(), commentId);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK));
    }
}
