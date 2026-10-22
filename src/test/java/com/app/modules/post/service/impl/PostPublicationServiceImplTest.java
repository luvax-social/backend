package com.app.modules.post.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.modules.media.entity.MediaAsset;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostMedia;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.place.service.PlaceService;
import com.app.modules.post.repository.PostMediaAssetRepository;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.repository.PostUserRepository;
import com.app.modules.post.service.PostCollaborationService;
import com.app.modules.post.service.PostMentionService;
import com.app.modules.post.service.PostMentionService.MentionChange;
import com.app.modules.post.service.PostPublicationException;
import com.app.modules.post.service.PostPublicationException.Reason;
import com.app.modules.post.service.PostPublicationService.Source;
import com.app.modules.post.service.PostTagService;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserStatus;

@ExtendWith(MockitoExtension.class)
class PostPublicationServiceImplTest {

    @Mock private PostRepository postRepository;
    @Mock private PostUserRepository postUserRepository;
    @Mock private PostMediaAssetRepository postMediaAssetRepository;
    @Mock private PostCaptionHashtags captionHashtags;
    @Mock private PlaceService placeService;
    @Mock private PostMentionService postMentionService;
    @Mock private PostTagService postTagService;
    @Mock private PostCollaborationService postCollaborationService;
    @Mock private PostIndexEventPublisher postIndexEventPublisher;
    @Mock private OutboxService outboxService;

    private PostPublicationServiceImpl service;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID postId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service =
                new PostPublicationServiceImpl(
                        postRepository,
                        postUserRepository,
                        postMediaAssetRepository,
                        captionHashtags,
                        placeService,
                        postMentionService,
                        postTagService,
                        postCollaborationService,
                        postIndexEventPublisher,
                        outboxService);
        Mockito.lenient()
                .when(postRepository.saveAndFlush(any(Post.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.lenient()
                .when(postUserRepository.findByIdAndDeletedAtIsNull(ownerId))
                .thenReturn(Optional.of(user(UserStatus.ACTIVE)));
        Mockito.lenient().when(captionHashtags.bannedNames(any())).thenReturn(List.of());
        Mockito.lenient()
                .when(postMentionService.replaceCaptionMentions(any()))
                .thenReturn(new MentionChange(List.of(), List.of(), List.of()));
    }

    private User user(UserStatus status) {
        return User.builder().id(ownerId).status(status).build();
    }

    private Post post(PostStatus status) {
        PostMedia media = PostMedia.builder().id(UUID.randomUUID()).mediaAssetId(assetId).build();
        return Post.builder()
                .id(postId)
                .userId(ownerId)
                .status(status)
                .caption("hello #world")
                .createdAt(OffsetDateTime.parse("2026-10-01T10:00:00Z"))
                .media(new ArrayList<>(List.of(media)))
                .build();
    }

    private void ownerOwnsTheAsset() {
        when(postMediaAssetRepository.findAllById(any()))
                .thenReturn(List.of(MediaAsset.builder().id(assetId).userId(ownerId).build()));
    }

    private static PostPublicationException failureOf(Runnable action) {
        try {
            action.run();
        } catch (PostPublicationException e) {
            return e;
        }
        throw new AssertionError("expected a PostPublicationException");
    }

    @Test
    void validate_draftWithEverythingIntact_passes() {
        ownerOwnsTheAsset();

        service.validate(post(PostStatus.DRAFT));
    }

    @Test
    void validate_publishedOrRemovedPost_isAnInvalidState() {
        for (PostStatus status : List.of(PostStatus.PUBLISHED, PostStatus.REMOVED)) {
            PostPublicationException failure = failureOf(() -> service.validate(post(status)));

            assertThat(failure.getReason()).isEqualTo(Reason.INVALID_STATE);
            assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_STATE_CONFLICT);
        }
    }

    @Test
    void validate_ownerSuspended_isRefused() {
        when(postUserRepository.findByIdAndDeletedAtIsNull(ownerId))
                .thenReturn(Optional.of(user(UserStatus.SUSPENDED)));

        PostPublicationException failure =
                failureOf(() -> service.validate(post(PostStatus.SCHEDULED)));

        assertThat(failure.getReason()).isEqualTo(Reason.OWNER_UNAVAILABLE);
    }

    @Test
    void validate_ownerDeleted_isRefused() {
        when(postUserRepository.findByIdAndDeletedAtIsNull(ownerId)).thenReturn(Optional.empty());

        PostPublicationException failure =
                failureOf(() -> service.validate(post(PostStatus.SCHEDULED)));

        assertThat(failure.getReason()).isEqualTo(Reason.OWNER_UNAVAILABLE);
    }

    @Test
    void validate_mediaAssetGone_isRefused() {
        when(postMediaAssetRepository.findAllById(any())).thenReturn(List.of());

        PostPublicationException failure =
                failureOf(() -> service.validate(post(PostStatus.DRAFT)));

        assertThat(failure.getReason()).isEqualTo(Reason.MEDIA_UNAVAILABLE);
    }

    @Test
    void validate_mediaAssetOwnedBySomeoneElse_isRefused() {
        when(postMediaAssetRepository.findAllById(any()))
                .thenReturn(
                        List.of(
                                MediaAsset.builder()
                                        .id(assetId)
                                        .userId(UUID.randomUUID())
                                        .build()));

        PostPublicationException failure =
                failureOf(() -> service.validate(post(PostStatus.DRAFT)));

        assertThat(failure.getReason()).isEqualTo(Reason.MEDIA_UNAVAILABLE);
    }

    @Test
    void validate_bannedHashtagInCaption_carriesTheOffendingNames() {
        ownerOwnsTheAsset();
        when(captionHashtags.bannedNames("hello #world")).thenReturn(List.of("world"));

        PostPublicationException failure =
                failureOf(() -> service.validate(post(PostStatus.DRAFT)));

        assertThat(failure.getReason()).isEqualTo(Reason.BANNED_HASHTAG);
        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_BANNED_HASHTAG);
        assertThat(failure.getDetails()).isEqualTo(Map.of("bannedTags", List.of("world")));
    }

