package com.app.modules.post.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.common.pagination.Cursor;
import com.app.common.pagination.CursorCodec;
import com.app.common.pagination.CursorScope;
import com.app.common.pagination.TimeCursors;
import com.app.common.response.CursorPageResponse;
import com.app.common.response.UserSummaryResponse;
import com.app.common.security.user.UserPrincipal;
import com.app.modules.post.dto.request.CreatePostRequest;
import com.app.modules.post.dto.request.PostStatusTransitionRequest;
import com.app.modules.post.dto.request.UpdatePostCaptionRequest;
import com.app.modules.post.dto.response.FeedPostResponse;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostEditHistory;
import com.app.modules.post.enums.FeedSurface;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.enums.PostType;
import com.app.modules.post.mapper.PostMapper;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.place.entity.Place;
import com.app.modules.post.place.enums.PlaceStatus;
import com.app.modules.post.place.repository.PlaceRepository;
import com.app.modules.post.repository.PostEditHistoryRepository;
import com.app.modules.post.repository.PostModerationProjection;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.repository.PostUserRepository;
import com.app.modules.post.service.PostMentionService;
import com.app.modules.post.service.PostMentionService.MentionChange;
import com.app.modules.post.service.PostModerationResult;
import com.app.modules.post.service.PostPublicationService;
import com.app.modules.post.service.PostPublicationService.Source;
import com.app.modules.post.service.PostVisibilityService;
import com.app.modules.post.validation.PostTypeFilter;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.entity.User;
import com.app.modules.users.service.UserSummaryService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@ExtendWith(MockitoExtension.class)
class PostServiceImplTest {

    private static final UUID NO_AUTHOR = new UUID(0L, 0L);

    @Mock private PostRepository postRepository;
    @Mock private PostEditHistoryRepository postEditHistoryRepository;
    @Mock private PostUserRepository postUserRepository;
    @Mock private PostCaptionHashtags captionHashtags;
    @Mock private PostVisibilityService postVisibilityService;
    @Mock private PostResponseAssembler postResponseAssembler;
    @Mock private PostMapper postMapper;
    @Mock private SocialService socialService;
    @Mock private OutboxService outboxService;
    @Mock private UserSummaryService userSummaryService;
    @Mock private PostContentWriter postContentWriter;
    @Mock private PostPublicationService postPublicationService;
    @Mock private PostSchedulePolicy postSchedulePolicy;
    @Mock private PostMentionService postMentionService;
    @Mock private PostIndexEventPublisher postIndexEventPublisher;
    @Mock private PlaceRepository placeRepository;

    private PostServiceImpl service;

