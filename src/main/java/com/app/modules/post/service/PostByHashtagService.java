package com.app.modules.post.service;

import java.util.UUID;

import com.app.common.response.CursorPageResponse;
import com.app.modules.post.dto.response.PostResponse;

/**
 * Published posts carrying a given hashtag.
 *
 * <p>Deliberately separate from {@link PostSearchService}. Caption full-text search and hashtag
 * membership are different operations against different fields with different failure contracts:
 * caption search degrades to an empty page because it has no lower tier, whereas this read degrades
 * to a PostgreSQL join through {@code post_hashtags}, which is the source of truth for hashtag
 * membership and therefore returns real, complete results rather than nothing.
 */
public interface PostByHashtagService {

    /**
     * Lists published posts tagged with the hashtag, newest first.
     *
     * <p>Refuses the whole call when the hashtag is banned or deleted, rather than answering an
     * empty page: an empty page cannot be told apart from a hashtag that has no posts yet, and the
     * two states have to read differently to a user.
     *
     * <p>Elasticsearch is the primary path, with a PostgreSQL join as the fallback when the {@code
     * elasticsearchSearch} circuit breaker is open. Both tiers page on the same offset cursor, so a
     * cursor issued by one remains valid if the next request is served by the other. Hits are
     * re-checked for published status against PostgreSQL and filtered through the shared post
     * visibility chain, so a page may hold fewer items than requested.
     *
     * <p>Pagination depth is bounded. A request whose window would exceed Elasticsearch's {@code
     * index.max_result_window} is refused with its own error code rather than left to fail inside
     * the search tier, where the resulting exception is indistinguishable from an outage and would
     * be charged to a circuit breaker shared with two other search surfaces.
     *
     * @param viewerId authenticated viewer, whose blocks and private-account access govern what is
     *     returned
     * @param hashtagId hashtag whose posts are listed
     * @param cursor opaque base64 offset cursor; null or blank for the first page
     * @param size maximum results per page
     * @return cursor-paginated visible posts, newest first
     * @throws com.app.common.exception.AppException {@code HASHTAG_NOT_FOUND} when no row carries
     *     the id, {@code HASHTAG_UNAVAILABLE} when the hashtag is banned or deleted, {@code
     *     PAGINATION_DEPTH_EXCEEDED} when the requested window is deeper than the endpoint serves,
     *     or {@code INVALID_CURSOR} when the cursor is malformed
     */
    CursorPageResponse<PostResponse> findPostsByHashtag(
            UUID viewerId, UUID hashtagId, String cursor, int size);
}