    @Test
    void validate_placeBannedAfterTheDraftWasWritten_isRefused() {
        ownerOwnsTheAsset();
        UUID placeId = UUID.randomUUID();
        Post post = post(PostStatus.DRAFT);
        post.setPlaceId(placeId);
        doThrow(new AppException(ApiErrorCode.POST_PLACE_UNAVAILABLE))
                .when(placeService)
                .assertPublishable(placeId);

        PostPublicationException failure = failureOf(() -> service.validate(post));

        assertThat(failure.getReason()).isEqualTo(Reason.PLACE_UNAVAILABLE);
        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_PLACE_UNAVAILABLE);
    }

    @Test
    void validate_postWithoutAPlace_neverAsksThePlaceService() {
        ownerOwnsTheAsset();

        service.validate(post(PostStatus.DRAFT));

        verifyNoInteractions(placeService);
    }

    @Test
    void publish_firstPublication_runsEverySideEffectInOrderAndAnnouncesThePeopleInvolved() {
        ownerOwnsTheAsset();
        Post post = post(PostStatus.DRAFT);
        UUID mentioned = UUID.randomUUID();
        when(postMentionService.replaceCaptionMentions(post))
                .thenReturn(new MentionChange(List.of(mentioned), List.of(mentioned), List.of()));

        service.publish(post, Source.OWNER);

        assertThat(post.getStatus()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(post.getScheduledAt()).isNull();
        InOrder inOrder =
                Mockito.inOrder(
                        postRepository,
                        captionHashtags,
                        postMentionService,
                        postTagService,
                        postCollaborationService,
                        postIndexEventPublisher);
        inOrder.verify(postRepository).saveAndFlush(post);
        inOrder.verify(captionHashtags).refresh(postId, "hello #world");
        inOrder.verify(postMentionService).replaceCaptionMentions(post);
        inOrder.verify(postTagService).announceOnPublication(post);
        inOrder.verify(postCollaborationService).announceOnPublication(post);
        inOrder.verify(postIndexEventPublisher).enqueueUpsert(post);
        inOrder.verify(postMentionService).announce(post, List.of(mentioned), List.of(mentioned));
        verify(outboxService)
                .enqueue(
                        eq(PostEventTypes.POST_PUBLISHED_V1),
                        eq(PostEventTypes.POST_PUBLISHED_V1),
                        eq("post"),
                        eq(postId),
                        eq(ownerId),
                        anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_fromTheScheduler_recordsTheSourceAndHowLateItRan() {
        ownerOwnsTheAsset();
        Post post = post(PostStatus.SCHEDULED);
        post.setScheduledAt(OffsetDateTime.parse("2026-10-01T10:00:00Z"));
        // The database trigger stamps the publication time on flush; stand in for it here.
        when(postRepository.saveAndFlush(post))
                .thenAnswer(
                        invocation -> {
                            post.setPublishedAt(OffsetDateTime.parse("2026-10-01T10:00:41Z"));
                            return post;
                        });

        service.publish(post, Source.SCHEDULER);

        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(outboxService)
                .enqueue(
                        eq(PostEventTypes.POST_PUBLISHED_V1),
                        any(),
                        any(),
                        any(),
                        any(),
                        data.capture());
        assertThat(data.getValue())
                .containsEntry("source", "scheduler")
                .containsEntry("lagSeconds", 41L);
    }

    @Test
    void publish_unarchive_onlyRestoresTheIndexAndAnnouncesNothing() {
        ownerOwnsTheAsset();
        Post post = post(PostStatus.ARCHIVED);
        post.setPublishedAt(OffsetDateTime.parse("2026-09-01T10:00:00Z"));

        service.publish(post, Source.OWNER);

        assertThat(post.getStatus()).isEqualTo(PostStatus.PUBLISHED);
        verify(postIndexEventPublisher).enqueueUpsert(post);
        verify(postMentionService, never()).replaceCaptionMentions(any());
        verify(postMentionService, never()).announce(any(), any(), any());
        verify(postTagService, never()).announceOnPublication(any());
        verify(postCollaborationService, never()).announceOnPublication(any());
        verify(outboxService, never())
                .enqueue(eq(PostEventTypes.POST_PUBLISHED_V1), any(), any(), any(), any(), any());
    }

    @Test
    void publish_validationFailure_changesNothing() {
        Post post = post(PostStatus.DRAFT);
        when(postMediaAssetRepository.findAllById(any())).thenReturn(List.of());

        failureOf(() -> service.publish(post, Source.OWNER));

        assertThat(post.getStatus()).isEqualTo(PostStatus.DRAFT);
        verify(postRepository, never()).saveAndFlush(any());
        verifyNoInteractions(postIndexEventPublisher, postTagService, postCollaborationService);
    }

    @Test
    void failSchedule_returnsThePostToDraftsAndRecordsTheReason() {
        Post post = post(PostStatus.SCHEDULED);
        post.setScheduledAt(OffsetDateTime.parse("2026-10-01T10:00:00Z"));

        service.failSchedule(
                post,
                new PostPublicationException(
                        Reason.BANNED_HASHTAG, ApiErrorCode.POST_BANNED_HASHTAG));

        assertThat(post.getStatus()).isEqualTo(PostStatus.DRAFT);
        assertThat(post.getScheduledAt()).isNull();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(outboxService)
                .enqueue(
                        eq(PostEventTypes.POST_SCHEDULE_FAILED_V1),
                        eq(PostEventTypes.POST_SCHEDULE_FAILED_V1),
                        eq("post"),
                        eq(postId),
                        eq(ownerId),
                        data.capture());
        assertThat(data.getValue()).containsEntry("reason", "banned_hashtag");
    }
}
