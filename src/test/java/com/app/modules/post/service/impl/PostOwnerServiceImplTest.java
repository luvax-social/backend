package com.app.modules.post.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.common.pagination.Cursor;
import com.app.common.pagination.CursorCodec;
import com.app.common.pagination.CursorScope;
import com.app.common.pagination.TimeCursors;
import com.app.common.response.CursorPageResponse;
import com.app.modules.post.dto.request.ReplaceCollaboratorsRequest;
import com.app.modules.post.dto.request.ReplacePostTagsRequest;
import com.app.modules.post.dto.request.SetPostPlaceRequest;
import com.app.modules.post.dto.request.UpdateAltTextRequest;
import com.app.modules.post.dto.request.UpdatePostContentRequest;
import com.app.modules.post.dto.response.OwnerPostCountsResponse;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostMedia;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.enums.PostType;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.place.entity.Place;
import com.app.modules.post.place.service.PlaceService;
import com.app.modules.post.repository.PostMediaRepository;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.service.PostCollaborationService;
import com.app.modules.post.service.PostTagService;

@ExtendWith(MockitoExtension.class)
class PostOwnerServiceImplTest {

    @Mock private PostRepository postRepository;
    @Mock private PostMediaRepository postMediaRepository;
    @Mock private PostContentWriter postContentWriter;
    @Mock private PostResponseAssembler postResponseAssembler;
    @Mock private PostSchedulePolicy postSchedulePolicy;
    @Mock private PostTagService postTagService;
    @Mock private PostCollaborationService postCollaborationService;
    @Mock private PlaceService placeService;
    @Mock private PostIndexEventPublisher postIndexEventPublisher;
    @Mock private OutboxService outboxService;

