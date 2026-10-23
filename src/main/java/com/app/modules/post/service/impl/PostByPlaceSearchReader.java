package com.app.modules.post.service.impl;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.pagination.OffsetCursorCodec;
import com.app.common.pagination.OffsetPageable;
import com.app.common.response.CursorPageResponse;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.search.PostDocument;
import com.app.modules.post.service.PostVisibilityService;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Elasticsearch path for the posts-by-place read, with PostgreSQL as its fallback.
 *
 * <p>Holds the {@code elasticsearchSearch} circuit breaker and nothing else, for the reason given
 * on {@code PostByHashtagSearchReader}: everything that can fail for a reason other than
 * Elasticsearch being unwell is done by the caller before this method is entered.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostByPlaceSearchReader {

    private final ElasticsearchOperations elasticsearchOperations;
    private final PostRepository postRepository;
    private final PostVisibilityService postVisibilityService;
    private final PostResponseAssembler postResponseAssembler;
    private final PostByPlacePostgresReader postgresReader;

    /**
     * Reads one page of published posts at the place from Elasticsearch.
     *
     * <p>The caller must already have validated the depth and applied the place lifecycle gate.
     *
     * @param viewerId authenticated viewer whose visibility governs the page
     * @param placeId the place
     * @param offset zero-based row offset decoded from the caller's cursor
     * @param limit maximum posts on the page, already clamped by the caller
     * @return cursor-paginated visible posts, newest first
     */
    @Transactional(readOnly = true)
    @CircuitBreaker(name = "elasticsearchSearch", fallbackMethod = "readFallback")
    public CursorPageResponse<PostResponse> read(
            UUID viewerId, UUID placeId, int offset, int limit) {
        NativeQuery nativeQuery =
                NativeQuery.builder()
                        .withQuery(
                                q ->
                                        q.bool(
                                                b ->
                                                        b.filter(
                                                                        f ->
                                                                                f.term(
                                                                                        t ->
                                                                                                t.field(
                                                                                                                "place_id")
                                                                                                        .value(
                                                                                                                placeId
                                                                                                                        .toString())))
                                                                .filter(
                                                                        f ->
                                                                                f.term(
                                                                                        t ->
                                                                                                t.field(
                                                                                                                "status")
                                                                                                        .value(
                                                                                                                "published")))))
                        // Newest first rather than relevance: every document matches the term
                        // filter equally, so a relevance sort would reshuffle pages between
                        // segment merges.
                        .withSort(Sort.by(Sort.Direction.DESC, "publishedAt"))
                        .withPageable(new OffsetPageable(offset, limit + 1))
                        .build();
        SearchHits<PostDocument> hits =
                elasticsearchOperations.search(nativeQuery, PostDocument.class);
        List<UUID> allIds =
                hits.getSearchHits().stream()
                        .map(SearchHit::getContent)
                        .map(PostDocument::getId)
                        .map(UUID::fromString)
                        .toList();
        boolean hasNextPage = allIds.size() > limit;
        List<UUID> ids = hasNextPage ? allIds.subList(0, limit) : allIds;
        List<PostResponse> content = hydrateVisible(viewerId, ids);
        String start = content.isEmpty() ? null : OffsetCursorCodec.encode(offset);
        // Advances by the pre-filter hit count, so hidden posts are never re-fetched.
        String end = content.isEmpty() ? null : OffsetCursorCodec.encode(offset + ids.size());
        return CursorPageResponse.of(content, hasNextPage, start, end, offset > 0);
    }

    // Resilience4j fallback: invoked when the primary throws or the circuit is open. posts.place_id
    // is the source of truth for the place, so the lower tier is complete rather than degraded.
    CursorPageResponse<PostResponse> readFallback(
            UUID viewerId, UUID placeId, int offset, int limit, Throwable t) {
        // Only availability failures may degrade to PostgreSQL; programming or data errors must
        // surface to the caller instead of being masked as a successful degraded read.
        if (!isAvailabilityFailure(t)) {
            if (t instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Unexpected posts-by-place failure", t);
        }
        log.warn(
                "Posts-by-place Elasticsearch read unavailable ({}: {}), using PostgreSQL",
                t.getClass().getSimpleName(),
                t.getMessage(),
                t);
        return postgresReader.read(viewerId, placeId, offset, limit);
    }

    private List<PostResponse> hydrateVisible(UUID viewerId, List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, Post> byId =
                postRepository.findAllById(ids).stream()
                        // Stale-index guard: only posts still published in PostgreSQL qualify.
                        .filter(p -> p.getStatus() == PostStatus.PUBLISHED)
                        .filter(p -> p.getDeletedAt() == null)
                        .filter(p -> postVisibilityService.isVisibleTo(viewerId, p))
                        .collect(Collectors.toMap(Post::getId, Function.identity()));
        List<Post> ordered = ids.stream().map(byId::get).filter(p -> p != null).toList();
        return ordered.isEmpty() ? List.of() : postResponseAssembler.assemble(viewerId, ordered);
    }

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
}