    private final UUID authorId = UUID.randomUUID();
    private final UUID strangerId = UUID.randomUUID();
    private final UUID postId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service =
                new PostServiceImpl(
                        postRepository,
                        postEditHistoryRepository,
                        postUserRepository,
                        captionHashtags,
                        postVisibilityService,
                        postResponseAssembler,
                        postMapper,
                        socialService,
                        outboxService,
                        userSummaryService,
                        postContentWriter,
                        postPublicationService,
                        postSchedulePolicy,
                        postMentionService,
                        postIndexEventPublisher,
                        placeRepository);
        lenient()
                .when(postRepository.saveAndFlush(any(Post.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient()
                .when(postMentionService.replaceCaptionMentions(any()))
                .thenReturn(new MentionChange(List.of(), List.of(), List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String role) {
        UserPrincipal principal =
                new UserPrincipal(UUID.randomUUID(), "actor@test.local", role, "ACTIVE");
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities()));
    }

    private Post ownedPost(PostStatus status) {
        return Post.builder()
                .id(postId)
                .userId(authorId)
                .status(status)
                .postType(PostType.IMAGE)
                .caption("old caption")
                .createdAt(OffsetDateTime.now())
                .build();
    }

    private void stubOwnedPost(Post post) {
        when(postRepository.findByIdForUpdate(postId)).thenReturn(Optional.of(post));
    }

    private CreatePostRequest createRequest(PostStatus status, OffsetDateTime scheduledAt) {
        return new CreatePostRequest(
                "caption", PostType.TEXT, null, null, status, scheduledAt, null, null, null, null);
    }

    private static AppException failureOf(Runnable action) {
        try {
            action.run();
        } catch (AppException e) {
            return e;
        }
        throw new AssertionError("expected an AppException");
    }

    @Test
    void createPost_published_storesADraftThenPublishesThroughTheRoutine() {
        Post stored = ownedPost(PostStatus.DRAFT);
        when(postContentWriter.create(eq(authorId), any(), eq(PostStatus.DRAFT), eq(null)))
                .thenReturn(stored);

        service.createPost(authorId, createRequest(PostStatus.PUBLISHED, null));

        verify(postPublicationService).publish(stored, Source.OWNER);
        verifyNoInteractions(postSchedulePolicy);
    }

    @Test
    void createPost_noStatus_defaultsToPublished() {
        Post stored = ownedPost(PostStatus.DRAFT);
        when(postContentWriter.create(eq(authorId), any(), eq(PostStatus.DRAFT), eq(null)))
                .thenReturn(stored);

        service.createPost(authorId, createRequest(null, null));

        verify(postPublicationService).publish(stored, Source.OWNER);
    }

    @Test
    void createPost_draft_takesASlotAndRecordsTheSaveWithoutPublishing() {
        Post stored = ownedPost(PostStatus.DRAFT);
        when(postContentWriter.create(eq(authorId), any(), eq(PostStatus.DRAFT), eq(null)))
                .thenReturn(stored);

        service.createPost(authorId, createRequest(PostStatus.DRAFT, null));

        verify(postSchedulePolicy).requireRoomForUnpublished(authorId);
        verify(postPublicationService, never()).publish(any(), any());
        verify(outboxService)
                .enqueue(
                        eq(PostEventTypes.POST_DRAFT_SAVED_V1),
                        eq(PostEventTypes.POST_DRAFT_SAVED_V1),
                        eq("post"),
                        eq(postId),
                        eq(authorId),
                        anyMap());
    }

    @Test
    void createPost_scheduled_validatesTheTimeAndAnnouncesTheSchedule() {
        OffsetDateTime requested = OffsetDateTime.now().plusHours(3);
        OffsetDateTime floored = requested.withSecond(0).withNano(0);
        Post stored = ownedPost(PostStatus.SCHEDULED);
        stored.setScheduledAt(floored);
        when(postSchedulePolicy.validate(requested)).thenReturn(floored);
        when(postContentWriter.create(eq(authorId), any(), eq(PostStatus.SCHEDULED), eq(floored)))
                .thenReturn(stored);

        service.createPost(authorId, createRequest(PostStatus.SCHEDULED, requested));

        verify(postSchedulePolicy).requireRoomForUnpublished(authorId);
        verify(postPublicationService, never()).publish(any(), any());
        verify(outboxService)
                .enqueue(
                        eq(PostEventTypes.POST_SCHEDULED_V1),
                        eq(PostEventTypes.POST_SCHEDULED_V1),
                        eq("post"),
                        eq(postId),
                        eq(authorId),
                        anyMap());
    }

    @Test
    void createPost_scheduledTimeWithoutScheduledStatus_throwsBadRequest() {
        AppException failure =
                failureOf(
                        () ->
                                service.createPost(
                                        authorId,
                                        createRequest(
                                                PostStatus.PUBLISHED,
                                                OffsetDateTime.now().plusHours(1))));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.BAD_REQUEST);
        verifyNoInteractions(postContentWriter);
    }

    @Test
    void createPost_archivedOrRemovedInitialStatus_throwsBadRequest() {
        for (PostStatus status : List.of(PostStatus.ARCHIVED, PostStatus.REMOVED)) {
            AppException failure =
                    failureOf(() -> service.createPost(authorId, createRequest(status, null)));
            assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.BAD_REQUEST);
        }
        verifyNoInteractions(postContentWriter);
    }

