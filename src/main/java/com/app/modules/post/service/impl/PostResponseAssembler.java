package com.app.modules.post.service.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.app.common.response.UserSummaryResponse;
import com.app.modules.hashtag.dto.response.HashtagSummaryResponse;
import com.app.modules.hashtag.service.HashtagService;
import com.app.modules.media.entity.MediaAsset;
import com.app.modules.post.dto.response.FeedPostResponse;
import com.app.modules.post.dto.response.PostExtras;
import com.app.modules.post.dto.response.PostMediaResponse;
import com.app.modules.post.dto.response.PostMediaTagResponse;
import com.app.modules.post.dto.response.PostMentionResponse;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostMedia;
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

/**
 * Assembles {@link PostResponse} DTOs by joining posts with their {@code media_assets} rows, author
 * {@code users} rows, and the requesting viewer's like/save state.
 *
 * <p>Shared by the post, save, and search services so media, author, and viewer-state hydration
 * stay in one place. Everything per post that is not on the {@code posts} row, the place, the
 * mentions, the co-authors and the tags, is loaded for the whole page at once.
 */
@Component
public class PostResponseAssembler {

    private final PostMediaAssetRepository postMediaAssetRepository;
    private final UserSummaryService userSummaryService;
    private final PostViewerStateService postViewerStateService;
    private final HashtagService hashtagService;
    private final PlaceService placeService;
    private final PostMentionService postMentionService;
    private final PostTagService postTagService;
    private final PostCollaborationService postCollaborationService;
    private final PostMapper postMapper;

    public PostResponseAssembler(
            PostMediaAssetRepository postMediaAssetRepository,
            UserSummaryService userSummaryService,
            PostViewerStateService postViewerStateService,
            HashtagService hashtagService,
            PlaceService placeService,
            PostMentionService postMentionService,
            PostTagService postTagService,
            PostCollaborationService postCollaborationService,
            PostMapper postMapper) {
        this.postMediaAssetRepository = postMediaAssetRepository;
        this.userSummaryService = userSummaryService;
        this.postViewerStateService = postViewerStateService;
        this.hashtagService = hashtagService;
        this.placeService = placeService;
        this.postMentionService = postMentionService;
        this.postTagService = postTagService;
        this.postCollaborationService = postCollaborationService;
        this.postMapper = postMapper;
    }

    public PostResponse assemble(UUID viewerId, Post post) {
        return assemble(viewerId, List.of(post)).get(0);
    }

    public List<PostResponse> assemble(UUID viewerId, List<Post> posts) {
        return build(viewerId, posts, postMapper::toResponse);
    }

    /**
     * Assembles {@link FeedPostResponse} DTOs for the following feed.
     *
     * <p>Batches every lookup identically to {@link #assemble(UUID, List)}, so the statement count
     * for a page of twenty is the same as for a page of one.
     *
     * @param viewerId the requesting viewer, whose like/save state is batch-resolved
     * @param posts posts to assemble; must not be empty
     * @return feed post responses in the same order as the input list
     */
    public List<FeedPostResponse> assembleFeed(UUID viewerId, List<Post> posts) {
        return build(viewerId, posts, postMapper::toFeedResponse);
    }

    private interface ResponseFactory<T> {
        T create(
                Post post,
                List<PostMediaResponse> media,
                UserSummaryResponse author,
                boolean isLiked,
                boolean isSaved,
                boolean hasReported,
                List<HashtagSummaryResponse> hashtags,
                PostExtras extras);
    }

    private <T> List<T> build(UUID viewerId, List<Post> posts, ResponseFactory<T> factory) {
        // Single batched asset lookup avoids one media_assets query per post on list pages.
        Set<UUID> assetIds =
                posts.stream()
                        .flatMap(p -> p.getMedia().stream())
                        .map(PostMedia::getMediaAssetId)
                        .collect(Collectors.toSet());
        Map<UUID, MediaAsset> assets =
                assetIds.isEmpty()
                        ? Map.of()
                        : postMediaAssetRepository.findAllById(assetIds).stream()
                                .collect(Collectors.toMap(MediaAsset::getId, a -> a));
        Map<UUID, UserSummaryResponse> authors = batchFetchAuthors(posts);
        PostViewerState viewerState = batchFetchViewerState(viewerId, posts);
        Map<UUID, List<HashtagSummaryResponse>> hashtags = batchFetchHashtags(posts);
        Map<UUID, PlaceSummaryResponse> places = batchFetchPlaces(posts);
        List<UUID> postIds = posts.stream().map(Post::getId).toList();
        Map<UUID, List<PostMentionResponse>> mentions =
                postMentionService.loadForPosts(viewerId, postIds);
        Map<UUID, List<PostMediaTagResponse>> tags = postTagService.loadForPosts(viewerId, posts);
        Map<UUID, CollaborationView> collaboration =
                postCollaborationService.loadForPosts(viewerId, posts);

        List<T> result = new ArrayList<>(posts.size());
        for (Post post : posts) {
            List<PostMediaResponse> media =
                    post.getMedia().stream()
                            .map(
                                    pm ->
                                            postMapper.toMediaResponse(
                                                    pm,
                                                    assets.get(pm.getMediaAssetId()),
                                                    tags.getOrDefault(pm.getId(), List.of())))
                            .toList();
            CollaborationView view = collaboration.get(post.getId());
            PostExtras extras =
                    new PostExtras(
                            post.getPlaceId() == null ? null : places.get(post.getPlaceId()),
                            mentions.getOrDefault(post.getId(), List.of()),
                            view == null ? List.of() : view.collaborators(),
                            view == null ? null : view.viewerStatus(),
                            // The due time is the owner's alone; everyone else reads a published
                            // post, which has none.
                            viewerId.equals(post.getUserId()) ? post.getScheduledAt() : null);
            result.add(
                    factory.create(
                            post,
                            media,
                            authors.get(post.getUserId()),
                            viewerState.isLiked(post.getId()),
                            viewerState.isSaved(post.getId()),
                            viewerState.hasReported(post.getId()),
                            hashtags.getOrDefault(post.getId(), List.of()),
                            extras));
        }
        return result;
    }

    // One batched summary lookup for every author on the page; a soft-deleted author resolves to a
    // placeholder so a post never renders without an author object.
    private Map<UUID, UserSummaryResponse> batchFetchAuthors(List<Post> posts) {
        return userSummaryService.loadSummaries(posts.stream().map(Post::getUserId).toList());
    }

    // One batched like/save/report lookup for every post on the page instead of one probe per row.
    private PostViewerState batchFetchViewerState(UUID viewerId, List<Post> posts) {
        return postViewerStateService.load(viewerId, posts.stream().map(Post::getId).toList());
    }

    // One batched hashtag lookup for every post on the page. Deleted hashtags are filtered out by
    // the query rather than here, so the omission cannot be forgotten at one call site.
    private Map<UUID, List<HashtagSummaryResponse>> batchFetchHashtags(List<Post> posts) {
        return hashtagService.getVisibleHashtagsForPosts(posts.stream().map(Post::getId).toList());
    }

    // A banned or deleted place is left out by the lookup, so no surface shows it.
    private Map<UUID, PlaceSummaryResponse> batchFetchPlaces(List<Post> posts) {
        Set<UUID> placeIds = new HashSet<>();
        for (Post post : posts) {
            if (post.getPlaceId() != null) {
                placeIds.add(post.getPlaceId());
            }
        }
        return placeIds.isEmpty() ? Map.of() : placeService.loadActiveSummaries(placeIds);
    }
}
