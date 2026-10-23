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
import com.app.modules.post.api.PlacePostApi;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.service.PostByPlaceService;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;

/** HTTP surface for listing the posts tagged at a given place. */
@RestController
@RequiredArgsConstructor
public class PlacePostController extends BaseController implements PlacePostApi {

    private final PostByPlaceService postByPlaceService;

    /** Lists published posts tagged at the place; 404s when the place is out of circulation. */
    @Override
    @GetMapping(ApiConstants.Places.POSTS)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<PostResponse>>> listPostsByPlace(
            UUID placeId, String cursor, int limit) {
        CursorPageResponse<PostResponse> body =
                postByPlaceService.findPostsByPlace(
                        SecurityUtils.getCurrentUserId(), placeId, cursor, limit);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }
}
