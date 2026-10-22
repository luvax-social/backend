package com.app.modules.post.service.impl;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.pagination.OffsetCursorCodec;
import com.app.common.response.CursorPageResponse;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.place.service.PlaceService;
import com.app.modules.post.service.PostByPlaceService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PostByPlaceServiceImpl implements PostByPlaceService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    // Elasticsearch's own default index.max_result_window, shared by both tiers for the reasons
    // given on PostByHashtagServiceImpl.
    private static final int MAX_RESULT_WINDOW = 10_000;

    private final PlaceService placeService;
    private final PostByPlaceSearchReader searchReader;

    @Override
    public CursorPageResponse<PostResponse> findPostsByPlace(
            UUID viewerId, UUID placeId, String cursor, int size) {
        int offset = OffsetCursorCodec.decode(cursor);
        int limit = normalizeLimit(size);
        // Both guards run outside the circuit breaker on purpose: a caller error raised inside it
        // would be charged to a breaker shared with hashtag search and caption search.
        requireServableDepth(offset, limit);
        placeService.getUsable(placeId);
        return searchReader.read(viewerId, placeId, offset, limit);
    }

    private static void requireServableDepth(int offset, int limit) {
        int requestedWindow = offset + limit + 1;
        if (requestedWindow > MAX_RESULT_WINDOW) {
            throw new AppException(
                    ApiErrorCode.PAGINATION_DEPTH_EXCEEDED,
                    Map.of(
                            "requestedWindow", requestedWindow,
                            "maxResultWindow", MAX_RESULT_WINDOW));
        }
    }

    private static int normalizeLimit(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