    @Test
    void createPost_success_logsInfoWithPostId() {
        Post stored = ownedPost(PostStatus.DRAFT);
        when(postContentWriter.create(eq(authorId), any(), eq(PostStatus.DRAFT), eq(null)))
                .thenReturn(stored);

        Logger logger = (Logger) LoggerFactory.getLogger(PostServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.createPost(authorId, createRequest(PostStatus.DRAFT, null));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .anyMatch(
                        event ->
                                event.getLevel() == Level.INFO
                                        && event.getFormattedMessage().contains(postId.toString()));
    }

    @Test
    void transitionStatus_success_logsInfoWithFromAndToStatus() {
        stubOwnedPost(ownedPost(PostStatus.DRAFT));

        Logger logger = (Logger) LoggerFactory.getLogger(PostServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.transitionStatus(
                    authorId, postId, new PostStatusTransitionRequest(PostStatus.PUBLISHED, null));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .anyMatch(
                        event ->
                                event.getLevel() == Level.INFO
                                        && event.getFormattedMessage().contains(postId.toString())
                                        && event.getFormattedMessage().contains("DRAFT")
                                        && event.getFormattedMessage().contains("PUBLISHED"));
    }

    @Test
    void transitionStatus_draftToPublished_usesThePublicationRoutine() {
        Post post = ownedPost(PostStatus.DRAFT);
        stubOwnedPost(post);

        service.transitionStatus(
                authorId, postId, new PostStatusTransitionRequest(PostStatus.PUBLISHED, null));

        verify(postPublicationService).publish(post, Source.OWNER);
    }

    @Test
    void transitionStatus_scheduledToPublished_publishesNow() {
        Post post = ownedPost(PostStatus.SCHEDULED);
        stubOwnedPost(post);

        service.transitionStatus(
                authorId, postId, new PostStatusTransitionRequest(PostStatus.PUBLISHED, null));

        verify(postPublicationService).publish(post, Source.OWNER);
    }

    @Test
    void transitionStatus_draftToScheduled_setsTheValidatedTime() {
        Post post = ownedPost(PostStatus.DRAFT);
        stubOwnedPost(post);
        OffsetDateTime requested = OffsetDateTime.now().plusDays(2);
        OffsetDateTime floored = requested.withSecond(0).withNano(0);
        when(postSchedulePolicy.validate(requested)).thenReturn(floored);

        service.transitionStatus(
                authorId, postId, new PostStatusTransitionRequest(PostStatus.SCHEDULED, requested));

        assertThat(post.getStatus()).isEqualTo(PostStatus.SCHEDULED);
        assertThat(post.getScheduledAt()).isEqualTo(floored);
        verify(outboxService)
                .enqueue(
                        eq(PostEventTypes.POST_SCHEDULED_V1),
                        any(),
                        eq("post"),
                        eq(postId),
                        eq(authorId),
                        anyMap());
    }

    @Test
    void transitionStatus_scheduledToScheduled_reschedules() {
        Post post = ownedPost(PostStatus.SCHEDULED);
        post.setScheduledAt(OffsetDateTime.now().plusHours(2));
        stubOwnedPost(post);
        OffsetDateTime requested = OffsetDateTime.now().plusDays(1);
        OffsetDateTime floored = requested.withSecond(0).withNano(0);
        when(postSchedulePolicy.validate(requested)).thenReturn(floored);

        service.transitionStatus(
                authorId, postId, new PostStatusTransitionRequest(PostStatus.SCHEDULED, requested));

        assertThat(post.getScheduledAt()).isEqualTo(floored);
    }

    @Test
    void transitionStatus_scheduledToDraft_clearsTheTime() {
        Post post = ownedPost(PostStatus.SCHEDULED);
        post.setScheduledAt(OffsetDateTime.now().plusHours(2));
        stubOwnedPost(post);

        service.transitionStatus(
                authorId, postId, new PostStatusTransitionRequest(PostStatus.DRAFT, null));

        assertThat(post.getStatus()).isEqualTo(PostStatus.DRAFT);
        assertThat(post.getScheduledAt()).isNull();
    }

    @Test
    void transitionStatus_scheduleTimeOnANonScheduleTarget_throwsBadRequest() {
        stubOwnedPost(ownedPost(PostStatus.DRAFT));

        AppException failure =
                failureOf(
                        () ->
                                service.transitionStatus(
                                        authorId,
                                        postId,
                                        new PostStatusTransitionRequest(
                                                PostStatus.PUBLISHED,
                                                OffsetDateTime.now().plusHours(1))));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.BAD_REQUEST);
        verifyNoInteractions(postPublicationService);
    }

    @Test
    void transitionStatus_publishedToArchived_detachesHashtagsAndLeavesTheIndex() {
        Post post = ownedPost(PostStatus.PUBLISHED);
        stubOwnedPost(post);

        service.transitionStatus(
                authorId, postId, new PostStatusTransitionRequest(PostStatus.ARCHIVED, null));

        assertThat(post.getStatus()).isEqualTo(PostStatus.ARCHIVED);
        verify(captionHashtags).detach(postId);
        verify(postIndexEventPublisher).enqueueDelete(post);
        verify(outboxService)
                .enqueue(
                        eq(PostEventTypes.POST_ARCHIVED_V1),
                        any(),
                        eq("post"),
                        eq(postId),
                        eq(authorId),
                        anyMap());
    }

    @Test
    void transitionStatus_archivedToPublished_republishesAndRecordsTheUnarchive() {
        Post post = ownedPost(PostStatus.ARCHIVED);
        stubOwnedPost(post);

        service.transitionStatus(
                authorId, postId, new PostStatusTransitionRequest(PostStatus.PUBLISHED, null));

        verify(postPublicationService).publish(post, Source.OWNER);
        verify(outboxService)
                .enqueue(
                        eq(PostEventTypes.POST_UNARCHIVED_V1),
                        any(),
                        eq("post"),
                        eq(postId),
                        eq(authorId),
                        anyMap());
    }

    @Test
    void transitionStatus_anythingEverPublishedBackToDraft_throwsBadRequest() {
        for (PostStatus from : List.of(PostStatus.PUBLISHED, PostStatus.ARCHIVED)) {
            stubOwnedPost(ownedPost(from));

            AppException failure =
                    failureOf(
                            () ->
                                    service.transitionStatus(
                                            authorId,
                                            postId,
                                            new PostStatusTransitionRequest(
                                                    PostStatus.DRAFT, null)));

            assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.BAD_REQUEST);
        }
        verifyNoInteractions(postPublicationService);
    }

    @Test
    void transitionStatus_toRemovedByAdmin_softDeletesAndLeavesTheIndex() {
        authenticateAs("ADMIN");
        Post post = ownedPost(PostStatus.PUBLISHED);
        stubOwnedPost(post);

        service.transitionStatus(
                strangerId, postId, new PostStatusTransitionRequest(PostStatus.REMOVED, null));

        assertThat(post.getStatus()).isEqualTo(PostStatus.REMOVED);
        assertThat(post.getDeletedAt()).isNotNull();
        verify(captionHashtags).detach(postId);
        verify(postIndexEventPublisher).enqueueDelete(post);
    }