    private PostOwnerServiceImpl service;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID strangerId = UUID.randomUUID();
    private final UUID postId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service =
                new PostOwnerServiceImpl(
                        postRepository,
                        postMediaRepository,
                        postContentWriter,
                        postResponseAssembler,
                        postSchedulePolicy,
                        postTagService,
                        postCollaborationService,
                        placeService,
                        postIndexEventPublisher,
                        outboxService);
        lenient()
                .when(postResponseAssembler.assemble(any(), any(Post.class)))
                .thenReturn(org.mockito.Mockito.mock(PostResponse.class));
    }

    private Post post(PostStatus status) {
        return Post.builder()
                .id(postId)
                .userId(ownerId)
                .status(status)
                .postType(PostType.IMAGE)
                .updatedAt(OffsetDateTime.parse("2026-10-01T10:00:00Z"))
                .scheduledAt(OffsetDateTime.parse("2026-11-01T10:00:00Z"))
                .build();
    }

    private void stubLocked(Post post) {
        when(postRepository.findByIdForUpdate(postId)).thenReturn(Optional.of(post));
    }

    private static AppException failureOf(Runnable action) {
        try {
            action.run();
        } catch (AppException e) {
            return e;
        }
        throw new AssertionError("expected an AppException");
    }

    private UpdatePostContentRequest contentRequest() {
        return new UpdatePostContentRequest(
                "caption", PostType.TEXT, null, null, null, null, null, null);
    }

    @Test
    void replaceContent_draft_replacesAndRecordsTheSave() {
        Post post = post(PostStatus.DRAFT);
        stubLocked(post);

        service.replaceContent(ownerId, postId, contentRequest());

        verify(postContentWriter).replace(eq(post), any());
        verify(outboxService)
                .enqueue(
                        eq(PostEventTypes.POST_DRAFT_SAVED_V1),
                        any(),
                        eq("post"),
                        eq(postId),
                        eq(ownerId),
                        anyMap());
    }

    @Test
    void replaceContent_scheduled_replacesWithoutRecordingADraftSave() {
        Post post = post(PostStatus.SCHEDULED);
        stubLocked(post);

        service.replaceContent(ownerId, postId, contentRequest());

        verify(postContentWriter).replace(eq(post), any());
        verifyNoInteractions(outboxService);
    }

    @Test
    void replaceContent_publishedOrArchived_isAConflict() {
        for (PostStatus status : List.of(PostStatus.PUBLISHED, PostStatus.ARCHIVED)) {
            stubLocked(post(status));

            AppException failure =
                    failureOf(() -> service.replaceContent(ownerId, postId, contentRequest()));

            assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_STATE_CONFLICT);
        }
        verifyNoInteractions(postContentWriter);
    }

    @Test
    void replaceContent_strangerOnADraft_readsAsNotFound() {
        stubLocked(post(PostStatus.DRAFT));

        AppException failure =
                failureOf(() -> service.replaceContent(strangerId, postId, contentRequest()));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_NOT_FOUND);
        verifyNoInteractions(postContentWriter);
    }

    @Test
    void replaceContent_strangerOnAPublishedPost_isForbidden() {
        stubLocked(post(PostStatus.PUBLISHED));

        AppException failure =
                failureOf(() -> service.replaceContent(strangerId, postId, contentRequest()));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_FORBIDDEN);
    }

    @Test
    void replaceContent_missingPost_isNotFound() {
        when(postRepository.findByIdForUpdate(postId)).thenReturn(Optional.empty());

        AppException failure =
                failureOf(() -> service.replaceContent(ownerId, postId, contentRequest()));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_NOT_FOUND);
    }

    @Test
    void listOwnerPosts_draftsAreReadByLastChangeAndTheCursorIsBuiltFromIt() {
        Post post = post(PostStatus.DRAFT);
        when(postRepository.findFirstOwnerPostsByStatus(eq(ownerId), eq("draft"), any()))
                .thenReturn(List.of(post));
        when(postResponseAssembler.assemble(eq(ownerId), anyList()))
                .thenReturn(List.of(org.mockito.Mockito.mock(PostResponse.class)));

        CursorPageResponse<PostResponse> page =
                service.listOwnerPosts(ownerId, PostStatus.DRAFT, null, 20);

        Cursor end =
                CursorCodec.decode(
                        page.getPageInfo().getEndCursor(), CursorScope.OWNER_POSTS_DRAFT);
        assertThat(end.sortValueMicros()).isEqualTo(TimeCursors.toMicros(post.getUpdatedAt()));
        assertThat(end.id()).isEqualTo(postId);
    }

    @Test
    void listOwnerPosts_scheduledAreReadSoonestFirstAndTheCursorUsesTheDueTime() {
        Post post = post(PostStatus.SCHEDULED);
        when(postRepository.findFirstOwnerScheduledPosts(eq(ownerId), any()))
                .thenReturn(List.of(post));
        when(postResponseAssembler.assemble(eq(ownerId), anyList()))
                .thenReturn(List.of(org.mockito.Mockito.mock(PostResponse.class)));

        CursorPageResponse<PostResponse> page =
                service.listOwnerPosts(ownerId, PostStatus.SCHEDULED, null, 20);

        Cursor end =
                CursorCodec.decode(
                        page.getPageInfo().getEndCursor(), CursorScope.OWNER_POSTS_SCHEDULED);
        assertThat(end.sortValueMicros()).isEqualTo(TimeCursors.toMicros(post.getScheduledAt()));
    }

    @Test
    void listOwnerPosts_aCursorIsBoundToItsStatus() {
        String draftCursor =
                CursorCodec.encode(
                        new Cursor(TimeCursors.toMicros(OffsetDateTime.now()), UUID.randomUUID()),
                        CursorScope.OWNER_POSTS_DRAFT);

        AppException failure =
                failureOf(
                        () ->
                                service.listOwnerPosts(
                                        ownerId, PostStatus.ARCHIVED, draftCursor, 20));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.INVALID_CURSOR);
    }

    @Test
    void listOwnerPosts_publishedOrRemoved_isBadRequest() {
        for (PostStatus status : List.of(PostStatus.PUBLISHED, PostStatus.REMOVED)) {
            AppException failure =
                    failureOf(() -> service.listOwnerPosts(ownerId, status, null, 20));
            assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.BAD_REQUEST);
        }
        verifyNoInteractions(postRepository);
    }

    @Test
    void listOwnerPosts_pageSizeIsClampedAndOneRowMoreIsFetched() {
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        when(postRepository.findFirstOwnerPostsByStatus(any(), any(), page.capture()))
                .thenReturn(List.of());

        service.listOwnerPosts(ownerId, PostStatus.ARCHIVED, null, 5000);

        assertThat(page.getValue().getPageSize()).isEqualTo(101);
    }

    @Test
    void counts_reportEachStateAndTheCap() {
        when(postRepository.countByOwnerAndStatus(ownerId, "draft")).thenReturn(4L);
        when(postRepository.countByOwnerAndStatus(ownerId, "scheduled")).thenReturn(2L);
        when(postRepository.countByOwnerAndStatus(ownerId, "archived")).thenReturn(7L);
        when(postSchedulePolicy.limit()).thenReturn(50L);

        OwnerPostCountsResponse counts = service.counts(ownerId);

        assertThat(counts).isEqualTo(new OwnerPostCountsResponse(4, 2, 7, 50));
    }

    @Test
    void setPlace_publishedPost_setsTheIdAndRefreshesTheIndexDocument() {
        Post post = post(PostStatus.PUBLISHED);
        stubLocked(post);
        UUID placeId = UUID.randomUUID();
        when(placeService.resolveForPostWrite(ownerId, null, "Da Nang"))
                .thenReturn(Place.builder().id(placeId).name("Da Nang").build());

        service.setPlace(ownerId, postId, new SetPostPlaceRequest(null, "Da Nang"));

        assertThat(post.getPlaceId()).isEqualTo(placeId);
        verify(postIndexEventPublisher).enqueueUpsert(post);
    }

    @Test
    void setPlace_draft_doesNotTouchTheIndex() {
        Post post = post(PostStatus.DRAFT);
        stubLocked(post);
        when(placeService.resolveForPostWrite(ownerId, null, "Da Nang"))
                .thenReturn(Place.builder().id(UUID.randomUUID()).name("Da Nang").build());

        service.setPlace(ownerId, postId, new SetPostPlaceRequest(null, "Da Nang"));

        verifyNoInteractions(postIndexEventPublisher);
    }

    @Test
    void setPlace_nothingNamed_clearsThePlace() {
        Post post = post(PostStatus.ARCHIVED);
        post.setPlaceId(UUID.randomUUID());
        stubLocked(post);
        when(placeService.resolveForPostWrite(ownerId, null, null)).thenReturn(null);

        service.setPlace(ownerId, postId, new SetPostPlaceRequest(null, null));

        assertThat(post.getPlaceId()).isNull();
    }

    @Test
    void replaceTags_delegatesToTheTagServiceForAnyRetainedStatus() {
        for (PostStatus status :
                List.of(
                        PostStatus.DRAFT,
                        PostStatus.SCHEDULED,
                        PostStatus.PUBLISHED,
                        PostStatus.ARCHIVED)) {
            Post post = post(status);
            stubLocked(post);

            service.replaceTags(ownerId, postId, new ReplacePostTagsRequest(List.of()));

            verify(postTagService, org.mockito.Mockito.atLeastOnce()).replaceTags(post, List.of());
        }
    }

    @Test
    void replaceCollaborators_delegatesToTheCollaborationService() {
        Post post = post(PostStatus.PUBLISHED);
        stubLocked(post);
        UUID invitee = UUID.randomUUID();

        service.replaceCollaborators(
                ownerId, postId, new ReplaceCollaboratorsRequest(List.of(invitee)));

        verify(postCollaborationService).replaceInvites(post, List.of(invitee));
    }

    @Test
    void updateAltText_setsTrimmedTextAndClearsBlank() {
        Post post = post(PostStatus.ARCHIVED);
        stubLocked(post);
        UUID mediaId = UUID.randomUUID();
        PostMedia media = PostMedia.builder().id(mediaId).altText("old").build();
        when(postMediaRepository.findByIdAndPostId(mediaId, postId)).thenReturn(Optional.of(media));

        service.updateAltText(ownerId, postId, mediaId, new UpdateAltTextRequest("  A red door  "));
        assertThat(media.getAltText()).isEqualTo("A red door");

        service.updateAltText(ownerId, postId, mediaId, new UpdateAltTextRequest("   "));
        assertThat(media.getAltText()).isNull();
    }

    @Test
    void updateAltText_mediaOfAnotherPost_isNotFound() {
        stubLocked(post(PostStatus.DRAFT));
        UUID mediaId = UUID.randomUUID();
        when(postMediaRepository.findByIdAndPostId(mediaId, postId)).thenReturn(Optional.empty());

        AppException failure =
                failureOf(
                        () ->
                                service.updateAltText(
                                        ownerId, postId, mediaId, new UpdateAltTextRequest("x")));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.NOT_FOUND);
        verify(postMediaRepository, never()).saveAndFlush(any());
    }
}
