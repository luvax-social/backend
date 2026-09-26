package com.app.modules.post.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.app.common.ApiConstants;
import com.app.common.base.BaseController;
import com.app.common.enums.ApiSuccessCode;
import com.app.common.response.ApiResponse;
import com.app.common.response.CursorPageResponse;
import com.app.common.security.util.SecurityUtils;
import com.app.modules.post.api.HashtagPostApi;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.service.PostByHashtagService;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;

/** HTTP surface for listing the posts that carry a given hashtag. */
@RestController
@RequiredArgsConstructor
public class HashtagPostController extends BaseController implements HashtagPostApi {

    private final PostByHashtagService postByHashtagService;

    /** Lists published posts carrying the hashtag; 404s when the hashtag is out of circulation. */
    @Override
    @GetMapping(ApiConstants.Hashtags.POSTS)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<PostResponse>>> listPostsByHashtag(
            UUID hashtagId, String cursor, int limit) {
        CursorPageResponse<PostResponse> body =
                postByHashtagService.findPostsByHashtag(
                        SecurityUtils.getCurrentUserId(), hashtagId, cursor, limit);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }
}
