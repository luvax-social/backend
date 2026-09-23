package com.app.modules.post.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.modules.post.dto.response.LikeActionResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostLike;
import com.app.modules.post.entity.PostLikeId;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.repository.PostLikeRepository;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.service.PostVisibilityService;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.service.UserSummaryService;

@ExtendWith(MockitoExtension.class)
class PostLikeServiceImplTest {

    @Mock private PostRepository postRepository;
    @Mock private PostLikeRepository postLikeRepository;
    @Mock private PostVisibilityService postVisibilityService;
    @Mock private PostResponseAssembler postResponseAssembler;
    @Mock private UserSummaryService userSummaryService;
    @Mock private SocialService socialService;
    @Mock private OutboxService outboxService;

    private PostLikeServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final UUID postId = UUID.randomUUID();
    private final PostLikeId likeId = new PostLikeId(userId, postId);

    private Post publishedPost;

    @BeforeEach
    void setUp() {
        service =
                new PostLikeServiceImpl(
                        postRepository,
                        postLikeRepository,
                        postVisibilityService,
                        postResponseAssembler,
                        userSummaryService,
                        socialService,
                        outboxService);
        publishedPost =
                Post.builder().id(postId).userId(ownerId).status(PostStatus.PUBLISHED).build();
        lenient()
                .when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(publishedPost));
        lenient().when(postVisibilityService.isVisibleTo(userId, publishedPost)).thenReturn(true);
    }

    @Test
    void likePost_alreadyLiked_throwsPostAlreadyLiked() {
        when(postLikeRepository.existsById(likeId)).thenReturn(true);

        assertThatThrownBy(() -> service.likePost(userId, postId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_ALREADY_LIKED);
        verify(postLikeRepository, never()).saveAndFlush(any());
        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), any());
    }

    @Test
    void likePost_firstLike_enqueuesOutboxEventWithPostAndOwnerIds() {
        when(postLikeRepository.existsById(likeId)).thenReturn(false);
        when(postLikeRepository.saveAndFlush(any(PostLike.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(postRepository.findLikeCount(postId)).thenReturn(1);

        service.likePost(userId, postId);

        verify(outboxService)
                .enqueue(
                        eq(PostEventTypes.POST_LIKED_V1),
                        eq(PostEventTypes.POST_LIKED_V1),
                        eq("post"),
                        eq(postId),
                        eq(userId),
                        eq(
                                Map.of(
                                        "postId", postId.toString(),
                                        "postOwnerId", ownerId.toString(),
                                        "userId", userId.toString())));
    }

    @Test
    void likePost_concurrentDuplicateInsert_doesNotEnqueue() {
        when(postLikeRepository.existsById(likeId)).thenReturn(false);
        when(postLikeRepository.saveAndFlush(any(PostLike.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.likePost(userId, postId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_ALREADY_LIKED);
        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), any());
    }

    @Test
    void likePost_firstLike_savesAndReturnsFreshCount() {
        when(postLikeRepository.existsById(likeId)).thenReturn(false);
        when(postLikeRepository.saveAndFlush(any(PostLike.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(postRepository.findLikeCount(postId)).thenReturn(1);

        LikeActionResponse response = service.likePost(userId, postId);

        assertThat(response.postId()).isEqualTo(postId);
        assertThat(response.liked()).isTrue();
        assertThat(response.likeCount()).isEqualTo(1);
    }

    @Test
    void likePost_concurrentDuplicateInsert_throwsAlreadyLiked() {
        when(postLikeRepository.existsById(likeId)).thenReturn(false);
        when(postLikeRepository.saveAndFlush(any(PostLike.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.likePost(userId, postId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_ALREADY_LIKED);
    }

    @Test
    void unlikePost_notLiked_throwsPostNotFound() {
        when(postLikeRepository.deleteByUserAndPost(userId, postId)).thenReturn(0);

        assertThatThrownBy(() -> service.unlikePost(userId, postId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_NOT_FOUND);
    }

    @Test
    void unlikePost_hiddenPostNonOwner_throwsPostNotFound() {
        Post draftPost =
                Post.builder()
                        .id(postId)
                        .userId(UUID.randomUUID())
                        .status(PostStatus.DRAFT)
                        .build();
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(draftPost));

        assertThatThrownBy(() -> service.unlikePost(userId, postId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_NOT_FOUND);
        verify(postLikeRepository, never()).deleteByUserAndPost(any(), any());
    }

    @Test
    void unlikePost_publishedPostNotVisible_throwsPostNotFound() {
        when(postVisibilityService.isVisibleTo(userId, publishedPost)).thenReturn(false);

        assertThatThrownBy(() -> service.unlikePost(userId, postId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_NOT_FOUND);
        verify(postLikeRepository, never()).deleteByUserAndPost(any(), any());
    }

    @Test
    void unlikePost_hiddenPostOwner_reachesLikeLookup() {
        Post draftPost = Post.builder().id(postId).userId(userId).status(PostStatus.DRAFT).build();
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(draftPost));
        when(postVisibilityService.isVisibleTo(userId, draftPost)).thenReturn(true);
        when(postLikeRepository.deleteByUserAndPost(userId, postId)).thenReturn(1);
        when(postRepository.findLikeCount(postId)).thenReturn(0);

        LikeActionResponse response = service.unlikePost(userId, postId);

        assertThat(response.liked()).isFalse();
        verify(postLikeRepository).deleteByUserAndPost(userId, postId);
    }

    @Test
    void unlikePost_removedLike_publishesTheUnlikeForTheNotificationTier() {
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(publishedPost));
        when(postVisibilityService.isVisibleTo(userId, publishedPost)).thenReturn(true);
        when(postLikeRepository.deleteByUserAndPost(userId, postId)).thenReturn(1);
        when(postRepository.findLikeCount(postId)).thenReturn(0);

        service.unlikePost(userId, postId);

        verify(outboxService)
                .enqueue(
                        eq(PostEventTypes.POST_UNLIKED_V1),
                        eq(PostEventTypes.POST_UNLIKED_V1),
                        eq("post"),
                        eq(postId),
                        eq(userId),
                        eq(
                                Map.of(
                                        "postId", postId.toString(),
                                        "postOwnerId", publishedPost.getUserId().toString(),
                                        "userId", userId.toString())));
    }

    @Test
    void likePost_unlikeThenRelike_succeeds() {
        when(postLikeRepository.existsById(likeId)).thenReturn(false, false);
        when(postLikeRepository.saveAndFlush(any(PostLike.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(postLikeRepository.deleteByUserAndPost(userId, postId)).thenReturn(1);
        when(postRepository.findLikeCount(postId)).thenReturn(1, 0, 1);

        LikeActionResponse first = service.likePost(userId, postId);
        LikeActionResponse removed = service.unlikePost(userId, postId);
        LikeActionResponse second = service.likePost(userId, postId);

        assertThat(first.liked()).isTrue();
        assertThat(removed.liked()).isFalse();
        assertThat(second.liked()).isTrue();
        verify(postLikeRepository, times(2)).saveAndFlush(any(PostLike.class));
        verify(postLikeRepository, times(1)).deleteByUserAndPost(userId, postId);
    }

    @Test
    void listLikers_postNotVisible_throwsPostNotFound() {
        when(postVisibilityService.isVisibleTo(userId, publishedPost)).thenReturn(false);

        assertThatThrownBy(() -> service.listLikers(userId, postId, null, 20))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_NOT_FOUND);
    }

    @Test
    void likePost_postNotFound_throwsPostNotFound() {
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.likePost(userId, postId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_NOT_FOUND);
    }

    @Test
    void likePost_unpublishedPostNonOwner_throwsPostNotFound() {
        Post draftPost =
                Post.builder()
                        .id(postId)
                        .userId(UUID.randomUUID())
                        .status(PostStatus.DRAFT)
                        .build();
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(draftPost));

        assertThatThrownBy(() -> service.likePost(userId, postId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_NOT_FOUND);
    }

    @Test
    void listLikers_invalidCursor_throwsInvalidCursor() {
        assertThatThrownBy(() -> service.listLikers(userId, postId, "!!!not-valid-base64!!!", 20))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.INVALID_CURSOR);
    }
}
