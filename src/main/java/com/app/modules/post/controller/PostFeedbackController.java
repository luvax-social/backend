package com.app.modules.post.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.app.common.ApiConstants;
import com.app.common.base.BaseController;
import com.app.common.security.util.SecurityUtils;
import com.app.modules.post.api.PostFeedbackApi;
import com.app.modules.post.service.PostFeedbackService;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/** HTTP surface for the authenticated viewer's not-interested feedback on posts. */
@RestController
public class PostFeedbackController extends BaseController implements PostFeedbackApi {

    private final PostFeedbackService postFeedbackService;

    public PostFeedbackController(PostFeedbackService postFeedbackService) {
        this.postFeedbackService = postFeedbackService;
    }

    /** Marks a post as not interesting for the authenticated user; returns 204. */
    @Override
    @PostMapping(ApiConstants.Posts.NOT_INTERESTED)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<Void> markNotInterested(@PathVariable("postId") UUID postId) {
        postFeedbackService.markNotInterested(SecurityUtils.getCurrentUserId(), postId);
        return ResponseEntity.noContent().build();
    }

    /** Removes the authenticated user's not-interested mark on a post; returns 204. */
    @Override
    @DeleteMapping(ApiConstants.Posts.NOT_INTERESTED)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<Void> undoNotInterested(@PathVariable("postId") UUID postId) {
        postFeedbackService.undoNotInterested(SecurityUtils.getCurrentUserId(), postId);
        return ResponseEntity.noContent().build();
    }
}
