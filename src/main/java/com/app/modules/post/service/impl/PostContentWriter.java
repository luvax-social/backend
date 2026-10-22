package com.app.modules.post.service.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.settings.service.SystemSettingService;
import com.app.modules.media.entity.MediaAsset;
import com.app.modules.media.enums.MediaType;
import com.app.modules.post.dto.request.CreatePostRequest;
import com.app.modules.post.dto.request.PostMediaInput;
import com.app.modules.post.dto.request.PostTagInput;
import com.app.modules.post.dto.request.UpdatePostContentRequest;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostMedia;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.enums.PostType;
import com.app.modules.post.place.entity.Place;
import com.app.modules.post.place.service.PlaceService;
import com.app.modules.post.repository.PostMediaAssetRepository;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.service.PostCollaborationService;
import com.app.modules.post.service.PostMentionService;
import com.app.modules.post.service.PostTagService;

/**
 * Validates composer content and writes it onto a post: media with alt text, place, people tags,
 * co-author invitations and caption mentions.
 *
 * <p>Create and draft replacement share it, so a post is checked and stored the same way however it
 * arrives. It never decides the post's status or sends a lifecycle event; the caller does.
 */
@Component
public class PostContentWriter {

    private static final String MAX_POST_MEDIA_ITEMS = "max_post_media_items";

    /**
     * Composer content after the request shape was normalised.
     *
     * @param caption the caption; may be null for a media post
     * @param postType the post type
     * @param media the media items in carousel order; empty for a text post
     * @param placeId an existing place, or null
     * @param placeName a typed place name used when no id is given, or null
     * @param tags people tagged on media items, never null
     * @param collaboratorIds invited co-authors, never null
     */
    public record Content(
            String caption,
            PostType postType,
            List<PostMediaInput> media,
            UUID placeId,
            String placeName,
            List<PostTagInput> tags,
            List<UUID> collaboratorIds) {

        public static Content from(CreatePostRequest request) {
            return new Content(
                    request.caption(),
                    request.postType(),
                    resolveMedia(request.mediaIds(), request.media()),
                    request.placeId(),
                    request.placeName(),
                    request.tags() == null ? List.of() : request.tags(),
                    request.collaboratorIds() == null ? List.of() : request.collaboratorIds());
        }

        public static Content from(UpdatePostContentRequest request) {
            return new Content(
                    request.caption(),
                    request.postType(),
                    resolveMedia(request.mediaIds(), request.media()),
                    request.placeId(),
                    request.placeName(),
                    request.tags() == null ? List.of() : request.tags(),
                    request.collaboratorIds() == null ? List.of() : request.collaboratorIds());
        }

        private static List<PostMediaInput> resolveMedia(
                List<UUID> legacyIds, List<PostMediaInput> items) {
            boolean hasLegacy = legacyIds != null && !legacyIds.isEmpty();
            boolean hasItems = items != null && !items.isEmpty();
            if (hasLegacy && hasItems) {
                throw new AppException(
                        ApiErrorCode.BAD_REQUEST, "Send either mediaIds or media, not both");
            }
            if (hasItems) {
                return items;
            }
            if (hasLegacy) {
                return legacyIds.stream().map(id -> new PostMediaInput(id, null)).toList();
            }
            return List.of();
        }
    }

    private final PostRepository postRepository;
    private final PostMediaAssetRepository postMediaAssetRepository;
    private final SystemSettingService systemSettingService;
    private final PostCaptionHashtags captionHashtags;
    private final PlaceService placeService;
    private final PostTagService postTagService;
    private final PostCollaborationService postCollaborationService;
    private final PostMentionService postMentionService;

    public PostContentWriter(
            PostRepository postRepository,
            PostMediaAssetRepository postMediaAssetRepository,
            SystemSettingService systemSettingService,
            PostCaptionHashtags captionHashtags,
            PlaceService placeService,
            PostTagService postTagService,
            PostCollaborationService postCollaborationService,
            PostMentionService postMentionService) {
        this.postRepository = postRepository;
        this.postMediaAssetRepository = postMediaAssetRepository;
        this.systemSettingService = systemSettingService;
        this.captionHashtags = captionHashtags;
        this.placeService = placeService;
        this.postTagService = postTagService;
        this.postCollaborationService = postCollaborationService;
        this.postMentionService = postMentionService;
    }

    /**
     * Creates the post row, its media and everything the content carries, in the given status.
     *
     * <p>Only {@code draft} and {@code scheduled} are valid here: a post that is to be published is
     * created as a draft and handed to the publication routine, so publication has one definition.
     * The caller has taken the draft cap into account.
     *
     * @param authorId the owner
     * @param content the validated-on-entry composer content
     * @param status {@code draft} or {@code scheduled}
     * @param scheduledAt the due time for a scheduled post, otherwise null
     * @return the persisted post
     */
    @Transactional
    public Post create(
            UUID authorId,
            Content content,
            PostStatus status,
            java.time.OffsetDateTime scheduledAt) {
        validateShape(content);
        captionHashtags.rejectBanned(content.caption());
        Place place =
                placeService.resolveForPostWrite(authorId, content.placeId(), content.placeName());
        Map<UUID, MediaAsset> assets = requireOwnedAssets(authorId, content);
        validateMediaCardinality(content, assets);

        Post post =
                Post.builder()
                        .userId(authorId)
                        .caption(content.caption())
                        .postType(content.postType())
                        .status(status)
                        .scheduledAt(scheduledAt)
                        .placeId(place == null ? null : place.getId())
                        .build();
        for (int i = 0; i < content.media().size(); i++) {
            PostMediaInput item = content.media().get(i);
            post.getMedia()
                    .add(
                            PostMedia.builder()
                                    .post(post)
                                    .mediaAssetId(item.mediaId())
                                    .position((short) i)
                                    .altText(normaliseAltText(item.altText()))
                                    .build());
        }
        // Flushed so the generated ids and timestamps exist before tags and invitations refer to
        // the media rows.
        postRepository.saveAndFlush(post);
        applyExtras(post, content);
        return post;
    }

