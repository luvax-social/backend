package com.app.modules.post.place.service.impl;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import com.app.common.pagination.OffsetCursorCodec;
import com.app.common.pagination.OffsetPageable;
import com.app.common.response.CursorPageResponse;
import com.app.common.text.TextMatchNormalizer;
import com.app.modules.post.place.dto.response.PlaceResponse;
import com.app.modules.post.place.repository.PlaceRepository;
import com.app.modules.post.place.search.PlaceDocument;
import com.app.modules.post.place.service.PlaceSearchService;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class PlaceSearchServiceImpl implements PlaceSearchService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final ElasticsearchOperations elasticsearchOperations;
    private final PlaceRepository placeRepository;

    public PlaceSearchServiceImpl(
            ElasticsearchOperations elasticsearchOperations, PlaceRepository placeRepository) {
        this.elasticsearchOperations = elasticsearchOperations;
        this.placeRepository = placeRepository;
    }

    @Override
    @CircuitBreaker(name = "elasticsearchSearch", fallbackMethod = "searchFallback")
    public CursorPageResponse<PlaceResponse> search(String query, String cursor, int limit) {
        String normalized = TextMatchNormalizer.normalizeName(query);
        int offset = OffsetCursorCodec.decode(cursor);
        int effectiveLimit = clamp(limit);
        if (normalized.isEmpty()) {
            return toPage(List.of(), offset, false);
        }
        // Page by the exact cursor offset: PageRequest derives from as page * size, which
        // truncates any offset not divisible by the page size and skips or repeats results.
        NativeQuery nativeQuery =
                NativeQuery.builder()
                        .withQuery(
                                q ->
                                        q.match(
                                                m ->
                                                        m.field("normalized_name.ngram")
                                                                .query(normalized)))
                        .withPageable(new OffsetPageable(offset, effectiveLimit + 1))
                        .build();
        SearchHits<PlaceDocument> hits =
                elasticsearchOperations.search(nativeQuery, PlaceDocument.class);
        List<PlaceResponse> all =
                hits.getSearchHits().stream()
                        .map(SearchHit::getContent)
                        .map(
                                d ->
                                        new PlaceResponse(
                                                UUID.fromString(d.getId()),
                                                d.getName(),
                                                d.getPostCount()))
                        .toList();
        // Over-fetch by one and use its presence as the hasNextPage signal.
        boolean hasNextPage = all.size() > effectiveLimit;
        return toPage(hasNextPage ? all.subList(0, effectiveLimit) : all, offset, hasNextPage);
    }

    // Resilience4j fallback: invoked when the primary throws OR the circuit is open. Falls back to
    // the PostgreSQL pg_trgm index so search degrades gracefully when Elasticsearch is down.
    CursorPageResponse<PlaceResponse> searchFallback(
            String query, String cursor, int limit, Throwable t) {
        // Only availability failures may degrade; programming or data errors must surface.
        if (!isAvailabilityFailure(t)) {
            if (t instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Unexpected place search failure", t);
        }
        log.warn(
                "Place Elasticsearch search unavailable ({}: {}), using pg_trgm fallback",
                t.getClass().getSimpleName(),
                t.getMessage());
        String normalized = TextMatchNormalizer.normalizeName(query);
        int offset = OffsetCursorCodec.decode(cursor);
        int effectiveLimit = clamp(limit);
        if (normalized.isEmpty()) {
            return toPage(List.of(), offset, false);
        }
        List<PlaceResponse> all =
                placeRepository.searchByNameTrgm(normalized, effectiveLimit + 1, offset).stream()
                        .map(p -> new PlaceResponse(p.getId(), p.getName(), p.getPostCount()))
                        .toList();
        boolean hasNextPage = all.size() > effectiveLimit;
        return toPage(hasNextPage ? all.subList(0, effectiveLimit) : all, offset, hasNextPage);
    }

    private static int clamp(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    // Spring Data Elasticsearch translates transport/connection failures to
    // DataAccessResourceFailureException; raw client failures surface as IOException in the cause
    // chain. CallNotPermittedException means the circuit is already open. Any exception type from
    // Spring Data Elasticsearch's own package is the server rejecting a request, not a connectivity
    // problem, but the search index is still a rebuildable tier and must degrade the same way.
    private static boolean isAvailabilityFailure(Throwable t) {
        if (t instanceof CallNotPermittedException) {
            return true;
        }
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException
                    || cause instanceof IOException
                    || cause.getClass()
                            .getName()
                            .startsWith("org.springframework.data.elasticsearch.")) {
                return true;
            }
        }
        return false;
    }

    private static CursorPageResponse<PlaceResponse> toPage(
            List<PlaceResponse> content, int offset, boolean hasNextPage) {
        String start = content.isEmpty() ? null : OffsetCursorCodec.encode(offset);
        String end = content.isEmpty() ? null : OffsetCursorCodec.encode(offset + content.size());
        return CursorPageResponse.of(content, hasNextPage, start, end, offset > 0);
    }
}
