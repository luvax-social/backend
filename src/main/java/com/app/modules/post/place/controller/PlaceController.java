package com.app.modules.post.place.controller;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import com.app.modules.post.place.api.PlaceApi;
import com.app.modules.post.place.dto.request.CreatePlaceRequest;
import com.app.modules.post.place.dto.response.PlaceResponse;
import com.app.modules.post.place.service.PlaceSearchService;
import com.app.modules.post.place.service.PlaceService;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/** HTTP surface for creating, searching and reading places. */
@RestController
public class PlaceController extends BaseController implements PlaceApi {

    private final PlaceService placeService;
    private final PlaceSearchService placeSearchService;

    public PlaceController(PlaceService placeService, PlaceSearchService placeSearchService) {
        this.placeService = placeService;
        this.placeSearchService = placeSearchService;
    }

    /** Creates a place by name, or returns the one that already has it; 201 with the place. */
    @Override
    @PostMapping
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<PlaceResponse>> createPlace(
            @Valid @RequestBody CreatePlaceRequest request) {
        PlaceResponse body =
                placeService.findOrCreate(SecurityUtils.getCurrentUserId(), request.name());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(ApiSuccessCode.CREATED, body));
    }

    /** Searches usable places by name; degrades to trigram search when Elasticsearch is down. */
    @Override
    @GetMapping(ApiConstants.Places.SEARCH)
    @StrictQueryParameters
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<PlaceResponse>>> searchPlaces(
            @RequestParam("q") String q,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK, placeSearchService.search(q, cursor, limit)));
    }

    /** Returns an active place; 404s when it is banned or deleted. */
    @Override
    @GetMapping(ApiConstants.Places.BY_ID)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<PlaceResponse>> getPlace(
            @PathVariable("placeId") UUID placeId) {
        return ResponseEntity.ok(
                ApiResponse.success(ApiSuccessCode.OK, placeService.getUsable(placeId)));
    }
}