    /**
     * Replaces the content of a draft or scheduled post.
     *
     * <p>Media rows are updated in place by position, so a media row keeps its id and the tags on
     * it survive a reorder-free edit; rows past the new length are removed and extra ones added.
     *
     * @param post the post, locked by the caller; draft or scheduled
     * @param content the complete new content
     */
    @Transactional
    public void replace(Post post, Content content) {
        UUID ownerId = post.getUserId();
        validateShape(content);
        captionHashtags.rejectBanned(content.caption());
        Place place =
                placeService.resolveForPostWrite(ownerId, content.placeId(), content.placeName());
        Map<UUID, MediaAsset> assets = requireOwnedAssets(ownerId, content);
        validateMediaCardinality(content, assets);

        post.setCaption(content.caption());
        post.setPostType(content.postType());
        post.setPlaceId(place == null ? null : place.getId());
        List<PostMedia> rows = post.getMedia();
        int wanted = content.media().size();
        for (int i = 0; i < wanted; i++) {
            PostMediaInput item = content.media().get(i);
            if (i < rows.size()) {
                PostMedia row = rows.get(i);
                row.setMediaAssetId(item.mediaId());
                row.setAltText(normaliseAltText(item.altText()));
            } else {
                rows.add(
                        PostMedia.builder()
                                .post(post)
                                .mediaAssetId(item.mediaId())
                                .position((short) i)
                                .altText(normaliseAltText(item.altText()))
                                .build());
            }
        }
        while (rows.size() > wanted) {
            rows.remove(rows.size() - 1);
        }
        postRepository.saveAndFlush(post);
        applyExtras(post, content);
    }

    /**
     * Normalises alt text for storage: trimmed, and null when blank.
     *
     * @param altText the text as typed, or null
     * @return the text to store
     */
    public static String normaliseAltText(String altText) {
        if (altText == null) {
            return null;
        }
        String trimmed = altText.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void applyExtras(Post post, Content content) {
        postTagService.replaceTags(post, content.tags());
        postCollaborationService.replaceInvites(post, content.collaboratorIds());
        postMentionService.replaceCaptionMentions(post);
    }

    private void validateShape(Content content) {
        if (content.postType() == PostType.TEXT) {
            if (content.caption() == null || content.caption().isBlank()) {
                throw new AppException(
                        ApiErrorCode.BAD_REQUEST, "Text posts require a non-blank caption");
            }
            if (!content.media().isEmpty()) {
                throw new AppException(
                        ApiErrorCode.BAD_REQUEST, "Text posts must not include media");
            }
            if (!content.tags().isEmpty()) {
                throw new AppException(
                        ApiErrorCode.BAD_REQUEST, "Text posts have no media to tag people on");
            }
            return;
        }
        if (content.media().isEmpty()) {
            throw new AppException(
                    ApiErrorCode.BAD_REQUEST, "Media posts require at least one media item");
        }
        List<UUID> ids = content.media().stream().map(PostMediaInput::mediaId).toList();
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new AppException(ApiErrorCode.BAD_REQUEST, "Duplicate media ids");
        }
    }

    private Map<UUID, MediaAsset> requireOwnedAssets(UUID ownerId, Content content) {
        List<UUID> ids = new ArrayList<>();
        for (PostMediaInput item : content.media()) {
            ids.add(item.mediaId());
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, MediaAsset> assets =
                postMediaAssetRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(MediaAsset::getId, a -> a));
        if (assets.size() != ids.size()) {
            throw new AppException(ApiErrorCode.NOT_FOUND, "Media asset not found");
        }
        for (MediaAsset asset : assets.values()) {
            if (!asset.getUserId().equals(ownerId)) {
                throw new AppException(
                        ApiErrorCode.POST_FORBIDDEN, "Media asset not owned by author");
            }
        }
        return assets;
    }

    private void validateMediaCardinality(Content content, Map<UUID, MediaAsset> assets) {
        if (content.postType() == PostType.TEXT) {
            return;
        }
        int count = content.media().size();
        if (content.postType() == PostType.CAROUSEL) {
            if (count < 2) {
                throw new AppException(
                        ApiErrorCode.BAD_REQUEST, "Carousel posts require at least 2 media items");
            }
            long maxItems = systemSettingService.getRequiredLong(MAX_POST_MEDIA_ITEMS);
            if (count > maxItems) {
                throw new AppException(
                        ApiErrorCode.BAD_REQUEST,
                        "Carousel exceeds the maximum of " + maxItems + " media items");
            }
            return;
        }
        if (count != 1) {
            throw new AppException(
                    ApiErrorCode.BAD_REQUEST, "Image and video posts require exactly 1 media item");
        }
        MediaAsset asset = assets.get(content.media().get(0).mediaId());
        MediaType expected =
                content.postType() == PostType.IMAGE ? MediaType.IMAGE : MediaType.VIDEO;
        if (asset.getMediaType() != expected) {
            throw new AppException(
                    ApiErrorCode.BAD_REQUEST, "Media asset type does not match the post type");
        }
    }
}