    @Test
    void transitionStatus_toRemovedByStranger_throwsPostForbidden() {
        stubOwnedPost(ownedPost(PostStatus.PUBLISHED));

        AppException failure =
                failureOf(
                        () ->
                                service.transitionStatus(
                                        strangerId,
                                        postId,
                                        new PostStatusTransitionRequest(PostStatus.REMOVED, null)));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_FORBIDDEN);
    }

    @Test
    void transitionStatus_strangerOnAnUnpublishedPost_readsAsNotFound() {
        stubOwnedPost(ownedPost(PostStatus.DRAFT));

        AppException failure =
                failureOf(
                        () ->
                                service.transitionStatus(
                                        strangerId,
                                        postId,
                                        new PostStatusTransitionRequest(
                                                PostStatus.PUBLISHED, null)));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_NOT_FOUND);
    }

    @Test
    void deletePost_owner_setsDeletedAtAndLeavesTheIndex() {
        Post post = ownedPost(PostStatus.PUBLISHED);
        stubOwnedPost(post);

        service.deletePost(authorId, postId);

        assertThat(post.getStatus()).isEqualTo(PostStatus.REMOVED);
        assertThat(post.getDeletedAt()).isNotNull();
        verify(captionHashtags).detach(postId);
        verify(postIndexEventPublisher).enqueueDelete(post);
    }

    @Test
    void deletePost_draft_neverTouchesTheIndex() {
        stubOwnedPost(ownedPost(PostStatus.DRAFT));

        service.deletePost(authorId, postId);

        verify(postIndexEventPublisher, never()).enqueueDelete(any(Post.class));
    }

    @Test
    void deletePost_nonOwnerNonAdmin_throwsPostForbidden() {
        stubOwnedPost(ownedPost(PostStatus.PUBLISHED));

        AppException failure = failureOf(() -> service.deletePost(strangerId, postId));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_FORBIDDEN);
    }

    @Test
    void updateCaption_nonOwner_throwsPostForbidden() {
        stubOwnedPost(ownedPost(PostStatus.PUBLISHED));

        AppException failure =
                failureOf(
                        () ->
                                service.updateCaption(
                                        strangerId, postId, new UpdatePostCaptionRequest("new")));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_FORBIDDEN);
        verifyNoInteractions(postEditHistoryRepository);
    }

    @Test
    void updateCaption_owner_appendsEditHistoryWithPreviousCaption() {
        Post post = ownedPost(PostStatus.DRAFT);
        stubOwnedPost(post);

        service.updateCaption(authorId, postId, new UpdatePostCaptionRequest("new caption"));

        ArgumentCaptor<PostEditHistory> captor = ArgumentCaptor.forClass(PostEditHistory.class);
        verify(postEditHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().getPreviousCaption()).isEqualTo("old caption");
        assertThat(captor.getValue().getEditorId()).isEqualTo(authorId);
        assertThat(captor.getValue().getPostId()).isEqualTo(postId);
        assertThat(post.getCaption()).isEqualTo("new caption");
    }

    @Test
    void updateCaption_draft_storesMentionsButTouchesNeitherHashtagsNorIndex() {
        stubOwnedPost(ownedPost(PostStatus.DRAFT));

        service.updateCaption(authorId, postId, new UpdatePostCaptionRequest("hi @jane #tag"));

        verify(captionHashtags).rejectBanned("hi @jane #tag");
        verify(postMentionService).replaceCaptionMentions(any());
        verify(captionHashtags, never()).refresh(any(), any());
        verifyNoInteractions(postIndexEventPublisher);
    }

    @Test
    void updateCaption_publishedPost_refreshesHashtagsAndIndexAndRecordsOnlyNewMentions() {
        Post post = ownedPost(PostStatus.PUBLISHED);
        stubOwnedPost(post);
        UUID added = UUID.randomUUID();
        when(postMentionService.replaceCaptionMentions(post))
                .thenReturn(new MentionChange(List.of(added), List.of(added), List.of(added)));

        service.updateCaption(authorId, postId, new UpdatePostCaptionRequest("now with #fresh"));

        verify(captionHashtags).refresh(postId, "now with #fresh");
        verify(postIndexEventPublisher).enqueueUpsert(post);
        // An edit never notifies: the third argument, the accounts to notify, is empty.
        verify(postMentionService).announce(post, List.of(added), List.of());
    }

    @Test
    void getPostById_notVisible_throwsPostForbidden() {
        Post post = ownedPost(PostStatus.PUBLISHED);
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(post));
        when(postVisibilityService.isVisibleTo(strangerId, post)).thenReturn(false);

        AppException failure = failureOf(() -> service.getPostById(strangerId, postId));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_FORBIDDEN);
    }

    @Test
    void getPostById_unpublishedPostOfSomeoneElse_readsAsNotFound() {
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(ownedPost(PostStatus.SCHEDULED)));

        AppException failure = failureOf(() -> service.getPostById(strangerId, postId));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_NOT_FOUND);
        verifyNoInteractions(postVisibilityService);
    }

    @Test
    void getPostById_ownDraft_isReturned() {
        Post post = ownedPost(PostStatus.DRAFT);
        PostResponse response = Mockito.mock(PostResponse.class);
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(post));
        when(postVisibilityService.isVisibleTo(authorId, post)).thenReturn(true);
        when(postResponseAssembler.assemble(authorId, post)).thenReturn(response);

        assertThat(service.getPostById(authorId, postId)).isSameAs(response);
    }

    @Test
    void getPostById_missing_throwsPostNotFound() {
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.empty());

        AppException failure = failureOf(() -> service.getPostById(strangerId, postId));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_NOT_FOUND);
    }

    @Test
    void listEditHistory_nonOwner_throwsPostForbidden() {
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(ownedPost(PostStatus.PUBLISHED)));

        AppException failure =
                failureOf(() -> service.listEditHistory(strangerId, postId, null, 20));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.POST_FORBIDDEN);
        verifyNoInteractions(postEditHistoryRepository);
    }

    @Test
    void listUserPosts_coAuthoredPostOfAPrivateOwner_isLeftOutForAViewerWhoCannotSeeIt() {
        UUID viewer = UUID.randomUUID();
        UUID coAuthor = UUID.randomUUID();
        UUID privateOwner = UUID.randomUUID();
        Post own = Post.builder().id(UUID.randomUUID()).userId(coAuthor).build();
        own.setPublishedAt(OffsetDateTime.now());
        Post shared = Post.builder().id(UUID.randomUUID()).userId(privateOwner).build();
        shared.setPublishedAt(OffsetDateTime.now().minusMinutes(1));
        when(postUserRepository.findByIdAndDeletedAtIsNull(coAuthor))
                .thenReturn(Optional.of(User.builder().id(coAuthor).isPrivate(false).build()));
        when(socialService.findBlockedEitherDirection(viewer)).thenReturn(Set.of());
        when(postRepository.findFirstUserPosts(eq(coAuthor), anyInt()))
                .thenReturn(List.of(own, shared));
        when(postVisibilityService.filterVisiblePostIds(eq(viewer), anyMap())).thenReturn(Set.of());
        when(postResponseAssembler.assemble(eq(viewer), anyList()))
                .thenAnswer(
                        invocation -> {
                            List<Post> kept = invocation.getArgument(1);
                            assertThat(kept).containsExactly(own);
                            return List.of(Mockito.mock(PostResponse.class));
                        });

        CursorPageResponse<PostResponse> page =
                service.listUserPosts(viewer, coAuthor, PostTypeFilter.parse(null), null, 20);

        assertThat(page.getContent()).hasSize(1);
    }

    @Test
    void listUserPosts_blockedProfile_readsAsUnknownUser() {
        UUID viewer = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(postUserRepository.findByIdAndDeletedAtIsNull(target))
                .thenReturn(Optional.of(User.builder().id(target).build()));
        when(socialService.findBlockedEitherDirection(viewer)).thenReturn(Set.of(target));

        AppException failure =
                failureOf(
                        () ->
                                service.listUserPosts(
                                        viewer, target, PostTypeFilter.parse(null), null, 20));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.NOT_FOUND);
    }

    @Test
    void listUserPosts_cursorIsBuiltFromPublicationTime() {
        UUID viewer = UUID.randomUUID();
        Post post = Post.builder().id(UUID.randomUUID()).userId(viewer).build();
        OffsetDateTime publishedAt = OffsetDateTime.parse("2026-10-01T10:15:30Z");
        post.setPublishedAt(publishedAt);
        when(postUserRepository.findByIdAndDeletedAtIsNull(viewer))
                .thenReturn(Optional.of(User.builder().id(viewer).build()));
        when(socialService.findBlockedEitherDirection(viewer)).thenReturn(Set.of());
        when(postRepository.findFirstUserPosts(eq(viewer), anyInt())).thenReturn(List.of(post));
        when(postResponseAssembler.assemble(eq(viewer), anyList()))
                .thenReturn(List.of(Mockito.mock(PostResponse.class)));

        CursorPageResponse<PostResponse> page =
                service.listUserPosts(viewer, viewer, PostTypeFilter.parse(null), null, 20);

        Cursor end =
                CursorCodec.decode(
                        page.getPageInfo().getEndCursor(),
                        PostTypeFilter.parse(null).cursorScope());
        assertThat(end.sortValueMicros()).isEqualTo(TimeCursors.toMicros(publishedAt));
        assertThat(end.id()).isEqualTo(post.getId());
    }

    @Test
    void getFeed_noFollowingAccounts_returnsEmptyPage() {
        UUID viewer = UUID.randomUUID();
        when(socialService.getAcceptedFollowingExcludingBlocks(viewer))
                .thenReturn(Collections.emptyList());

        CursorPageResponse<FeedPostResponse> page =
                service.getFeed(viewer, null, 20, FeedSurface.FOLLOWING);

        assertThat(page.getContent()).isEmpty();
        verifyNoInteractions(postRepository);
    }

    @Test
    void getFeed_firstPage_returnsMostRecentPosts() {
        UUID viewer = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        Post post = ownedPost(PostStatus.PUBLISHED);
        post.setPublishedAt(OffsetDateTime.now());
        FeedPostResponse feedResponse = feedResponse();
        when(socialService.getAcceptedFollowingExcludingBlocks(viewer)).thenReturn(List.of(author));
        when(postRepository.findFirstFeedPosts(any(), any(), any(), anyInt()))
                .thenReturn(List.of(post));
        when(postResponseAssembler.assembleFeed(eq(viewer), any()))
                .thenReturn(List.of(feedResponse));

        CursorPageResponse<FeedPostResponse> page =
                service.getFeed(viewer, null, 20, FeedSurface.FOLLOWING);

        assertThat(page.getContent()).hasSize(1).containsExactly(feedResponse);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getFeed_followingSurface_dropsMutedAuthorsAndKeepsShowFewerOnes() {
        UUID viewer = UUID.randomUUID();
        UUID mutedAuthor = UUID.randomUUID();
        UUID showFewerAuthor = UUID.randomUUID();
        when(socialService.getAcceptedFollowingExcludingBlocks(viewer))
                .thenReturn(List.of(mutedAuthor, showFewerAuthor));
        // The social service answers per surface: on the following feed only the mute applies.
        when(socialService.findAllFeedSuppressedAuthors(viewer, FeedSurface.FOLLOWING))
                .thenReturn(Set.of(mutedAuthor));
        when(postRepository.findFirstFeedPosts(any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.getFeed(viewer, null, 20, FeedSurface.FOLLOWING);

        ArgumentCaptor<List<UUID>> authors = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<UUID>> excluded = ArgumentCaptor.forClass(List.class);
        verify(postRepository)
                .findFirstFeedPosts(authors.capture(), excluded.capture(), eq(viewer), anyInt());
        assertThat(authors.getValue()).containsExactly(showFewerAuthor);
        // The muted author also hides a post that merely lists them as a co-author.
        assertThat(excluded.getValue()).containsExactly(mutedAuthor);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getFeed_forYouFallbackSurface_dropsTheShowFewerAuthorsToo() {
        UUID viewer = UUID.randomUUID();
        UUID mutedAuthor = UUID.randomUUID();
        UUID showFewerAuthor = UUID.randomUUID();
        UUID keptAuthor = UUID.randomUUID();
        when(socialService.getAcceptedFollowingExcludingBlocks(viewer))
                .thenReturn(List.of(mutedAuthor, showFewerAuthor, keptAuthor));
        when(socialService.findAllFeedSuppressedAuthors(viewer, FeedSurface.FOR_YOU_FALLBACK))
                .thenReturn(Set.of(mutedAuthor, showFewerAuthor));
        when(postRepository.findFirstFeedPosts(any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.getFeed(viewer, null, 20, FeedSurface.FOR_YOU_FALLBACK);

        ArgumentCaptor<List<UUID>> authors = ArgumentCaptor.forClass(List.class);
        verify(postRepository).findFirstFeedPosts(authors.capture(), any(), eq(viewer), anyInt());
        assertThat(authors.getValue()).containsExactly(keptAuthor);
        verify(socialService, never())
                .findAllFeedSuppressedAuthors(any(), eq(FeedSurface.FOLLOWING));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getFeed_blockedAccounts_areExcludedAsAuthorsOfAnyPost() {
        UUID viewer = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        UUID blockedCoAuthor = UUID.randomUUID();
        when(socialService.getAcceptedFollowingExcludingBlocks(viewer)).thenReturn(List.of(author));
        when(socialService.findBlockedEitherDirection(viewer)).thenReturn(Set.of(blockedCoAuthor));
        when(postRepository.findFirstFeedPosts(any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.getFeed(viewer, null, 20, FeedSurface.FOLLOWING);

        ArgumentCaptor<List<UUID>> excluded = ArgumentCaptor.forClass(List.class);
        verify(postRepository).findFirstFeedPosts(any(), excluded.capture(), eq(viewer), anyInt());
        assertThat(excluded.getValue()).containsExactly(blockedCoAuthor);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getFeed_nothingToExclude_passesTheNilAuthorSoTheListIsNeverEmpty() {
        UUID viewer = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        when(socialService.getAcceptedFollowingExcludingBlocks(viewer)).thenReturn(List.of(author));
        when(postRepository.findFirstFeedPosts(any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.getFeed(viewer, null, 20, FeedSurface.FOLLOWING);

        ArgumentCaptor<List<UUID>> excluded = ArgumentCaptor.forClass(List.class);
        verify(postRepository).findFirstFeedPosts(any(), excluded.capture(), eq(viewer), anyInt());
        assertThat(excluded.getValue()).containsExactly(NO_AUTHOR);
    }

    @Test
    void getFeed_everyFollowedAuthorSuppressed_returnsEmptyPageWithoutQueryingPosts() {
        UUID viewer = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        when(socialService.getAcceptedFollowingExcludingBlocks(viewer)).thenReturn(List.of(author));
        when(socialService.findAllFeedSuppressedAuthors(viewer, FeedSurface.FOR_YOU_FALLBACK))
                .thenReturn(Set.of(author));

        CursorPageResponse<FeedPostResponse> page =
                service.getFeed(viewer, null, 20, FeedSurface.FOR_YOU_FALLBACK);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getPageInfo().isHasNextPage()).isFalse();
        verifyNoInteractions(postRepository);
    }

    @Test
    void getFeed_withCursor_returnsPostsBefore() {
        UUID viewer = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        Post post = ownedPost(PostStatus.PUBLISHED);
        post.setPublishedAt(OffsetDateTime.now());
        String cursor =
                CursorCodec.encode(
                        new Cursor(TimeCursors.toMicros(OffsetDateTime.now()), UUID.randomUUID()),
                        CursorScope.POST_FEED);
        FeedPostResponse feedResponse = feedResponse();
        when(socialService.getAcceptedFollowingExcludingBlocks(viewer)).thenReturn(List.of(author));
        when(postRepository.findFeedPostsBefore(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(post));
        when(postResponseAssembler.assembleFeed(eq(viewer), any()))
                .thenReturn(List.of(feedResponse));

        CursorPageResponse<FeedPostResponse> page =
                service.getFeed(viewer, cursor, 20, FeedSurface.FOLLOWING);

        assertThat(page.getContent()).containsExactly(feedResponse);
        verify(postRepository).findFeedPostsBefore(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void getFeed_invalidCursor_throwsInvalidCursor() {
        UUID viewer = UUID.randomUUID();

        // The cursor is decoded before the accepted-following lookup, so an invalid cursor throws
        // even for a viewer who follows nobody; socialService is deliberately never stubbed here.
        AppException failure =
                failureOf(
                        () ->
                                service.getFeed(
                                        viewer,
                                        "!!!not-valid-base64!!!",
                                        20,
                                        FeedSurface.FOLLOWING));

        assertThat(failure.getErrorCode()).isEqualTo(ApiErrorCode.INVALID_CURSOR);
    }

    @Test
    void getFeed_pageSizeExceedsMax_normalizesToHundred() {
        UUID viewer = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        when(socialService.getAcceptedFollowingExcludingBlocks(viewer)).thenReturn(List.of(author));
        when(postRepository.findFirstFeedPosts(any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.getFeed(viewer, null, 9999, FeedSurface.FOLLOWING);

        verify(postRepository).findFirstFeedPosts(any(), any(), any(), limitCaptor.capture());
        // normalizeLimit caps at 100, then fetches pageSize+1 to detect next page
        assertThat(limitCaptor.getValue()).isEqualTo(101);
    }

    @Test
    void getFeed_pageSizeZero_normalizesToDefault() {
        UUID viewer = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        when(socialService.getAcceptedFollowingExcludingBlocks(viewer)).thenReturn(List.of(author));
        when(postRepository.findFirstFeedPosts(any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.getFeed(viewer, null, 0, FeedSurface.FOLLOWING);

        verify(postRepository).findFirstFeedPosts(any(), any(), any(), limitCaptor.capture());
        // normalizeLimit defaults to 20, then fetches pageSize+1 to detect next page
        assertThat(limitCaptor.getValue()).isEqualTo(21);
    }

    @Test
    void getFeed_exactlyPageSizeResults_hasNextPageTrue() {
        UUID viewer = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        // Return pageSize+1 posts to trigger hasNextPage = true.
        List<Post> posts = buildPosts(author, 21);
        FeedPostResponse feedResponse = feedResponse();
        when(socialService.getAcceptedFollowingExcludingBlocks(viewer)).thenReturn(List.of(author));
        when(postRepository.findFirstFeedPosts(any(), any(), any(), anyInt())).thenReturn(posts);
        when(postResponseAssembler.assembleFeed(eq(viewer), any()))
                .thenReturn(Collections.nCopies(20, feedResponse));

        CursorPageResponse<FeedPostResponse> page =
                service.getFeed(viewer, null, 20, FeedSurface.FOLLOWING);

        assertThat(page.getPageInfo().isHasNextPage()).isTrue();
        assertThat(page.getContent()).hasSize(20);
    }

    @Test
    void getFeed_fewerThanPageSizeResults_hasNextPageFalse() {
        UUID viewer = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        List<Post> posts = buildPosts(author, 5);
        FeedPostResponse feedResponse = feedResponse();
        when(socialService.getAcceptedFollowingExcludingBlocks(viewer)).thenReturn(List.of(author));
        when(postRepository.findFirstFeedPosts(any(), any(), any(), anyInt())).thenReturn(posts);
        when(postResponseAssembler.assembleFeed(eq(viewer), eq(posts)))
                .thenReturn(Collections.nCopies(5, feedResponse));

        CursorPageResponse<FeedPostResponse> page =
                service.getFeed(viewer, null, 20, FeedSurface.FOLLOWING);

        assertThat(page.getPageInfo().isHasNextPage()).isFalse();
        assertThat(page.getContent()).hasSize(5);
    }

    @Test
    void applyModerationRemoval_remembersThePriorStatusAndLeavesTheIndex() {
        PostModerationProjection post = projection("published", null, null, null);
        when(postRepository.findModerationViewIncludingDeleted(postId))
                .thenReturn(Optional.of(post));

        PostModerationResult result = service.applyModerationRemoval(postId);

        assertThat(result.status()).isEqualTo(PostStatus.REMOVED);
        verify(captionHashtags).detach(postId);
        verify(postRepository).applyModerationRemoval(eq(postId), any());
        verify(postIndexEventPublisher).enqueueDelete(postId, authorId);
    }

    @Test
    void applyModerationRestore_blankPriorStatus_restoresAsPublished() {
        PostModerationProjection post = projection("   ", null, null, null);
        when(postRepository.findModerationViewIncludingDeleted(postId))
                .thenReturn(Optional.of(post));

        PostModerationResult result = service.applyModerationRestore(postId);

        assertThat(result.status()).isEqualTo(PostStatus.PUBLISHED);
        verify(postRepository).applyModerationRestore(postId, "published", null);
        verify(postIndexEventPublisher).enqueueUpsert(eq(postId), eq(authorId), any(), any());
    }

    @Test
    void applyModerationRestore_draftBeforeRemoval_returnsAsDraftAndIsNotIndexed() {
        PostModerationProjection post = projection("draft", null, null, null);
        when(postRepository.findModerationViewIncludingDeleted(postId))
                .thenReturn(Optional.of(post));

        PostModerationResult result = service.applyModerationRestore(postId);

        assertThat(result.status()).isEqualTo(PostStatus.DRAFT);
        verify(postRepository).applyModerationRestore(postId, "draft", null);
        verifyNoInteractions(postIndexEventPublisher);
    }

    @Test
    void applyModerationRestore_scheduledWhoseTimePassed_returnsAsDraft() {
        PostModerationProjection post =
                projection("scheduled", Instant.now().minusSeconds(600), null, null);
        when(postRepository.findModerationViewIncludingDeleted(postId))
                .thenReturn(Optional.of(post));

        PostModerationResult result = service.applyModerationRestore(postId);

        assertThat(result.status()).isEqualTo(PostStatus.DRAFT);
        verify(postRepository).applyModerationRestore(postId, "draft", null);
    }

    @Test
    void applyModerationRestore_scheduledStillInTheFuture_staysScheduled() {
        PostModerationProjection post =
                projection("scheduled", Instant.now().plusSeconds(3600), null, null);
        when(postRepository.findModerationViewIncludingDeleted(postId))
                .thenReturn(Optional.of(post));

        PostModerationResult result = service.applyModerationRestore(postId);

        assertThat(result.status()).isEqualTo(PostStatus.SCHEDULED);
        verify(postRepository).applyModerationRestore(postId, "scheduled", null);
    }

    @Test
    void applyModerationRestore_placeBannedWhileRemoved_dropsItAndReportsItsName() {
        UUID placeId = UUID.randomUUID();
        PostModerationProjection post = projection("published", null, placeId, "caption");
        when(postRepository.findModerationViewIncludingDeleted(postId))
                .thenReturn(Optional.of(post));
        when(placeRepository.findById(placeId))
                .thenReturn(
                        Optional.of(
                                Place.builder()
                                        .id(placeId)
                                        .name("Closed Cafe")
                                        .status(PlaceStatus.BANNED)
                                        .build()));

        PostModerationResult result = service.applyModerationRestore(postId);

        assertThat(result.strippedPlaceName()).isEqualTo("Closed Cafe");
        verify(postRepository).applyModerationRestore(postId, "published", null);
    }

    @Test
    void applyModerationRestore_placeStillActive_isKept() {
        UUID placeId = UUID.randomUUID();
        PostModerationProjection post = projection("published", null, placeId, "caption");
        when(postRepository.findModerationViewIncludingDeleted(postId))
                .thenReturn(Optional.of(post));
        when(placeRepository.findById(placeId))
                .thenReturn(
                        Optional.of(
                                Place.builder()
                                        .id(placeId)
                                        .name("Open Cafe")
                                        .status(PlaceStatus.ACTIVE)
                                        .build()));

        PostModerationResult result = service.applyModerationRestore(postId);

        assertThat(result.strippedPlaceName()).isNull();
        verify(postRepository).applyModerationRestore(postId, "published", placeId);
    }

    @Test
    void applyModerationRestore_bannedHashtagInCaption_isReportedNotRefused() {
        PostModerationProjection post = projection("published", null, null, "a #banned tag");
        when(postRepository.findModerationViewIncludingDeleted(postId))
                .thenReturn(Optional.of(post));
        when(captionHashtags.extract("a #banned tag")).thenReturn(List.of("banned"));
        when(captionHashtags.upsertSkippingBanned(postId, List.of("banned")))
                .thenReturn(List.of("banned"));

        PostModerationResult result = service.applyModerationRestore(postId);

        assertThat(result.remainingBannedHashtags()).containsExactly("banned");
    }

    private PostModerationProjection projection(
            String statusBefore, Instant scheduledAt, UUID placeId, String caption) {
        PostModerationProjection post = Mockito.mock(PostModerationProjection.class);
        lenient().when(post.getUserId()).thenReturn(authorId);
        lenient().when(post.getCaption()).thenReturn(caption);
        lenient().when(post.getCreatedAt()).thenReturn(Instant.now());
        lenient().when(post.getStatusBeforeModeration()).thenReturn(statusBefore);
        lenient().when(post.getScheduledAt()).thenReturn(scheduledAt);
        lenient().when(post.getPlaceId()).thenReturn(placeId);
        lenient().when(post.getStatus()).thenReturn("removed");
        return post;
    }

    private List<Post> buildPosts(UUID userId, int count) {
        List<Post> result = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Post p =
                    Post.builder()
                            .id(UUID.randomUUID())
                            .userId(userId)
                            .status(PostStatus.PUBLISHED)
                            .postType(PostType.IMAGE)
                            .createdAt(OffsetDateTime.now().minusMinutes(i))
                            .publishedAt(OffsetDateTime.now().minusMinutes(i))
                            .build();
            result.add(p);
        }
        return result;
    }

    private FeedPostResponse feedResponse() {
        return new FeedPostResponse(
                UUID.randomUUID(),
                new UserSummaryResponse(authorId, "author", "Author", null, false),
                null,
                PostType.IMAGE,
                PostStatus.PUBLISHED,
                0,
                0,
                0,
                false,
                false,
                false,
                0,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                null);
    }
}
