package com.app.modules.story.service.impl;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.settings.service.SystemSettingService;
import com.app.modules.media.entity.MediaAsset;
import com.app.modules.media.enums.MediaType;
import com.app.modules.social.service.SocialService;
import com.app.modules.story.dto.request.CreateStoryRequest;
import com.app.modules.story.dto.response.StoryFeedItemResponse;
import com.app.modules.story.dto.response.StoryResponse;
import com.app.modules.story.entity.Story;
import com.app.modules.story.entity.StoryViewId;
import com.app.modules.story.enums.StoryType;
import com.app.modules.story.repository.StoryLikeRepository;
import com.app.modules.story.repository.StoryMediaAssetRepository;
import com.app.modules.story.repository.StoryRepository;
import com.app.modules.story.repository.StoryUserRepository;
import com.app.modules.story.repository.StoryViewRepository;
import com.app.modules.story.service.StoryService;
import com.app.modules.story.service.StoryVisibilityService;
import com.app.modules.users.entity.User;

@Service
public class StoryServiceImpl implements StoryService {

    /** Upper bound on one discovery page, mirroring the endpoint's own cap. */
    private static final int MAX_DISCOVERY_LIMIT = 20;

    private static final Logger log = LoggerFactory.getLogger(StoryServiceImpl.class);

    private static final String STORY_DURATION_HOURS = "story_duration_hours";

    private final StoryRepository storyRepository;
    private final StoryViewRepository storyViewRepository;
    private final StoryLikeRepository storyLikeRepository;
    private final StoryUserRepository storyUserRepository;
    private final StoryMediaAssetRepository storyMediaAssetRepository;
    private final SocialService socialService;
    private final SystemSettingService systemSettingService;
    private final StoryVisibilityService storyVisibilityService;
    private final StoryResponseAssembler storyResponseAssembler;

    public StoryServiceImpl(
            StoryRepository storyRepository,
            StoryViewRepository storyViewRepository,
            StoryLikeRepository storyLikeRepository,
            StoryUserRepository storyUserRepository,
            StoryMediaAssetRepository storyMediaAssetRepository,
            SocialService socialService,
            SystemSettingService systemSettingService,
            StoryVisibilityService storyVisibilityService,
            StoryResponseAssembler storyResponseAssembler) {
        this.storyRepository = storyRepository;
        this.storyViewRepository = storyViewRepository;
        this.storyLikeRepository = storyLikeRepository;
        this.storyUserRepository = storyUserRepository;
        this.storyMediaAssetRepository = storyMediaAssetRepository;
        this.socialService = socialService;
        this.systemSettingService = systemSettingService;
        this.storyVisibilityService = storyVisibilityService;
        this.storyResponseAssembler = storyResponseAssembler;
    }

