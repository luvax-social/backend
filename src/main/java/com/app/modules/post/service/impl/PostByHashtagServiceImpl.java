package com.app.modules.post.service.impl;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.pagination.OffsetCursorCodec;
import com.app.common.response.CursorPageResponse;
import com.app.modules.hashtag.service.HashtagLookupService;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.service.PostByHashtagService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PostByHashtagServiceImpl implements PostByHashtagService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * Deepest window either storage tier will be asked for, as {@code offset + size}.
     *
     * <p>Set to Elasticsearch's own default {@code index.max_result_window}, which neither index
     * settings file overrides. A lower bound would refuse pages Elasticsearch would happily serve;
     * a higher one would hand it a window it rejects outright. The PostgreSQL fallback wants the
     * same bound for a different reason - {@code OFFSET 10000} is a linear skip of ten thousand
     * joined rows - so one number covers both tiers.
     */
    private static final int MAX_RESULT_WINDOW = 10_000;

    private final HashtagLookupService hashtagLookupService;
    private final PostByHashtagSearchReader searchReader;

    @Override
    public CursorPageResponse<PostResponse> findPostsByHashtag(
            UUID viewerId, UUID hashtagId, String cursor, int size) {
        int offset = OffsetCursorCodec.decode(cursor);
        int limit = normalizeLimit(size);
        // Both guards below run outside the circuit breaker deliberately. Resilience4j charges
        // every exception thrown inside a breaker-wrapped method to its failure rate, and the
        // elasticsearchSearch instance configures no ignoreExceptions, so a caller error raised in
        // there would push a breaker shared with hashtag search and post caption search toward
        // open. Neither of these is an Elasticsearch outage, so neither may be charged as one.
        requireServableDepth(offset, limit);
        hashtagLookupService.getById(hashtagId);
        return searchReader.read(viewerId, hashtagId, offset, limit);
    }

    // The read over-fetches one row past the page to derive hasNextPage, so the window actually
    // requested is offset + limit + 1; bounding the request rather than the offset alone is what
    // keeps a large limit at a legal offset from producing an illegal window.
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

    // Clamp the page size so an oversized request cannot force a mass hydration or exceed the
    // Elasticsearch result-window ceiling, matching the bound applied by the other list endpoints.
    private static int normalizeLimit(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
