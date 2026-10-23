package com.app.modules.post.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.response.UserSummaryResponse;
import com.app.modules.hashtag.service.HashtagService;
import com.app.modules.post.dto.response.PostCollaboratorResponse;
import com.app.modules.post.dto.response.PostExtras;
import com.app.modules.post.dto.response.PostMediaTagResponse;
import com.app.modules.post.dto.response.PostMentionResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostMedia;
import com.app.modules.post.enums.CollaborationStatus;
import com.app.modules.post.enums.TagStatus;
import com.app.modules.post.mapper.PostMapper;
import com.app.modules.post.place.dto.response.PlaceSummaryResponse;
import com.app.modules.post.place.service.PlaceService;
import com.app.modules.post.repository.PostMediaAssetRepository;
import com.app.modules.post.service.PostCollaborationService;
import com.app.modules.post.service.PostCollaborationService.CollaborationView;
import com.app.modules.post.service.PostMentionService;
import com.app.modules.post.service.PostTagService;
import com.app.modules.post.service.PostViewerState;
import com.app.modules.post.service.PostViewerStateService;
import com.app.modules.users.service.UserSummaryService;

@ExtendWith(MockitoExtension.class)
class PostResponseAssemblerTest {

    private static final PostExtras NO_EXTRAS =
            new PostExtras(null, List.of(), List.of(), null, null);

    @Mock private PostMediaAssetRepository postMediaAssetRepository;
    @Mock private UserSummaryService userSummaryService;
    @Mock private PostViewerStateService postViewerStateService;
    @Mock private HashtagService hashtagService;
    @Mock private PlaceService placeService;
    @Mock private PostMentionService postMentionService;
    @Mock private PostTagService postTagService;
    @Mock private PostCollaborationService postCollaborationService;
    @Mock private PostMapper postMapper;

