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
 * Elasticsearch path for the posts-by-hashtag read, with the PostgreSQL join as its fallback.
 *
 * <p>This bean holds the {@code elasticsearchSearch} circuit breaker and nothing else, which is the
 * whole reason it is separate from {@code PostByHashtagServiceImpl}. Resilience4j charges every
 * exception thrown inside a breaker-wrapped method to that breaker's failure rate, and no {@code
 * ignoreExceptions} is configured for this instance. Argument validation and the hashtag lifecycle
 * refusal are caller errors, not Elasticsearch outages, so running them inside the breaker would
 * let a client open it by repeatedly requesting a banned hashtag or an out-of-range page - and that
 * breaker is shared with hashtag search and post caption search, so the damage would not stay on
 * this endpoint. Everything that can fail for a reason other than Elasticsearch being unwell is
 * therefore done by the caller, before this method is entered.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostByHashtagSearchReader {

    private final ElasticsearchOperations elasticsearchOperations;
    private final PostRepository postRepository;
    private final PostVisibilityService postVisibilityService;
    private final PostResponseAssembler postResponseAssembler;
    private final PostByHashtagPostgresReader postgresReader;

    /**
     * Reads one page of published posts carrying the hashtag from Elasticsearch.
     *
     * <p>The caller must already have validated the depth and applied the hashtag lifecycle gate.
     *
     * @param viewerId authenticated viewer whose visibility governs the page
     * @param hashtagId hashtag whose posts are listed
     * @param offset zero-based row offset decoded from the caller's cursor
     * @param limit maximum posts on the page, already clamped by the caller
     * @return cursor-paginated visible posts, newest first
     */
    @Transactional(readOnly = true)
    @CircuitBreaker(name = "elasticsearchSearch", fallbackMethod = "readFallback")
    public CursorPageResponse<PostResponse> read(
            UUID viewerId, UUID hashtagId, int offset, int limit) {
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
                                                                                                                "hashtag_ids")
                                                                                                        .value(
                                                                                                                hashtagId
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
                        // filter equally, so a relevance sort would be an arbitrary order that
                        // shifts between segment merges and silently reshuffles pages.
                        .withSort(Sort.by(Sort.Direction.DESC, "createdAt"))
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
        // Over-fetch by one and use its presence as the hasNextPage signal, rather than inferring
        // it from a full page, which is indistinguishable from an exhausted result set on the last
        // page and forces the client into one extra, always-empty request.
        boolean hasNextPage = allIds.size() > limit;
        List<UUID> ids = hasNextPage ? allIds.subList(0, limit) : allIds;
        List<PostResponse> content = hydrateVisible(viewerId, ids);
        String start = content.isEmpty() ? null : OffsetCursorCodec.encode(offset);
        // Advances by the pre-filter hit count, not the visible count, so posts hidden by the
        // visibility chain are never re-fetched on the next page.
        String end = content.isEmpty() ? null : OffsetCursorCodec.encode(offset + ids.size());
        return CursorPageResponse.of(content, hasNextPage, start, end, offset > 0);
    }

    // Resilience4j fallback: invoked when the primary throws OR the circuit is open. Unlike caption
    // search, this read has a real lower tier - post_hashtags is the source of truth for hashtag
    // membership - so it degrades to a PostgreSQL join rather than to an empty page, and the result
    // is complete rather than degraded.
    CursorPageResponse<PostResponse> readFallback(
            UUID viewerId, UUID hashtagId, int offset, int limit, Throwable t) {
        // Only availability failures may degrade to PostgreSQL; programming or data errors must
        // surface to the caller instead of being masked as a successful degraded read.
        if (!isAvailabilityFailure(t)) {
            if (t instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Unexpected posts-by-hashtag failure", t);
        }
        log.warn(
                "Posts-by-hashtag Elasticsearch read unavailable ({}: {}), using PostgreSQL join",
                t.getClass().getSimpleName(),
                t.getMessage(),
                t);
        return postgresReader.read(viewerId, hashtagId, offset, limit);
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
        // Preserve the Elasticsearch ordering, which is the newest-first sort applied above.
        List<Post> ordered = ids.stream().map(byId::get).filter(p -> p != null).toList();
        return ordered.isEmpty() ? List.of() : postResponseAssembler.assemble(viewerId, ordered);
    }

    // Mirrors PostSearchServiceImpl: Spring Data Elasticsearch translates transport failures to
    // DataAccessResourceFailureException, raw client failures surface as IOException in the cause
    // chain, and CallNotPermittedException means the circuit is already open. Any exception from
    // Spring Data Elasticsearch's own package is the server rejecting a request rather than a
    // connectivity problem, but the index is still a rebuildable tier per GLOBAL_RULES.md and must
    // degrade the same way. PostgreSQL failures throw JPA/Hibernate types, not these, so a genuine
    // data-layer bug still surfaces as a real failure.
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
