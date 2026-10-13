package com.app.modules.post.service.impl;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.modules.post.entity.Post;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.repository.PostNotInterestedRepository;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.service.PostFeedbackService;
import com.app.modules.post.service.PostVisibilityService;

@Service
public class PostFeedbackServiceImpl implements PostFeedbackService {

    private static final String AGGREGATE_TYPE_POST = "post";

    private final PostRepository postRepository;
    private final PostNotInterestedRepository notInterestedRepository;
    private final PostVisibilityService postVisibilityService;
    private final OutboxService outboxService;

    public PostFeedbackServiceImpl(
            PostRepository postRepository,
            PostNotInterestedRepository notInterestedRepository,
            PostVisibilityService postVisibilityService,
            OutboxService outboxService) {
        this.postRepository = postRepository;
        this.notInterestedRepository = notInterestedRepository;
        this.postVisibilityService = postVisibilityService;
        this.outboxService = outboxService;
    }

    @Override
    @Transactional
    public void markNotInterested(UUID userId, UUID postId) {
        Post post =
                postRepository
                        .findByIdAndDeletedAtIsNull(postId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.POST_NOT_FOUND));
        if (userId.equals(post.getUserId())) {
            throw new AppException(ApiErrorCode.POST_FEEDBACK_SELF);
        }
        // An unpublished post of another user, or one hidden by a block, a private owner or a
        // deleted owner, answers as not found so the endpoint is no oracle for any of them.
        if (post.getStatus() != PostStatus.PUBLISHED
                || !postVisibilityService.isVisibleTo(userId, post)) {
            throw new AppException(ApiErrorCode.POST_NOT_FOUND);
        }
        // The insert reports whether it created the row, so a repeat or a concurrent duplicate
        // enqueues nothing and the recommender never receives the same feedback twice.
        if (notInterestedRepository.insertIfAbsent(userId, postId) == 0) {
            return;
        }
        outboxService.enqueue(
                PostEventTypes.POST_NOT_INTERESTED_V1,
                PostEventTypes.POST_NOT_INTERESTED_V1,
                AGGREGATE_TYPE_POST,
                postId,
                userId,
                Map.of("postId", postId.toString(), "authorId", post.getUserId().toString()));
    }

    @Override
    @Transactional
    public void undoNotInterested(UUID userId, UUID postId) {
        if (notInterestedRepository.deleteByUserAndPost(userId, postId) == 0) {
            return;
        }
        outboxService.enqueue(
                PostEventTypes.POST_NOT_INTERESTED_UNDONE_V1,
                PostEventTypes.POST_NOT_INTERESTED_UNDONE_V1,
                AGGREGATE_TYPE_POST,
                postId,
                userId,
                Map.of("postId", postId.toString()));
    }
}