    private PostResponseAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler =
                new PostResponseAssembler(
                        postMediaAssetRepository,
                        userSummaryService,
                        postViewerStateService,
                        hashtagService,
                        placeService,
                        postMentionService,
                        postTagService,
                        postCollaborationService,
                        postMapper);
    }

    @Test
    void assemble_noMedia_skipsAssetLookup() {
        UUID viewerId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Post post =
                Post.builder()
                        .id(UUID.randomUUID())
                        .userId(authorId)
                        .media(new ArrayList<>())
                        .build();
        UserSummaryResponse author =
                new UserSummaryResponse(authorId, "jane_doe", "Jane", null, false);
        when(userSummaryService.loadSummaries(anyCollection()))
                .thenReturn(Map.of(authorId, author));
        when(postViewerStateService.load(eq(viewerId), anyCollection()))
                .thenReturn(PostViewerState.NONE);

        assembler.assemble(viewerId, post);

        verify(postMediaAssetRepository, never()).findAllById(any());
        verify(placeService, never()).loadActiveSummaries(any());
        verify(postMapper)
                .toResponse(
                        eq(post),
                        eq(List.of()),
                        eq(author),
                        eq(false),
                        eq(false),
                        eq(false),
                        eq(List.of()),
                        eq(NO_EXTRAS));
    }

    @Test
    void assemble_withMedia_batchesAssetLookupByDistinctIds() {
        UUID viewerId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        PostMedia media = PostMedia.builder().id(UUID.randomUUID()).mediaAssetId(assetId).build();
        Post post =
                Post.builder().id(UUID.randomUUID()).userId(authorId).media(List.of(media)).build();
        UserSummaryResponse author =
                new UserSummaryResponse(authorId, "jane_doe", "Jane", null, false);
        when(postMediaAssetRepository.findAllById(Set.of(assetId))).thenReturn(List.of());
        when(userSummaryService.loadSummaries(anyCollection()))
                .thenReturn(Map.of(authorId, author));
        when(postViewerStateService.load(eq(viewerId), anyCollection()))
                .thenReturn(PostViewerState.NONE);

        assembler.assemble(viewerId, List.of(post));

        verify(postMediaAssetRepository).findAllById(Set.of(assetId));
        // Asset map is empty, so the media row hydrates against a null asset and no tags.
        verify(postMapper).toMediaResponse(eq(media), isNull(), eq(List.of()));
        verify(postMapper)
                .toResponse(
                        eq(post),
                        any(),
                        eq(author),
                        eq(false),
                        eq(false),
                        eq(false),
                        eq(List.of()),
                        eq(NO_EXTRAS));
    }

    @Test
    void assemble_resolvesAuthor_batchesUserLookupByDistinctIds() {
        UUID viewerId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UserSummaryResponse author =
                new UserSummaryResponse(authorId, "jane_doe", "Jane", null, false);
        Post post =
                Post.builder()
                        .id(UUID.randomUUID())
                        .userId(authorId)
                        .media(new ArrayList<>())
                        .build();
        when(userSummaryService.loadSummaries(List.of(authorId)))
                .thenReturn(Map.of(authorId, author));
        when(postViewerStateService.load(eq(viewerId), anyCollection()))
                .thenReturn(PostViewerState.NONE);

        assembler.assemble(viewerId, post);

        verify(userSummaryService).loadSummaries(List.of(authorId));
        verify(postMapper)
                .toResponse(
                        eq(post),
                        eq(List.of()),
                        eq(author),
                        eq(false),
                        eq(false),
                        eq(false),
                        eq(List.of()),
                        eq(NO_EXTRAS));
    }

    @Test
    void assemble_viewerLikedSavedAndReportedPost_flagsTrue() {
        UUID viewerId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        Post post = Post.builder().id(postId).userId(authorId).media(new ArrayList<>()).build();
        UserSummaryResponse author =
                new UserSummaryResponse(authorId, "jane_doe", "Jane", null, false);
        when(userSummaryService.loadSummaries(anyCollection()))
                .thenReturn(Map.of(authorId, author));
        when(postViewerStateService.load(eq(viewerId), anyCollection()))
                .thenReturn(new PostViewerState(Set.of(postId), Set.of(postId), Set.of(postId)));

        assembler.assemble(viewerId, post);

        verify(postMapper)
                .toResponse(
                        eq(post),
                        eq(List.of()),
                        eq(author),
                        eq(true),
                        eq(true),
                        eq(true),
                        eq(List.of()),
                        eq(NO_EXTRAS));
    }

    @Test
    void assemble_postWithPlaceMentionsAndCollaborators_passesThemToTheMapper() {
        UUID viewerId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        Post post =
                Post.builder()
                        .id(postId)
                        .userId(authorId)
                        .placeId(placeId)
                        .media(new ArrayList<>())
                        .build();
        UserSummaryResponse author =
                new UserSummaryResponse(authorId, "jane_doe", "Jane", null, false);
        PlaceSummaryResponse place = new PlaceSummaryResponse(placeId, "Da Nang");
        PostMentionResponse mention = new PostMentionResponse("john", UUID.randomUUID(), "john");
        PostCollaboratorResponse collaborator =
                new PostCollaboratorResponse(author, CollaborationStatus.ACCEPTED);
        when(userSummaryService.loadSummaries(anyCollection()))
                .thenReturn(Map.of(authorId, author));
        when(postViewerStateService.load(eq(viewerId), anyCollection()))
                .thenReturn(PostViewerState.NONE);
        when(placeService.loadActiveSummaries(Set.of(placeId))).thenReturn(Map.of(placeId, place));
        when(postMentionService.loadForPosts(eq(viewerId), anyCollection()))
                .thenReturn(Map.of(postId, List.of(mention)));
        when(postCollaborationService.loadForPosts(eq(viewerId), anyCollection()))
                .thenReturn(
                        Map.of(
                                postId,
                                new CollaborationView(
                                        List.of(collaborator), CollaborationStatus.INVITED)));

        assembler.assemble(viewerId, post);

        verify(postMapper)
                .toResponse(
                        eq(post),
                        eq(List.of()),
                        eq(author),
                        eq(false),
                        eq(false),
                        eq(false),
                        eq(List.of()),
                        eq(
                                new PostExtras(
                                        place,
                                        List.of(mention),
                                        List.of(collaborator),
                                        CollaborationStatus.INVITED,
                                        null)));
    }

    @Test
    void assemble_bannedPlace_isOmittedBecauseTheLookupLeavesItOut() {
        UUID viewerId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Post post =
                Post.builder()
                        .id(UUID.randomUUID())
                        .userId(authorId)
                        .placeId(UUID.randomUUID())
                        .media(new ArrayList<>())
                        .build();
        when(userSummaryService.loadSummaries(anyCollection())).thenReturn(Map.of());
        when(postViewerStateService.load(eq(viewerId), anyCollection()))
                .thenReturn(PostViewerState.NONE);
        when(placeService.loadActiveSummaries(anyCollection())).thenReturn(Map.of());

        assembler.assemble(viewerId, post);

        verify(postMapper)
                .toResponse(
                        eq(post),
                        eq(List.of()),
                        any(),
                        eq(false),
                        eq(false),
                        eq(false),
                        eq(List.of()),
                        eq(NO_EXTRAS));
    }

    @Test
    void assemble_scheduledPost_revealsItsDueTimeToTheOwnerOnly() {
        UUID ownerId = UUID.randomUUID();
        UUID strangerId = UUID.randomUUID();
        OffsetDateTime dueAt = OffsetDateTime.parse("2026-11-01T10:00:00Z");
        Post post =
                Post.builder()
                        .id(UUID.randomUUID())
                        .userId(ownerId)
                        .scheduledAt(dueAt)
                        .media(new ArrayList<>())
                        .build();
        when(userSummaryService.loadSummaries(anyCollection())).thenReturn(Map.of());
        when(postViewerStateService.load(any(), anyCollection())).thenReturn(PostViewerState.NONE);

        assembler.assemble(ownerId, post);
        assembler.assemble(strangerId, post);

        verify(postMapper)
                .toResponse(
                        eq(post),
                        any(),
                        any(),
                        eq(false),
                        eq(false),
                        eq(false),
                        any(),
                        eq(new PostExtras(null, List.of(), List.of(), null, dueAt)));
        verify(postMapper)
                .toResponse(
                        eq(post),
                        any(),
                        any(),
                        eq(false),
                        eq(false),
                        eq(false),
                        any(),
                        eq(NO_EXTRAS));
    }

    @Test
    void assemble_taggedMediaItem_hydratesItsTagsFromTheTagService() {
        UUID viewerId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        PostMedia media = PostMedia.builder().id(mediaId).mediaAssetId(assetId).build();
        Post post =
                Post.builder().id(UUID.randomUUID()).userId(authorId).media(List.of(media)).build();
        PostMediaTagResponse tag =
                new PostMediaTagResponse(
                        UUID.randomUUID(),
                        new UserSummaryResponse(UUID.randomUUID(), "tagged", "T", null, false),
                        new java.math.BigDecimal("0.4"),
                        new java.math.BigDecimal("0.6"),
                        TagStatus.APPROVED);
        when(postMediaAssetRepository.findAllById(Set.of(assetId))).thenReturn(List.of());
        when(userSummaryService.loadSummaries(anyCollection())).thenReturn(Map.of());
        when(postViewerStateService.load(eq(viewerId), anyCollection()))
                .thenReturn(PostViewerState.NONE);
        when(postTagService.loadForPosts(eq(viewerId), anyCollection()))
                .thenReturn(Map.of(mediaId, List.of(tag)));

        assembler.assemble(viewerId, post);

        verify(postMapper).toMediaResponse(eq(media), isNull(), eq(List.of(tag)));
    }
}
