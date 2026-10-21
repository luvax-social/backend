package com.app.modules.post.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.modules.post.entity.Post;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.repository.PostNotInterestedRepository;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.service.PostVisibilityService;

@ExtendWith(MockitoExtension.class)
class PostFeedbackServiceImplTest {

    @Mock private PostRepository postRepository;
    @Mock private PostNotInterestedRepository notInterestedRepository;
    @Mock private PostVisibilityService postVisibilityService;
    @Mock private OutboxService outboxService;

    private PostFeedbackServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final UUID authorId = UUID.randomUUID();
    private final UUID postId = UUID.randomUUID();

    private Post publishedPost;

    @BeforeEach
    void setUp() {
        service =
                new PostFeedbackServiceImpl(
                        postRepository,
                        notInterestedRepository,
                        postVisibilityService,
                        outboxService);
        publishedPost =
                Post.builder().id(postId).userId(authorId).status(PostStatus.PUBLISHED).build();
        lenient()
                .when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(publishedPost));
        lenient().when(postVisibilityService.isVisibleTo(userId, publishedPost)).thenReturn(true);
    }

    @Test
    void markNotInterested_firstCall_storesRowAndEnqueuesEventWithPostAndAuthor() {
        when(notInterestedRepository.insertIfAbsent(userId, postId)).thenReturn(1);

        service.markNotInterested(userId, postId);

        verify(outboxService)
                .enqueue(
                        eq(PostEventTypes.POST_NOT_INTERESTED_V1),
                        eq(PostEventTypes.POST_NOT_INTERESTED_V1),
                        eq("post"),
                        eq(postId),
                        eq(userId),
                        eq(Map.of("postId", postId.toString(), "authorId", authorId.toString())));
    }

    @Test
    void markNotInterested_alreadyMarked_isNoOpWithoutSecondEvent() {
        when(notInterestedRepository.insertIfAbsent(userId, postId)).thenReturn(0);

        service.markNotInterested(userId, postId);

        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), any());
    }

    @Test
    void markNotInterested_ownPost_throwsPostFeedbackSelf() {
        Post own = Post.builder().id(postId).userId(userId).status(PostStatus.PUBLISHED).build();
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(own));

        assertThatThrownBy(() -> service.markNotInterested(userId, postId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_FEEDBACK_SELF);
        verify(notInterestedRepository, never()).insertIfAbsent(any(), any());
    }

    @Test
    void markNotInterested_missingPost_throwsPostNotFound() {
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markNotInterested(userId, postId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_NOT_FOUND);
        verify(notInterestedRepository, never()).insertIfAbsent(any(), any());
    }

    @Test
    void markNotInterested_postHiddenByAccountState_throwsPostNotFound() {
        when(postVisibilityService.isVisibleTo(userId, publishedPost)).thenReturn(false);

        assertThatThrownBy(() -> service.markNotInterested(userId, postId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_NOT_FOUND);
        verify(notInterestedRepository, never()).insertIfAbsent(any(), any());
    }

    @Test
    void markNotInterested_unpublishedPostOfAnotherUser_throwsPostNotFound() {
        Post draft = Post.builder().id(postId).userId(authorId).status(PostStatus.DRAFT).build();
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.markNotInterested(userId, postId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_NOT_FOUND);
        verify(notInterestedRepository, never()).insertIfAbsent(any(), any());
    }

    @Test
    void undoNotInterested_rowExisted_enqueuesUndoneEventWithPostOnly() {
        when(notInterestedRepository.deleteByUserAndPost(userId, postId)).thenReturn(1);

        service.undoNotInterested(userId, postId);

        verify(outboxService)
                .enqueue(
                        eq(PostEventTypes.POST_NOT_INTERESTED_UNDONE_V1),
                        eq(PostEventTypes.POST_NOT_INTERESTED_UNDONE_V1),
                        eq("post"),
                        eq(postId),
                        eq(userId),
                        eq(Map.of("postId", postId.toString())));
    }

    @Test
    void findNotInterested_returnsTheMarkedSubsetFromOneRead() {
        UUID marked = UUID.randomUUID();
        UUID unmarked = UUID.randomUUID();
        when(notInterestedRepository.findMarkedPostIds(userId, Set.of(marked, unmarked)))
                .thenReturn(List.of(marked));

        Set<UUID> result = service.findNotInterested(userId, Set.of(marked, unmarked));

        assertThat(result).containsExactly(marked);
        verify(notInterestedRepository).findMarkedPostIds(userId, Set.of(marked, unmarked));
    }

    @Test
    void findNotInterested_noCandidates_readsNothing() {
        assertThat(service.findNotInterested(userId, List.of())).isEmpty();
        assertThat(service.findNotInterested(userId, null)).isEmpty();

        verifyNoInteractions(notInterestedRepository);
    }

    @Test
    void undoNotInterested_noRow_isIdempotentWithoutEvent() {
        when(notInterestedRepository.deleteByUserAndPost(userId, postId)).thenReturn(0);

        service.undoNotInterested(userId, postId);

        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), any());
    }
}
