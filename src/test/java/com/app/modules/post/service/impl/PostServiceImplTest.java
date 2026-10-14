package com.app.modules.post.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import com.app.common.settings.service.SystemSettingService;
import com.app.modules.hashtag.service.HashtagService;
import com.app.modules.media.entity.MediaAsset;
import com.app.modules.media.enums.MediaType;
import com.app.modules.post.dto.request.CreatePostRequest;
import com.app.modules.post.dto.request.PostStatusTransitionRequest;
import com.app.modules.post.dto.request.UpdatePostCaptionRequest;
import com.app.modules.post.dto.response.FeedPostResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostEditHistory;
import com.app.modules.post.enums.FeedSurface;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.enums.PostType;
import com.app.modules.post.mapper.PostMapper;
import com.app.modules.post.repository.PostEditHistoryRepository;
import com.app.modules.post.repository.PostMediaAssetRepository;
import com.app.modules.post.repository.PostModerationProjection;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.repository.PostUserRepository;
import com.app.modules.post.service.PostVisibilityService;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.service.UserSummaryService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@ExtendWith(MockitoExtension.class)
class PostServiceImplTest {

    @Mock private PostRepository postRepository;
    @Mock private PostEditHistoryRepository postEditHistoryRepository;
    @Mock private PostUserRepository postUserRepository;
    @Mock private PostMediaAssetRepository postMediaAssetRepository;
    @Mock private HashtagService hashtagService;
    @Mock private SystemSettingService systemSettingService;
    @Mock private PostVisibilityService postVisibilityService;
    @Mock private PostResponseAssembler postResponseAssembler;
    @Mock private PostMapper postMapper;
    @Mock private SocialService socialService;
    @Mock private OutboxService outboxService;
    @Mock private UserSummaryService userSummaryService;

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
                        postMediaAssetRepository,
                        hashtagService,
                        systemSettingService,
                        postVisibilityService,
                        postResponseAssembler,
                        postMapper,
                        socialService,
                        outboxService,
                        userSummaryService);
        lenient()
                .when(systemSettingService.getRequiredLong("max_post_media_items"))
                .thenReturn(10L);
        lenient()
                .when(systemSettingService.getRequiredLong("max_hashtags_per_post"))
                .thenReturn(30L);
        lenient().when(hashtagService.getHashtagIdsForPosts(any())).thenReturn(Map.of());
        // Mimic Hibernate id and created_at assignment so publish-time index events carry both.
        lenient()
                .when(postRepository.saveAndFlush(any(Post.class)))
                .thenAnswer(
                        invocation -> {
                            Post p = invocation.getArgument(0);
                            if (p.getId() == null) {
                                p.setId(postId);
                            }
                            if (p.getCreatedAt() == null) {
                                p.setCreatedAt(OffsetDateTime.now());
                            }
                            return p;
                        });
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

    private MediaAsset asset(UUID id, UUID ownerId, MediaType type) {
        return MediaAsset.builder().id(id).userId(ownerId).mediaType(type).build();
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

    private CreatePostRequest createRequest(
            String caption, PostType type, List<UUID> mediaIds, PostStatus status) {
        return new CreatePostRequest(caption, type, mediaIds, status, null, null, null);
    }

    @Test
    void createPost_missingMediaAsset_throwsNotFound() {
        UUID mediaId = UUID.randomUUID();
        when(postMediaAssetRepository.findAllById(List.of(mediaId))).thenReturn(List.of());

        assertThatThrownBy(
                        () ->
                                service.createPost(
                                        authorId,
                                        createRequest(
                                                null, PostType.IMAGE, List.of(mediaId), null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.NOT_FOUND);
    }

    @Test
    void createPost_mediaNotOwnedByAuthor_throwsPostForbidden() {
        UUID mediaId = UUID.randomUUID();
        when(postMediaAssetRepository.findAllById(List.of(mediaId)))
                .thenReturn(List.of(asset(mediaId, strangerId, MediaType.IMAGE)));

        assertThatThrownBy(
                        () ->
                                service.createPost(
                                        authorId,
                                        createRequest(
                                                null, PostType.IMAGE, List.of(mediaId), null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_FORBIDDEN);
    }

    @Test
    void createPost_carouselWithSingleMedia_throwsBadRequest() {
        UUID mediaId = UUID.randomUUID();
        when(postMediaAssetRepository.findAllById(List.of(mediaId)))
                .thenReturn(List.of(asset(mediaId, authorId, MediaType.IMAGE)));

        assertThatThrownBy(
                        () ->
                                service.createPost(
                                        authorId,
                                        createRequest(
                                                null, PostType.CAROUSEL, List.of(mediaId), null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.BAD_REQUEST);
    }

    @Test
    void createPost_imageTypeWithVideoAsset_throwsBadRequest() {
        UUID mediaId = UUID.randomUUID();
        when(postMediaAssetRepository.findAllById(List.of(mediaId)))
                .thenReturn(List.of(asset(mediaId, authorId, MediaType.VIDEO)));

        assertThatThrownBy(
                        () ->
                                service.createPost(
                                        authorId,
                                        createRequest(
                                                null, PostType.IMAGE, List.of(mediaId), null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.BAD_REQUEST);
    }

    @Test
    void createPost_published_extractsHashtagsFromCaption() {
        UUID mediaId = UUID.randomUUID();
        when(postMediaAssetRepository.findAllById(List.of(mediaId)))
                .thenReturn(List.of(asset(mediaId, authorId, MediaType.IMAGE)));

        service.createPost(
                authorId,
                createRequest(
                        "Sunset #Beach and #sunset_2024 vibes",
                        PostType.IMAGE,
                        List.of(mediaId),
                        PostStatus.PUBLISHED));

        verify(hashtagService).upsertHashtagsForPost(postId, List.of("Beach", "sunset_2024"));
        verify(outboxService)
                .enqueue(
                        eq("post.index.upsert.v1"),
                        eq("post.index.upsert.v1"),
                        eq("post"),
                        any(UUID.class),
                        any(),
                        anyMap());
    }

    @Test
    void createPost_draft_screensTheCaptionButAssociatesNothing() {
        UUID mediaId = UUID.randomUUID();
        when(postMediaAssetRepository.findAllById(List.of(mediaId)))
                .thenReturn(List.of(asset(mediaId, authorId, MediaType.IMAGE)));

        service.createPost(
                authorId,
                createRequest("#draft tag", PostType.IMAGE, List.of(mediaId), PostStatus.DRAFT));

        // A draft creates no post_hashtags rows, which is the rule this test exists for. It is
        // still screened for banned tags: a draft naming one could never be published, so the
        // author should learn while the caption is in front of them rather than at publish time.
        verify(hashtagService).findBannedNames(List.of("draft"));
        verify(hashtagService, never()).upsertHashtagsForPost(any(), any());
        verify(hashtagService, never()).upsertHashtagsForPostSkippingBanned(any(), any());
    }

    @Test
    void createPost_success_logsInfoWithPostId() {
        UUID mediaId = UUID.randomUUID();
        when(postMediaAssetRepository.findAllById(List.of(mediaId)))
                .thenReturn(List.of(asset(mediaId, authorId, MediaType.IMAGE)));

        Logger logger = (Logger) LoggerFactory.getLogger(PostServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.createPost(
                    authorId,
                    createRequest("caption", PostType.IMAGE, List.of(mediaId), PostStatus.DRAFT));
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
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(ownedPost(PostStatus.DRAFT)));

        Logger logger = (Logger) LoggerFactory.getLogger(PostServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.transitionStatus(
                    authorId, postId, new PostStatusTransitionRequest(PostStatus.PUBLISHED));
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
    void updateCaption_nonOwner_throwsPostForbidden() {
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(ownedPost(PostStatus.PUBLISHED)));

        assertThatThrownBy(
                        () ->
                                service.updateCaption(
                                        strangerId, postId, new UpdatePostCaptionRequest("new")))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_FORBIDDEN);
        verifyNoInteractions(postEditHistoryRepository);
    }

    @Test
    void updateCaption_owner_appendsEditHistoryWithPreviousCaption() {
        Post post = ownedPost(PostStatus.DRAFT);
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(post));

        service.updateCaption(authorId, postId, new UpdatePostCaptionRequest("new caption"));

        ArgumentCaptor<PostEditHistory> captor = ArgumentCaptor.forClass(PostEditHistory.class);
        verify(postEditHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().getPreviousCaption()).isEqualTo("old caption");
        assertThat(captor.getValue().getEditorId()).isEqualTo(authorId);
        assertThat(captor.getValue().getPostId()).isEqualTo(postId);
        assertThat(post.getCaption()).isEqualTo("new caption");
    }

    @Test
    void updateCaption_publishedPost_refreshesHashtags() {
        Post post = ownedPost(PostStatus.PUBLISHED);
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(post));

        service.updateCaption(authorId, postId, new UpdatePostCaptionRequest("now with #fresh"));

        InOrder inOrder = Mockito.inOrder(hashtagService);
        inOrder.verify(hashtagService).removeHashtagsForPost(postId);
        inOrder.verify(hashtagService).upsertHashtagsForPost(postId, List.of("fresh"));
    }

    @Test
    void transitionStatus_draftToPublished_upsertsHashtags() {
        Post post = ownedPost(PostStatus.DRAFT);
        post.setCaption("publishing #now");
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(post));

        service.transitionStatus(
                authorId, postId, new PostStatusTransitionRequest(PostStatus.PUBLISHED));

        assertThat(post.getStatus()).isEqualTo(PostStatus.PUBLISHED);
        verify(hashtagService).upsertHashtagsForPost(postId, List.of("now"));
        verify(outboxService)
                .enqueue(
                        eq("post.index.upsert.v1"),
                        eq("post.index.upsert.v1"),
                        eq("post"),
                        any(UUID.class),
                        any(),
                        anyMap());
    }

    @Test
    void transitionStatus_publishedToArchived_removesHashtags() {
        Post post = ownedPost(PostStatus.PUBLISHED);
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(post));

        service.transitionStatus(
                authorId, postId, new PostStatusTransitionRequest(PostStatus.ARCHIVED));

        assertThat(post.getStatus()).isEqualTo(PostStatus.ARCHIVED);
        verify(hashtagService).removeHashtagsForPost(postId);
        verify(outboxService)
                .enqueue(
                        eq("post.index.delete.v1"),
                        eq("post.index.delete.v1"),
                        eq("post"),
                        any(UUID.class),
                        any(),
                        anyMap());
    }

    @Test
    void transitionStatus_archivedToPublished_succeeds() {
        Post post = ownedPost(PostStatus.ARCHIVED);
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(post));

        service.transitionStatus(
                authorId, postId, new PostStatusTransitionRequest(PostStatus.PUBLISHED));

        assertThat(post.getStatus()).isEqualTo(PostStatus.PUBLISHED);
    }

    @Test
    void transitionStatus_publishedToDraft_throwsBadRequest() {
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(ownedPost(PostStatus.PUBLISHED)));

        assertThatThrownBy(
                        () ->
                                service.transitionStatus(
                                        authorId,
                                        postId,
                                        new PostStatusTransitionRequest(PostStatus.DRAFT)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.BAD_REQUEST);
    }

    @Test
    void transitionStatus_toRemovedByAdmin_softDeletes() {
        authenticateAs("ADMIN");
        Post post = ownedPost(PostStatus.PUBLISHED);
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(post));

        service.transitionStatus(
                strangerId, postId, new PostStatusTransitionRequest(PostStatus.REMOVED));

        assertThat(post.getStatus()).isEqualTo(PostStatus.REMOVED);
        assertThat(post.getDeletedAt()).isNotNull();
        verify(hashtagService).removeHashtagsForPost(postId);
    }

    @Test
    void transitionStatus_toRemovedByStranger_throwsPostForbidden() {
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(ownedPost(PostStatus.PUBLISHED)));

        assertThatThrownBy(
                        () ->
                                service.transitionStatus(
                                        strangerId,
                                        postId,
                                        new PostStatusTransitionRequest(PostStatus.REMOVED)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_FORBIDDEN);
    }

    @Test
    void deletePost_owner_setsDeletedAtAndRemovedStatus() {
        Post post = ownedPost(PostStatus.PUBLISHED);
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(post));

        service.deletePost(authorId, postId);

        assertThat(post.getStatus()).isEqualTo(PostStatus.REMOVED);
        assertThat(post.getDeletedAt()).isNotNull();
        verify(hashtagService).removeHashtagsForPost(postId);
        verify(outboxService)
                .enqueue(
                        eq("post.index.delete.v1"),
                        eq("post.index.delete.v1"),
                        eq("post"),
                        any(UUID.class),
                        any(),
                        anyMap());
    }

    @Test
    void deletePost_nonOwnerNonAdmin_throwsPostForbidden() {
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(ownedPost(PostStatus.PUBLISHED)));

        assertThatThrownBy(() -> service.deletePost(strangerId, postId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_FORBIDDEN);
    }

    @Test
    void getPostById_notVisible_throwsPostForbidden() {
        Post post = ownedPost(PostStatus.PUBLISHED);
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(post));
        when(postVisibilityService.isVisibleTo(strangerId, post)).thenReturn(false);

        assertThatThrownBy(() -> service.getPostById(strangerId, postId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_FORBIDDEN);
    }

    @Test
    void getPostById_missing_throwsPostNotFound() {
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPostById(strangerId, postId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_NOT_FOUND);
    }

    @Test
    void listEditHistory_nonOwner_throwsPostForbidden() {
        when(postRepository.findByIdAndDeletedAtIsNull(postId))
                .thenReturn(Optional.of(ownedPost(PostStatus.PUBLISHED)));

        assertThatThrownBy(() -> service.listEditHistory(strangerId, postId, null, 20))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_FORBIDDEN);
        verifyNoInteractions(postEditHistoryRepository);
    }

    @Test
    void createPost_duplicateMediaIds_throwsBadRequest() {
        UUID mediaId = UUID.randomUUID();

        assertThatThrownBy(
                        () ->
                                service.createPost(
                                        authorId,
                                        createRequest(
                                                null,
                                                PostType.CAROUSEL,
                                                List.of(mediaId, mediaId),
                                                null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.BAD_REQUEST);
        verifyNoInteractions(postMediaAssetRepository);
    }

    @Test
    void createPost_invalidInitialStatus_throwsBadRequest() {
        assertThatThrownBy(
                        () ->
                                service.createPost(
                                        authorId,
                                        createRequest(
                                                null,
                                                PostType.IMAGE,
                                                List.of(UUID.randomUUID()),
                                                PostStatus.ARCHIVED)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.BAD_REQUEST);
        verifyNoInteractions(postMediaAssetRepository);
    }

    @Test
    void createPost_textType_noMedia_success() {
        service.createPost(
                authorId,
                createRequest("Hello #textworld", PostType.TEXT, null, PostStatus.PUBLISHED));

        verify(postRepository).saveAndFlush(any(Post.class));
        verifyNoInteractions(postMediaAssetRepository);
        verify(hashtagService).upsertHashtagsForPost(eq(postId), eq(List.of("textworld")));
        verify(outboxService)
                .enqueue(
                        eq("post.index.upsert.v1"),
                        eq("post.index.upsert.v1"),
                        eq("post"),
                        any(UUID.class),
                        any(),
                        anyMap());
    }

    @Test
    void createPost_textType_blankCaption_throwsBadRequest() {
        assertThatThrownBy(
                        () ->
                                service.createPost(
                                        authorId, createRequest("   ", PostType.TEXT, null, null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.BAD_REQUEST);
        verifyNoInteractions(postMediaAssetRepository);
        verifyNoInteractions(postRepository);
    }

    @Test
    void createPost_textType_nullCaption_throwsBadRequest() {
        assertThatThrownBy(
                        () ->
                                service.createPost(
                                        authorId, createRequest(null, PostType.TEXT, null, null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.BAD_REQUEST);
        verifyNoInteractions(postMediaAssetRepository);
        verifyNoInteractions(postRepository);
    }

    @Test
    void createPost_textType_withMedia_throwsBadRequest() {
        assertThatThrownBy(
                        () ->
                                service.createPost(
                                        authorId,
                                        createRequest(
                                                "Hello",
                                                PostType.TEXT,
                                                List.of(UUID.randomUUID()),
                                                null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.BAD_REQUEST);
        verifyNoInteractions(postMediaAssetRepository);
        verifyNoInteractions(postRepository);
    }

    @Test
    void getFeed_noFollowingAccounts_returnsEmptyPage() {
        UUID viewer = UUID.randomUUID();
        when(socialService.getAcceptedFollowingExcludingBlocks(viewer))
                .thenReturn(Collections.emptyList());

        CursorPageResponse<FeedPostResponse> page = service.getFeed(viewer, null, 20);

        assertThat(page.getContent()).isEmpty();
        verifyNoInteractions(postRepository);
    }

    @Test
    void getFeed_firstPage_returnsMostRecentPosts() {
        UUID viewer = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        Post post = ownedPost(PostStatus.PUBLISHED);
        FeedPostResponse feedResponse = feedResponse();
        when(socialService.getAcceptedFollowingExcludingBlocks(viewer)).thenReturn(List.of(author));
        when(postRepository.findFirstFeedPosts(any(), any(), any())).thenReturn(List.of(post));
        when(postResponseAssembler.assembleFeed(eq(viewer), any()))
                .thenReturn(List.of(feedResponse));

        CursorPageResponse<FeedPostResponse> page = service.getFeed(viewer, null, 20);

        assertThat(page.getContent()).hasSize(1).containsExactly(feedResponse);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getFeed_followingSurface_dropsMutedPostAuthorsAndKeepsShowFewerOnes() {
        UUID viewer = UUID.randomUUID();
        UUID mutedAuthor = UUID.randomUUID();
        UUID showFewerAuthor = UUID.randomUUID();
        when(socialService.getAcceptedFollowingExcludingBlocks(viewer))
                .thenReturn(List.of(mutedAuthor, showFewerAuthor));
        // The social service answers per surface: on the following feed only the mute applies.
        when(socialService.findFeedSuppressedAuthors(eq(viewer), any(), eq(FeedSurface.FOLLOWING)))
                .thenReturn(java.util.Set.of(mutedAuthor));
        when(postRepository.findFirstFeedPosts(any(), any(), any())).thenReturn(List.of());

        service.getFeed(viewer, null, 20, FeedSurface.FOLLOWING);

        ArgumentCaptor<List<UUID>> authors = ArgumentCaptor.forClass(List.class);
        verify(postRepository).findFirstFeedPosts(authors.capture(), eq(viewer), any());
        assertThat(authors.getValue()).containsExactly(showFewerAuthor);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getFeed_forYouFallbackSurface_asksForTheShowFewerAuthorsToo() {
        UUID viewer = UUID.randomUUID();
        UUID mutedAuthor = UUID.randomUUID();
        UUID showFewerAuthor = UUID.randomUUID();
        UUID keptAuthor = UUID.randomUUID();
        when(socialService.getAcceptedFollowingExcludingBlocks(viewer))
                .thenReturn(List.of(mutedAuthor, showFewerAuthor, keptAuthor));
        when(socialService.findFeedSuppressedAuthors(
                        eq(viewer), any(), eq(FeedSurface.FOR_YOU_FALLBACK)))
                .thenReturn(java.util.Set.of(mutedAuthor, showFewerAuthor));
        when(postRepository.findFirstFeedPosts(any(), any(), any())).thenReturn(List.of());

        service.getFeed(viewer, null, 20, FeedSurface.FOR_YOU_FALLBACK);

        ArgumentCaptor<List<UUID>> authors = ArgumentCaptor.forClass(List.class);
        verify(postRepository).findFirstFeedPosts(authors.capture(), eq(viewer), any());
        assertThat(authors.getValue()).containsExactly(keptAuthor);
        verify(socialService, never())
                .findFeedSuppressedAuthors(any(), any(), eq(FeedSurface.FOLLOWING));
    }

    @Test
    void getFeed_threeArgumentOverload_usesTheFollowingSurface() {
        UUID viewer = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        when(socialService.getAcceptedFollowingExcludingBlocks(viewer)).thenReturn(List.of(author));
        when(postRepository.findFirstFeedPosts(any(), any(), any())).thenReturn(List.of());

        service.getFeed(viewer, null, 20);

        verify(socialService)
                .findFeedSuppressedAuthors(viewer, List.of(author), FeedSurface.FOLLOWING);
    }

    @Test
    void getFeed_everyFollowedAuthorSuppressed_returnsEmptyPageWithoutQueryingPosts() {
        UUID viewer = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        when(socialService.getAcceptedFollowingExcludingBlocks(viewer)).thenReturn(List.of(author));
        when(socialService.findFeedSuppressedAuthors(
                        eq(viewer), any(), eq(FeedSurface.FOR_YOU_FALLBACK)))
                .thenReturn(java.util.Set.of(author));

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
        String cursor =
                CursorCodec.encode(
                        new Cursor(TimeCursors.toMicros(OffsetDateTime.now()), UUID.randomUUID()),
                        CursorScope.POST_FEED);
        FeedPostResponse feedResponse = feedResponse();
        when(socialService.getAcceptedFollowingExcludingBlocks(viewer)).thenReturn(List.of(author));
        when(postRepository.findFeedPostsBefore(any(), any(), any(), any(), any()))
                .thenReturn(List.of(post));
        when(postResponseAssembler.assembleFeed(eq(viewer), any()))
                .thenReturn(List.of(feedResponse));

        CursorPageResponse<FeedPostResponse> page = service.getFeed(viewer, cursor, 20);

        assertThat(page.getContent()).containsExactly(feedResponse);
        verify(postRepository).findFeedPostsBefore(any(), any(), any(), any(), any());
    }

    @Test
    void getFeed_invalidCursor_throwsInvalidCursor() {
        UUID viewer = UUID.randomUUID();

        // The cursor is decoded before the accepted-following lookup, so an invalid cursor throws
        // even for a viewer who follows nobody; socialService is deliberately never stubbed here.
        assertThatThrownBy(() -> service.getFeed(viewer, "!!!not-valid-base64!!!", 20))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.INVALID_CURSOR);
    }

    @Test
    void getFeed_pageSizeExceedsMax_normalizesToHundred() {
        UUID viewer = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        ArgumentCaptor<org.springframework.data.domain.Pageable> pageCaptor =
                ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        when(socialService.getAcceptedFollowingExcludingBlocks(viewer)).thenReturn(List.of(author));
        when(postRepository.findFirstFeedPosts(any(), any(), any())).thenReturn(List.of());

        service.getFeed(viewer, null, 9999);

        verify(postRepository).findFirstFeedPosts(any(), any(), pageCaptor.capture());
        // normalizeLimit caps at 100, then fetches pageSize+1 to detect next page
        assertThat(pageCaptor.getValue().getPageSize()).isEqualTo(101);
    }

    @Test
    void getFeed_pageSizeZero_normalizesToDefault() {
        UUID viewer = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        ArgumentCaptor<org.springframework.data.domain.Pageable> pageCaptor =
                ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        when(socialService.getAcceptedFollowingExcludingBlocks(viewer)).thenReturn(List.of(author));
        when(postRepository.findFirstFeedPosts(any(), any(), any())).thenReturn(List.of());

        service.getFeed(viewer, null, 0);

        verify(postRepository).findFirstFeedPosts(any(), any(), pageCaptor.capture());
        // normalizeLimit defaults to 20, then fetches pageSize+1 to detect next page
        assertThat(pageCaptor.getValue().getPageSize()).isEqualTo(21);
    }

    @Test
    void getFeed_exactlyPageSizeResults_hasNextPageTrue() {
        UUID viewer = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        // Return pageSize+1 posts to trigger hasNextPage = true.
        List<Post> posts = buildPosts(author, 21);
        FeedPostResponse feedResponse = feedResponse();
        when(socialService.getAcceptedFollowingExcludingBlocks(viewer)).thenReturn(List.of(author));
        when(postRepository.findFirstFeedPosts(any(), any(), any())).thenReturn(posts);
        when(postResponseAssembler.assembleFeed(eq(viewer), any()))
                .thenReturn(Collections.nCopies(20, feedResponse));

        CursorPageResponse<FeedPostResponse> page = service.getFeed(viewer, null, 20);

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
        when(postRepository.findFirstFeedPosts(any(), any(), any())).thenReturn(posts);
        when(postResponseAssembler.assembleFeed(eq(viewer), eq(posts)))
                .thenReturn(Collections.nCopies(5, feedResponse));

        CursorPageResponse<FeedPostResponse> page = service.getFeed(viewer, null, 20);

        assertThat(page.getPageInfo().isHasNextPage()).isFalse();
        assertThat(page.getContent()).hasSize(5);
    }

    @Test
    void applyModerationRestore_blankPriorStatus_restoresAsPublished() {
        PostModerationProjection post = Mockito.mock(PostModerationProjection.class);
        when(post.getUserId()).thenReturn(authorId);
        when(post.getCaption()).thenReturn(null);
        when(post.getCreatedAt()).thenReturn(java.time.Instant.now());
        when(post.getStatusBeforeModeration()).thenReturn("   ");
        when(postRepository.findModerationViewIncludingDeleted(postId))
                .thenReturn(Optional.of(post));

        var result = service.applyModerationRestore(postId);

        assertThat(result.status()).isEqualTo(PostStatus.PUBLISHED);
        verify(postRepository).applyModerationRestore(postId, "published");
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
                null,
                null,
                List.of(),
                List.of(),
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                null);
    }
}