    @Override
    @Transactional
    public StoryResponse createStory(UUID authorId, CreateStoryRequest request) {
        MediaAsset asset =
                storyMediaAssetRepository
                        .findById(request.mediaId())
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ApiErrorCode.NOT_FOUND, "Media asset not found"));
        if (!asset.getUserId().equals(authorId)) {
            throw new AppException(ApiErrorCode.STORY_FORBIDDEN, "Media asset not owned by author");
        }
        // Deriving the type from the asset removes the client-supplied type mismatch error class.
        StoryType storyType =
                asset.getMediaType() == MediaType.VIDEO ? StoryType.VIDEO : StoryType.IMAGE;
        OffsetDateTime expiresAt =
                OffsetDateTime.now(ZoneOffset.UTC)
                        .plusHours(systemSettingService.getRequiredLong(STORY_DURATION_HOURS));
        Story story =
                Story.builder()
                        .userId(authorId)
                        .mediaAssetId(asset.getId())
                        .storyType(storyType)
                        .caption(request.caption())
                        .expiresAt(expiresAt)
                        .build();
        // Flush so the DB-assigned id and @CreationTimestamp createdAt are populated in the
        // response.
        storyRepository.saveAndFlush(story);
        log.info("Story created: storyId={}, type={}", story.getId(), story.getStoryType());
        return storyResponseAssembler.assemble(authorId, story, Set.of());
    }

    @Override
    @Transactional(readOnly = true)
    public StoryResponse getStoryById(UUID viewerId, UUID storyId) {
        Story story = fetchActiveStory(storyId);
        if (!storyVisibilityService.isVisibleTo(viewerId, story)) {
            throw new AppException(ApiErrorCode.STORY_FORBIDDEN);
        }
        Set<UUID> seenIds =
                !viewerId.equals(story.getUserId())
                                && storyViewRepository.existsById(
                                        new StoryViewId(storyId, viewerId))
                        ? Set.of(storyId)
                        : Set.of();
        Set<UUID> likedIds = likedStoryIds(viewerId, List.of(story));
        return storyResponseAssembler.assemble(viewerId, story, seenIds, likedIds);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoryResponse> listUserStories(UUID viewerId, UUID targetUserId) {
        User target =
                storyUserRepository
                        .findByIdAndDeletedAtIsNull(targetUserId)
                        .orElseThrow(
                                () -> new AppException(ApiErrorCode.NOT_FOUND, "User not found"));
        // Stealth block model: matches assemblePublicProfile's reference behaviour - a block in
        // either direction must be indistinguishable from targetUserId not existing.
        if (socialService.isBlockedBetween(viewerId, targetUserId)) {
            throw new AppException(ApiErrorCode.NOT_FOUND, "User not found");
        }
        boolean isOwner = viewerId.equals(targetUserId);
        if (target.isPrivate()
                && !isOwner
                && !socialService.hasAcceptedFollow(viewerId, targetUserId)) {
            throw new AppException(ApiErrorCode.STORY_FORBIDDEN);
        }
        List<Story> stories =
                storyRepository.findActiveByUser(targetUserId, OffsetDateTime.now(ZoneOffset.UTC));
        if (stories.isEmpty()) {
            return List.of();
        }
        return storyResponseAssembler.assemble(
                viewerId,
                stories,
                seenStoryIds(viewerId, stories),
                likedStoryIds(viewerId, stories));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoryFeedItemResponse> getStoryFeed(UUID viewerId) {
        List<UUID> authorIds =
                new ArrayList<>(socialService.getAcceptedFollowingExcludingBlocks(viewerId));
        // The viewer's own tray entry is served from the same query and pinned first below.
        authorIds.add(viewerId);
        List<Story> stories =
                storyRepository.findActiveByAuthors(authorIds, OffsetDateTime.now(ZoneOffset.UTC));
        if (stories.isEmpty()) {
            return List.of();
        }
        List<StoryFeedItemResponse> items = buildTrayEntries(viewerId, stories);
        items.sort(
                Comparator.comparing((StoryFeedItemResponse i) -> !i.userId().equals(viewerId))
                        .thenComparing(i -> !i.hasUnseen())
                        .thenComparing(
                                StoryFeedItemResponse::latestStoryAt, Comparator.reverseOrder()));
        return items;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoryFeedItemResponse> discoverStories(UUID viewerId, int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_DISCOVERY_LIMIT));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<UUID> authorIds = storyRepository.findDiscoverableAuthors(viewerId, now, bounded);
        if (authorIds.isEmpty()) {
            return List.of();
        }
        List<Story> stories = storyRepository.findActiveByAuthors(authorIds, now);
        if (stories.isEmpty()) {
            return List.of();
        }
        // Suggestion rank order, not recency. The reason an account is here at all is that it was
        // suggested, so the ranking that produced it is the one that should survive to the rail;
        // sorting by newest story instead would discard it.
        Map<UUID, Integer> rank = new HashMap<>();
        for (int i = 0; i < authorIds.size(); i++) {
            rank.put(authorIds.get(i), i);
        }
        List<StoryFeedItemResponse> items = buildTrayEntries(viewerId, stories);
        items.sort(
                Comparator.comparingInt(
                        item -> rank.getOrDefault(item.userId(), Integer.MAX_VALUE)));
        return items;
    }

    /**
     * Groups stories by author into tray entries, unsorted.
     *
     * <p>Shared by the follow-based tray and discovery, which differ only in which authors they
     * choose and how the result is ordered. The viewer's own entry can never appear in a discovery
     * result, so the self-entry branch below is reached only from the tray.
     */
    private List<StoryFeedItemResponse> buildTrayEntries(UUID viewerId, List<Story> stories) {
        Set<UUID> seenIds = seenStoryIds(viewerId, stories);
        Set<UUID> likedIds = likedStoryIds(viewerId, stories);
        // The query orders by (userId, createdAt), so insertion order keeps playback order intact.
        Map<UUID, List<Story>> byAuthor =
                stories.stream()
                        .collect(
                                Collectors.groupingBy(
                                        Story::getUserId, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, User> authors =
                storyUserRepository.findAllByIdInAndDeletedAtIsNull(byAuthor.keySet()).stream()
                        .collect(Collectors.toMap(User::getId, u -> u));
        Map<UUID, StoryResponse> responsesById =
                storyResponseAssembler.assemble(viewerId, stories, seenIds, likedIds).stream()
                        .collect(Collectors.toMap(StoryResponse::id, r -> r));
        List<StoryFeedItemResponse> items = new ArrayList<>(byAuthor.size());
        for (Map.Entry<UUID, List<Story>> entry : byAuthor.entrySet()) {
            User author = authors.get(entry.getKey());
            // A soft-deleted owner drops out of the tray entirely.
            if (author == null) {
                continue;
            }
            List<Story> group = entry.getValue();
            boolean self = entry.getKey().equals(viewerId);
            // Owner views never create story_views rows, so the self entry cannot be "unseen".
            boolean hasUnseen = !self && group.stream().anyMatch(s -> !seenIds.contains(s.getId()));
            OffsetDateTime latestStoryAt =
                    group.stream()
                            .map(Story::getCreatedAt)
                            .max(Comparator.naturalOrder())
                            .orElse(null);
            items.add(
                    new StoryFeedItemResponse(
                            author.getId(),
                            author.getUsername(),
                            author.isVerified(),
                            author.getVerifiedCategory(),
                            author.getDisplayName(),
                            author.getAvatarUrl(),
                            hasUnseen,
                            latestStoryAt,
                            group.stream().map(s -> responsesById.get(s.getId())).toList()));
        }
        return items;
    }

    @Override
    @Transactional
    public void deleteStory(UUID requesterId, UUID storyId) {
        // Expiry is deliberately not filtered here: expired-but-live stories must stay deletable
        // so they can become cleanup-job targets (DATA_RULES §3B).
        Story story =
                storyRepository
                        .findById(storyId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.STORY_NOT_FOUND));
        if (!requesterId.equals(story.getUserId())) {
            // Non-owners who cannot even see the story must not learn that it exists.
            if (!storyVisibilityService.isVisibleTo(requesterId, story)) {
                throw new AppException(ApiErrorCode.STORY_NOT_FOUND);
            }
            throw new AppException(ApiErrorCode.STORY_FORBIDDEN);
        }
        story.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        log.info("Story soft-deleted: storyId={}", storyId);
    }

    private Story fetchActiveStory(UUID storyId) {
        return storyRepository
                .findActiveById(storyId, OffsetDateTime.now(ZoneOffset.UTC))
                .orElseThrow(() -> new AppException(ApiErrorCode.STORY_NOT_FOUND));
    }

    private Set<UUID> seenStoryIds(UUID viewerId, List<Story> stories) {
        List<UUID> candidateIds =
                stories.stream()
                        .filter(s -> !s.getUserId().equals(viewerId))
                        .map(Story::getId)
                        .toList();
        return candidateIds.isEmpty()
                ? Set.of()
                : new HashSet<>(storyViewRepository.findViewedStoryIds(viewerId, candidateIds));
    }

    // Unlike seenStoryIds, the owner's own stories stay candidates: self-like is permitted, so an
    // owner who liked their own story must see liked=true on it too.
    private Set<UUID> likedStoryIds(UUID viewerId, List<Story> stories) {
        List<UUID> storyIds = stories.stream().map(Story::getId).toList();
        return storyIds.isEmpty()
                ? Set.of()
                : new HashSet<>(storyLikeRepository.findLikedStoryIds(viewerId, storyIds));
    }
}
