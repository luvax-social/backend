package com.app.modules.story.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import com.app.modules.story.enums.StoryType;
import com.app.modules.story.repository.StoryLikeRepository;
import com.app.modules.story.repository.StoryMediaAssetRepository;
import com.app.modules.story.repository.StoryRepository;
import com.app.modules.story.repository.StoryUserRepository;
import com.app.modules.story.repository.StoryViewRepository;
import com.app.modules.story.service.StoryVisibilityService;
import com.app.modules.users.entity.User;

@ExtendWith(MockitoExtension.class)
class StoryServiceImplTest {

    @Mock private StoryRepository storyRepository;
    @Mock private StoryViewRepository storyViewRepository;
    @Mock private StoryLikeRepository storyLikeRepository;
    @Mock private StoryUserRepository storyUserRepository;
    @Mock private StoryMediaAssetRepository storyMediaAssetRepository;
    @Mock private SocialService socialService;
    @Mock private SystemSettingService systemSettingService;
    @Mock private StoryVisibilityService storyVisibilityService;
    @Mock private StoryResponseAssembler storyResponseAssembler;

    private StoryServiceImpl service;

    private final UUID viewerId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final UUID storyId = UUID.randomUUID();
    private final UUID mediaId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service =
                new StoryServiceImpl(
                        storyRepository,
                        storyViewRepository,
                        storyLikeRepository,
                        storyUserRepository,
                        storyMediaAssetRepository,
                        socialService,
                        systemSettingService,
                        storyVisibilityService,
                        storyResponseAssembler);
    }

    private MediaAsset asset(UUID userId, MediaType type) {
        return MediaAsset.builder().id(mediaId).userId(userId).mediaType(type).build();
    }

    private Story story(UUID id, UUID userId, OffsetDateTime createdAt) {
        return Story.builder()
                .id(id)
                .userId(userId)
                .mediaAssetId(mediaId)
                .storyType(StoryType.IMAGE)
                .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(1))
                .createdAt(createdAt)
                .build();
    }

    private StoryResponse response(Story story) {
        return new StoryResponse(
                story.getId(),
                story.getUserId(),
                null,
                null,
                null,
                StoryType.IMAGE,
                null,
                null,
                null,
                null,
                null,
                false,
                story.getCreatedAt(),
                story.getExpiresAt());
    }

    @Test
    void createStory_mediaNotFound_throwsNotFound() {
        when(storyMediaAssetRepository.findById(mediaId)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () -> service.createStory(ownerId, new CreateStoryRequest(mediaId, null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.NOT_FOUND);
    }

    @Test
    void createStory_mediaNotOwned_throwsForbidden() {
        when(storyMediaAssetRepository.findById(mediaId))
                .thenReturn(Optional.of(asset(UUID.randomUUID(), MediaType.IMAGE)));

        assertThatThrownBy(
                        () -> service.createStory(ownerId, new CreateStoryRequest(mediaId, null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.STORY_FORBIDDEN);
    }

    @Test
    void createStory_videoAsset_derivesVideoType() {
        when(storyMediaAssetRepository.findById(mediaId))
                .thenReturn(Optional.of(asset(ownerId, MediaType.VIDEO)));
        when(systemSettingService.getRequiredLong("story_duration_hours")).thenReturn(24L);
        when(storyResponseAssembler.assemble(eq(ownerId), any(Story.class), eq(Set.<UUID>of())))
                .thenReturn(response(story(storyId, ownerId, null)));

        service.createStory(ownerId, new CreateStoryRequest(mediaId, "hi"));

        ArgumentCaptor<Story> captor = ArgumentCaptor.forClass(Story.class);
        verify(storyRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStoryType()).isEqualTo(StoryType.VIDEO);
        assertThat(captor.getValue().getCaption()).isEqualTo("hi");
    }

    @Test
    void createStory_valid_setsExpiryFromSystemSetting() {
        when(storyMediaAssetRepository.findById(mediaId))
                .thenReturn(Optional.of(asset(ownerId, MediaType.IMAGE)));
        when(systemSettingService.getRequiredLong("story_duration_hours")).thenReturn(48L);
        when(storyResponseAssembler.assemble(eq(ownerId), any(Story.class), eq(Set.<UUID>of())))
                .thenReturn(response(story(storyId, ownerId, null)));

        service.createStory(ownerId, new CreateStoryRequest(mediaId, null));

        ArgumentCaptor<Story> captor = ArgumentCaptor.forClass(Story.class);
        verify(storyRepository).saveAndFlush(captor.capture());
        OffsetDateTime expiresAt = captor.getValue().getExpiresAt();
        OffsetDateTime expected = OffsetDateTime.now(ZoneOffset.UTC).plusHours(48);
        assertThat(expiresAt).isBetween(expected.minusMinutes(1), expected.plusMinutes(1));
    }

    @Test
    void getStoryById_missingOrExpired_throwsNotFound() {
        when(storyRepository.findActiveById(eq(storyId), any(OffsetDateTime.class)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStoryById(viewerId, storyId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.STORY_NOT_FOUND);
    }

    @Test
    void getStoryById_notVisible_throwsForbidden() {
        Story story = story(storyId, ownerId, OffsetDateTime.now(ZoneOffset.UTC));
        when(storyRepository.findActiveById(eq(storyId), any(OffsetDateTime.class)))
                .thenReturn(Optional.of(story));
        when(storyVisibilityService.isVisibleTo(viewerId, story)).thenReturn(false);

        assertThatThrownBy(() -> service.getStoryById(viewerId, storyId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.STORY_FORBIDDEN);
    }

    @Test
    void getStoryById_nonOwnerAlreadySeen_passesSeenSetToAssembler() {
        Story story = story(storyId, ownerId, OffsetDateTime.now(ZoneOffset.UTC));
        when(storyRepository.findActiveById(eq(storyId), any(OffsetDateTime.class)))
                .thenReturn(Optional.of(story));
        when(storyVisibilityService.isVisibleTo(viewerId, story)).thenReturn(true);
        when(storyViewRepository.existsById(any())).thenReturn(true);
        when(storyResponseAssembler.assemble(viewerId, story, Set.of(storyId), Set.of()))
                .thenReturn(response(story));

        StoryResponse result = service.getStoryById(viewerId, storyId);

        assertThat(result.id()).isEqualTo(storyId);
        verify(storyResponseAssembler).assemble(viewerId, story, Set.of(storyId), Set.of());
    }

    @Test
    void listUserStories_targetMissing_throwsNotFound() {
        when(storyUserRepository.findByIdAndDeletedAtIsNull(ownerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listUserStories(viewerId, ownerId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.NOT_FOUND);
    }

    @Test
    void listUserStories_blocked_throwsNotFound() {
        // Stealth block model: a blocked target must be indistinguishable from a nonexistent one.
        when(storyUserRepository.findByIdAndDeletedAtIsNull(ownerId))
                .thenReturn(Optional.of(User.builder().id(ownerId).build()));
        when(socialService.isBlockedBetween(viewerId, ownerId)).thenReturn(true);

        assertThatThrownBy(() -> service.listUserStories(viewerId, ownerId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.NOT_FOUND);
    }

    @Test
    void listUserStories_privateNonFollower_throwsForbidden() {
        when(storyUserRepository.findByIdAndDeletedAtIsNull(ownerId))
                .thenReturn(Optional.of(User.builder().id(ownerId).isPrivate(true).build()));
        when(socialService.isBlockedBetween(viewerId, ownerId)).thenReturn(false);
        when(socialService.hasAcceptedFollow(viewerId, ownerId)).thenReturn(false);

        assertThatThrownBy(() -> service.listUserStories(viewerId, ownerId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.STORY_FORBIDDEN);
    }

    @Test
    void getStoryFeed_noActiveStories_returnsEmpty() {
        when(socialService.getAcceptedFollowingExcludingBlocks(viewerId)).thenReturn(List.of());
        when(storyRepository.findActiveByAuthors(anyList(), any(OffsetDateTime.class)))
                .thenReturn(List.of());

        assertThat(service.getStoryFeed(viewerId)).isEmpty();
    }

    @Test
    void getStoryFeed_selfPinnedFirstThenUnseenAuthors() {
        UUID authorA = UUID.randomUUID();
        UUID authorB = UUID.randomUUID();
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusHours(3);
        Story selfStory = story(UUID.randomUUID(), viewerId, base);
        Story unseenStory = story(UUID.randomUUID(), authorA, base.plusHours(1));
        Story seenStory = story(UUID.randomUUID(), authorB, base.plusHours(2));

        when(socialService.getAcceptedFollowingExcludingBlocks(viewerId))
                .thenReturn(List.of(authorA, authorB));
        when(storyRepository.findActiveByAuthors(anyList(), any(OffsetDateTime.class)))
                .thenReturn(List.of(unseenStory, seenStory, selfStory));
        when(storyViewRepository.findViewedStoryIds(eq(viewerId), anyList()))
                .thenReturn(List.of(seenStory.getId()));
        when(storyUserRepository.findAllByIdInAndDeletedAtIsNull(any()))
                .thenReturn(
                        List.of(
                                User.builder().id(authorA).username("a").build(),
                                User.builder().id(authorB).username("b").build(),
                                User.builder().id(viewerId).username("me").build()));
        when(storyResponseAssembler.assemble(eq(viewerId), anyList(), any(), any()))
                .thenAnswer(
                        invocation ->
                                invocation.<List<Story>>getArgument(1).stream()
                                        .map(this::response)
                                        .toList());

        List<StoryFeedItemResponse> feed = service.getStoryFeed(viewerId);

        assertThat(feed).hasSize(3);
        assertThat(feed.get(0).userId()).isEqualTo(viewerId);
        assertThat(feed.get(0).hasUnseen()).isFalse();
        assertThat(feed.get(1).userId()).isEqualTo(authorA);
        assertThat(feed.get(1).hasUnseen()).isTrue();
        assertThat(feed.get(2).userId()).isEqualTo(authorB);
        assertThat(feed.get(2).hasUnseen()).isFalse();
        assertThat(feed.get(2).latestStoryAt()).isEqualTo(seenStory.getCreatedAt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getStoryFeed_mutedStoryAuthors_areSubtractedFromTheQueriedAuthorsAndTheViewerStays() {
        UUID mutedAuthor = UUID.randomUUID();
        UUID keptAuthor = UUID.randomUUID();
        when(socialService.getAcceptedFollowingExcludingBlocks(viewerId))
                .thenReturn(List.of(mutedAuthor, keptAuthor));
        when(socialService.findStoryMutedAuthors(viewerId)).thenReturn(Set.of(mutedAuthor));
        when(storyRepository.findActiveByAuthors(anyList(), any(OffsetDateTime.class)))
                .thenReturn(List.of());

        service.getStoryFeed(viewerId);

        ArgumentCaptor<List<UUID>> authors = ArgumentCaptor.forClass(List.class);
        verify(storyRepository).findActiveByAuthors(authors.capture(), any(OffsetDateTime.class));
        assertThat(authors.getValue()).containsExactlyInAnyOrder(keptAuthor, viewerId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getStoryFeed_everyFollowedAuthorMuted_stillReadsTheViewersOwnEntry() {
        UUID mutedAuthor = UUID.randomUUID();
        when(socialService.getAcceptedFollowingExcludingBlocks(viewerId))
                .thenReturn(List.of(mutedAuthor));
        when(socialService.findStoryMutedAuthors(viewerId)).thenReturn(Set.of(mutedAuthor));
        when(storyRepository.findActiveByAuthors(anyList(), any(OffsetDateTime.class)))
                .thenReturn(List.of());

        service.getStoryFeed(viewerId);

        ArgumentCaptor<List<UUID>> authors = ArgumentCaptor.forClass(List.class);
        verify(storyRepository).findActiveByAuthors(authors.capture(), any(OffsetDateTime.class));
        assertThat(authors.getValue()).containsExactly(viewerId);
    }

    @Test
    void deleteStory_missing_throwsNotFound() {
        when(storyRepository.findById(storyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteStory(viewerId, storyId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.STORY_NOT_FOUND);
    }

    @Test
    void deleteStory_nonOwnerVisible_throwsForbidden() {
        Story story = story(storyId, ownerId, OffsetDateTime.now(ZoneOffset.UTC));
        when(storyRepository.findById(storyId)).thenReturn(Optional.of(story));
        when(storyVisibilityService.isVisibleTo(viewerId, story)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteStory(viewerId, storyId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.STORY_FORBIDDEN);
    }

    @Test
    void deleteStory_nonOwnerNotVisible_throwsNotFound() {
        Story story = story(storyId, ownerId, OffsetDateTime.now(ZoneOffset.UTC));
        when(storyRepository.findById(storyId)).thenReturn(Optional.of(story));
        when(storyVisibilityService.isVisibleTo(viewerId, story)).thenReturn(false);

        assertThatThrownBy(() -> service.deleteStory(viewerId, storyId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.STORY_NOT_FOUND);
    }

    @Test
    void deleteStory_owner_setsDeletedAt() {
        Story story = story(storyId, ownerId, OffsetDateTime.now(ZoneOffset.UTC));
        when(storyRepository.findById(storyId)).thenReturn(Optional.of(story));

        service.deleteStory(ownerId, storyId);

        assertThat(story.getDeletedAt()).isNotNull();
    }

    @Test
    void deleteStory_expiredOwned_stillDeletes() {
        Story story = story(storyId, ownerId, OffsetDateTime.now(ZoneOffset.UTC).minusDays(2));
        story.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
        when(storyRepository.findById(storyId)).thenReturn(Optional.of(story));

        service.deleteStory(ownerId, storyId);

        assertThat(story.getDeletedAt()).isNotNull();
    }
}
