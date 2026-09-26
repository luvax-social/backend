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
 * PostgreSQL degradation path for the posts-by-hashtag read.
 *
 * <p>A separate bean rather than a private method on {@code PostByHashtagServiceImpl} for one
 * concrete reason: a Resilience4j fallback is invoked outside the {@code @Transactional} boundary
 * of the method it covers, so assembling a response there hits {@code Post.media} with no Hibernate
 * session and throws {@code LazyInitializationException}. Self-invocation would not restore the
 * boundary either, since Spring cannot advise a call that never leaves the object. Crossing a bean
 * boundary is what puts the transaction interceptor back in the path.
 */
@Component
@RequiredArgsConstructor
public class PostByHashtagPostgresReader {

    private final PostRepository postRepository;
    private final PostVisibilityService postVisibilityService;
    private final PostResponseAssembler postResponseAssembler;

    /**
     * Reads one page of published posts carrying the hashtag directly from PostgreSQL.
     *
     * @param viewerId authenticated viewer whose visibility governs the page
     * @param hashtagId hashtag whose posts are listed
     * @param offset zero-based row offset decoded from the caller's cursor
     * @param limit maximum posts on the page, already clamped by the caller
     * @return cursor-paginated visible posts, newest first
     */
    @Transactional(readOnly = true)
    public CursorPageResponse<PostResponse> read(
            UUID viewerId, UUID hashtagId, int offset, int limit) {
        // Over-fetch by one and use its presence as the hasNextPage signal, rather than inferring
        // it from a full page, which is indistinguishable from an exhausted result set.
        List<Post> fetched =
                postRepository.findPublishedByHashtagId(
                        hashtagId, new OffsetPageable(offset, limit + 1));
        boolean hasNextPage = fetched.size() > limit;
        List<Post> page = hasNextPage ? fetched.subList(0, limit) : fetched;
        List<Post> visible =
                page.stream().filter(p -> postVisibilityService.isVisibleTo(viewerId, p)).toList();
        List<PostResponse> content =
                visible.isEmpty() ? List.of() : postResponseAssembler.assemble(viewerId, visible);
        String start = content.isEmpty() ? null : OffsetCursorCodec.encode(offset);
        // Advances by the pre-filter row count, not the visible count, so posts hidden by the
        // visibility chain are never re-fetched on the next page.
        String end = content.isEmpty() ? null : OffsetCursorCodec.encode(offset + page.size());
        return CursorPageResponse.of(content, hasNextPage, start, end, offset > 0);
    }
}
