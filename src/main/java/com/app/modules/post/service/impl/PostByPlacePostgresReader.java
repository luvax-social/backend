package com.app.modules.post.service.impl;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.pagination.OffsetCursorCodec;
import com.app.common.pagination.OffsetPageable;
import com.app.common.response.CursorPageResponse;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.service.PostVisibilityService;

import lombok.RequiredArgsConstructor;

/**
 * PostgreSQL degradation path for the posts-by-place read.
 *
 * <p>A separate bean for the reason given on {@code PostByHashtagPostgresReader}: a Resilience4j
 * fallback runs outside the transaction of the method it covers, and only crossing a bean boundary
 * puts the transaction interceptor back in the path.
 */
@Component
@RequiredArgsConstructor
public class PostByPlacePostgresReader {

    private final PostRepository postRepository;
    private final PostVisibilityService postVisibilityService;
    private final PostResponseAssembler postResponseAssembler;

    /**
     * Reads one page of published posts at the place directly from PostgreSQL.
     *
     * @param viewerId authenticated viewer whose visibility governs the page
     * @param placeId the place
     * @param offset zero-based row offset decoded from the caller's cursor
     * @param limit maximum posts on the page, already clamped by the caller
     * @return cursor-paginated visible posts, newest first
     */
    @Transactional(readOnly = true)
    public CursorPageResponse<PostResponse> read(
            UUID viewerId, UUID placeId, int offset, int limit) {
        // Over-fetch by one and use its presence as the hasNextPage signal.
        List<Post> fetched =
                postRepository.findPublishedByPlaceId(
                        placeId, new OffsetPageable(offset, limit + 1));
        boolean hasNextPage = fetched.size() > limit;
        List<Post> page = hasNextPage ? fetched.subList(0, limit) : fetched;
        List<Post> visible =
                page.stream().filter(p -> postVisibilityService.isVisibleTo(viewerId, p)).toList();
        List<PostResponse> content =
                visible.isEmpty() ? List.of() : postResponseAssembler.assemble(viewerId, visible);
        String start = content.isEmpty() ? null : OffsetCursorCodec.encode(offset);
        // Advances by the pre-filter row count, so hidden posts are never re-fetched.
        String end = content.isEmpty() ? null : OffsetCursorCodec.encode(offset + page.size());
        return CursorPageResponse.of(content, hasNextPage, start, end, offset > 0);
    }
}
