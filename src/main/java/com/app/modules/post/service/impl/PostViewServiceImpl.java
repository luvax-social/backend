package com.app.modules.post.service.impl;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.modules.post.dto.response.PostViewResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.service.PostViewService;
import com.app.modules.post.service.PostVisibilityService;

@Service
public class PostViewServiceImpl implements PostViewService {

    private final PostRepository postRepository;
    private final PostVisibilityService postVisibilityService;
    private final OutboxService outboxService;

    public PostViewServiceImpl(
            PostRepository postRepository,
            PostVisibilityService postVisibilityService,
            OutboxService outboxService) {
        this.postRepository = postRepository;
        this.postVisibilityService = postVisibilityService;
        this.outboxService = outboxService;
    }

    @Override
    @Transactional
    public PostViewResponse recordView(UUID viewerId, UUID postId) {
        Post post =
                postRepository
                        .findByIdAndDeletedAtIsNull(postId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.POST_NOT_FOUND));
        // Unpublished posts surface as not-found to avoid leaking their existence, matching every
        // other single-post lookup in this module.
        if (post.getStatus() != PostStatus.PUBLISHED) {
            throw new AppException(ApiErrorCode.POST_NOT_FOUND);
        }
        if (!postVisibilityService.isVisibleTo(viewerId, post)) {
            throw new AppException(ApiErrorCode.POST_NOT_FOUND);
        }
        if (viewerId.equals(post.getUserId())) {
            return new PostViewResponse(postId, false);
        }
        // Deliberately distinct from the batched impression endpoint under the recommendations
        // root and must not be merged with it: this records one deliberate open of a single post,
        // that one ingests batched passive viewport impressions carrying dwell. Neither writes
        // posts.view_count, which a background job maintains and application code never touches.
        outboxService.enqueue(
                PostEventTypes.POST_VIEWED_V1,
                PostEventTypes.POST_VIEWED_V1,
                "post",
                postId,
                viewerId,
                Map.of(
                        "postId", postId.toString(),
                        "postOwnerId", post.getUserId().toString(),
                        "userId", viewerId.toString()));
        return new PostViewResponse(postId, true);
    }
}
