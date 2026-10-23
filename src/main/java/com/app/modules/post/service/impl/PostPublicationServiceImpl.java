package com.app.modules.post.service.impl;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
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
import com.app.modules.post.service.PostPublicationService;
import com.app.modules.post.service.PostTagService;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserStatus;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class PostPublicationServiceImpl implements PostPublicationService {

    private final PostRepository postRepository;
    private final PostUserRepository postUserRepository;
    private final PostMediaAssetRepository postMediaAssetRepository;
    private final PostCaptionHashtags captionHashtags;
    private final PlaceService placeService;
    private final PostMentionService postMentionService;
    private final PostTagService postTagService;
    private final PostCollaborationService postCollaborationService;
    private final PostIndexEventPublisher postIndexEventPublisher;
    private final OutboxService outboxService;

    public PostPublicationServiceImpl(
            PostRepository postRepository,
            PostUserRepository postUserRepository,
            PostMediaAssetRepository postMediaAssetRepository,
            PostCaptionHashtags captionHashtags,
            PlaceService placeService,
            PostMentionService postMentionService,
            PostTagService postTagService,
            PostCollaborationService postCollaborationService,
            PostIndexEventPublisher postIndexEventPublisher,
            OutboxService outboxService) {
        this.postRepository = postRepository;
        this.postUserRepository = postUserRepository;
        this.postMediaAssetRepository = postMediaAssetRepository;
        this.captionHashtags = captionHashtags;
        this.placeService = placeService;
        this.postMentionService = postMentionService;
        this.postTagService = postTagService;
        this.postCollaborationService = postCollaborationService;
        this.postIndexEventPublisher = postIndexEventPublisher;
        this.outboxService = outboxService;
    }

    @Override
    public void validate(Post post) {
        PostStatus status = post.getStatus();
        if (status != PostStatus.DRAFT
                && status != PostStatus.SCHEDULED
                && status != PostStatus.ARCHIVED) {
            throw new PostPublicationException(
                    Reason.INVALID_STATE, ApiErrorCode.POST_STATE_CONFLICT);
        }
        User owner = postUserRepository.findByIdAndDeletedAtIsNull(post.getUserId()).orElse(null);
        if (owner == null || owner.getStatus() != UserStatus.ACTIVE) {
            throw new PostPublicationException(
                    Reason.OWNER_UNAVAILABLE, ApiErrorCode.POST_FORBIDDEN);
        }
        requireMediaIntact(post);
        List<String> banned = captionHashtags.bannedNames(post.getCaption());
        if (!banned.isEmpty()) {
            throw new PostPublicationException(
                    Reason.BANNED_HASHTAG,
                    ApiErrorCode.POST_BANNED_HASHTAG,
                    Map.of("bannedTags", banned));
        }
        if (post.getPlaceId() != null) {
            try {
                placeService.assertPublishable(post.getPlaceId());
            } catch (com.app.common.exception.AppException e) {
                throw new PostPublicationException(
                        Reason.PLACE_UNAVAILABLE, ApiErrorCode.POST_PLACE_UNAVAILABLE);
            }
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(Post post, Source source) {
        validate(post);
        boolean firstPublication = post.getPublishedAt() == null;
        OffsetDateTime scheduledAt = post.getScheduledAt();
        post.setStatus(PostStatus.PUBLISHED);
        post.setScheduledAt(null);
        // Flushed so the trigger-written published_at is read back before the events carry it.
        postRepository.saveAndFlush(post);
        captionHashtags.refresh(post.getId(), post.getCaption());

        MentionChange mentions = null;
        if (firstPublication) {
            mentions = postMentionService.replaceCaptionMentions(post);
            postTagService.announceOnPublication(post);
            postCollaborationService.announceOnPublication(post);
        }
        postIndexEventPublisher.enqueueUpsert(post);
        if (firstPublication) {
            enqueuePublished(post, source, scheduledAt);
            postMentionService.announce(post, mentions.stored(), mentions.notifiable());
        }
        log.info(
                "Post published: postId={}, source={}, firstPublication={}",
                post.getId(),
                source,
                firstPublication);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void failSchedule(Post post, PostPublicationException failure) {
        post.setStatus(PostStatus.DRAFT);
        post.setScheduledAt(null);
        postRepository.saveAndFlush(post);
        Map<String, Object> data = new HashMap<>();
        data.put("postId", post.getId().toString());
        data.put("reason", failure.getReason().name().toLowerCase(java.util.Locale.ROOT));
        outboxService.enqueue(
                PostEventTypes.POST_SCHEDULE_FAILED_V1,
                PostEventTypes.POST_SCHEDULE_FAILED_V1,
                "post",
                post.getId(),
                post.getUserId(),
                data);
        log.warn(
                "Scheduled post returned to drafts: postId={}, reason={}",
                post.getId(),
                failure.getReason());
    }

    // Every media row must still resolve to an asset the owner owns; a deleted or reassigned asset
    // would otherwise publish a post whose image no longer loads.
    private void requireMediaIntact(Post post) {
        List<PostMedia> media = post.getMedia();
        if (media.isEmpty()) {
            return;
        }
        Set<UUID> assetIds =
                media.stream().map(PostMedia::getMediaAssetId).collect(Collectors.toSet());
        Map<UUID, MediaAsset> assets =
                postMediaAssetRepository.findAllById(assetIds).stream()
                        .collect(Collectors.toMap(MediaAsset::getId, asset -> asset));
        for (UUID assetId : assetIds) {
            MediaAsset asset = assets.get(assetId);
            if (asset == null || !asset.getUserId().equals(post.getUserId())) {
                throw new PostPublicationException(
                        Reason.MEDIA_UNAVAILABLE, ApiErrorCode.NOT_FOUND);
            }
        }
    }

    private void enqueuePublished(Post post, Source source, OffsetDateTime scheduledAt) {
        Map<String, Object> data = new HashMap<>();
        data.put("postId", post.getId().toString());
        data.put("source", source.name().toLowerCase(java.util.Locale.ROOT));
        if (scheduledAt != null && post.getPublishedAt() != null) {
            data.put(
                    "lagSeconds",
                    Math.max(0, Duration.between(scheduledAt, post.getPublishedAt()).getSeconds()));
        }
        outboxService.enqueue(
                PostEventTypes.POST_PUBLISHED_V1,
                PostEventTypes.POST_PUBLISHED_V1,
                "post",
                post.getId(),
                post.getUserId(),
                data);
    }
}
