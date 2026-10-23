package com.app.modules.post.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.app.common.response.UserSummaryResponse;
import com.app.modules.hashtag.dto.response.HashtagSummaryResponse;
import com.app.modules.media.entity.MediaAsset;
import com.app.modules.post.dto.response.FeedPostResponse;
import com.app.modules.post.dto.response.PostEditHistoryResponse;
import com.app.modules.post.dto.response.PostExtras;
import com.app.modules.post.dto.response.PostMediaResponse;
import com.app.modules.post.dto.response.PostMediaTagResponse;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostEditHistory;
import com.app.modules.post.entity.PostMedia;
import com.app.modules.post.search.PostDocument;

/** Maps post entities to API response DTOs. */
@Mapper(componentModel = "spring")
public interface PostMapper {

    /**
     * Builds the post response from the entity, the separately hydrated media items, the post's
     * author, and the viewer's like/save state.
     *
     * @param post the source post; media ordering comes from the entity collection {@code @OrderBy}
     * @param media media responses already joined with their {@code media_assets} rows
     * @param author the post author's public summary, batch-resolved by the service
     * @param isLiked whether the requesting viewer has liked this post, batch-resolved by the
     *     service
     * @param isSaved whether the requesting viewer has saved this post, batch-resolved by the
     *     service
     * @param hasReported whether the requesting viewer has already reported this post,
     *     batch-resolved by the service
     * @param hashtags the post's listable hashtags, batch-resolved by the service
     * @param extras the place, mentions, co-authors and owner-only schedule, batch-resolved by the
     *     service
     * @return the post response with media, the embedded author, viewer state, and hashtags
     */
    @Mapping(source = "post.id", target = "id")
    @Mapping(source = "post.status", target = "status")
    @Mapping(source = "post.createdAt", target = "createdAt")
    @Mapping(source = "post.updatedAt", target = "updatedAt")
    @Mapping(source = "post.publishedAt", target = "publishedAt")
    @Mapping(source = "media", target = "media")
    @Mapping(source = "author", target = "author")
    @Mapping(source = "isLiked", target = "isLiked")
    @Mapping(source = "isSaved", target = "isSaved")
    @Mapping(source = "hasReported", target = "hasReported")
    @Mapping(source = "hashtags", target = "hashtags")
    @Mapping(source = "extras.place", target = "place")
    @Mapping(source = "extras.mentions", target = "mentions")
    @Mapping(source = "extras.collaborators", target = "collaborators")
    @Mapping(source = "extras.viewerCollaboration", target = "viewerCollaboration")
    @Mapping(source = "extras.scheduledAt", target = "scheduledAt")
    PostResponse toResponse(
            Post post,
            List<PostMediaResponse> media,
            UserSummaryResponse author,
            boolean isLiked,
            boolean isSaved,
            boolean hasReported,
            List<HashtagSummaryResponse> hashtags,
            PostExtras extras);

    /**
     * Builds the feed-specific post response from the entity, the separately hydrated media items,
     * the post's author, and the viewer's like/save state, with {@code rankingScore} always null
     * for the current chronological implementation.
     *
     * @param post the source post; media ordering comes from the entity collection {@code @OrderBy}
     * @param media media responses already joined with their {@code media_assets} rows
     * @param author the post author's public summary, batch-resolved by the service
     * @param isLiked whether the requesting viewer has liked this post, batch-resolved by the
     *     service
     * @param isSaved whether the requesting viewer has saved this post, batch-resolved by the
     *     service
     * @param hasReported whether the requesting viewer has already reported this post,
     *     batch-resolved by the service
     * @param hashtags the post's visible hashtags, batch-resolved by the service
     * @param extras the place, mentions and co-authors, batch-resolved by the service
     * @return the feed post response with media, the embedded author, viewer state, hashtags, and
     *     ranking score reserved as null
     */
    @Mapping(source = "post.id", target = "id")
    @Mapping(source = "post.status", target = "status")
    @Mapping(source = "post.createdAt", target = "createdAt")
    @Mapping(source = "post.updatedAt", target = "updatedAt")
    @Mapping(source = "post.publishedAt", target = "publishedAt")
    @Mapping(source = "media", target = "media")
    @Mapping(source = "author", target = "author")
    @Mapping(source = "isLiked", target = "isLiked")
    @Mapping(source = "isSaved", target = "isSaved")
    @Mapping(source = "hasReported", target = "hasReported")
    @Mapping(source = "hashtags", target = "hashtags")
    @Mapping(source = "extras.place", target = "place")
    @Mapping(source = "extras.mentions", target = "mentions")
    @Mapping(source = "extras.collaborators", target = "collaborators")
    @Mapping(source = "extras.viewerCollaboration", target = "viewerCollaboration")
    @Mapping(target = "rankingScore", ignore = true)
    FeedPostResponse toFeedResponse(
            Post post,
            List<PostMediaResponse> media,
            UserSummaryResponse author,
            boolean isLiked,
            boolean isSaved,
            boolean hasReported,
            List<HashtagSummaryResponse> hashtags,
            PostExtras extras);

    /**
     * Combines a post media row with its referenced media asset for rendering.
     *
     * @param postMedia the ordered media row
     * @param asset the media module asset providing CDN URL and dimensions
     * @param tags the tags on this item that the viewer may see
     * @return the merged media response
     */
    @Mapping(source = "postMedia.id", target = "id")
    @Mapping(source = "postMedia.mediaAssetId", target = "mediaAssetId")
    @Mapping(source = "postMedia.position", target = "position")
    @Mapping(source = "postMedia.altText", target = "altText")
    @Mapping(source = "asset.cdnUrl", target = "cdnUrl")
    @Mapping(source = "asset.mediaType", target = "mediaType")
    @Mapping(source = "asset.width", target = "width")
    @Mapping(source = "asset.height", target = "height")
    @Mapping(source = "asset.blurhash", target = "blurhash")
    @Mapping(source = "tags", target = "tags")
    PostMediaResponse toMediaResponse(
            PostMedia postMedia, MediaAsset asset, List<PostMediaTagResponse> tags);

    /**
     * Builds a caption edit history entry with the editor's public summary embedded.
     *
     * @param history the append-only audit row
     * @param editor the editing user's public summary, batch-resolved by the service
     * @return the edit history response with the embedded editor
     */
    @Mapping(source = "history.id", target = "id")
    @Mapping(source = "editor", target = "editor")
    PostEditHistoryResponse toEditHistoryResponse(
            PostEditHistory history, UserSummaryResponse editor);

    /**
     * Projects the entity to its Elasticsearch document, converting UUIDs to string form and the
     * status to its lowercase index value.
     *
     * @param post the source post
     * @param hashtagIds string-form hashtag ids resolved from {@code post_hashtags}
     * @param placeName the post's place name when its place is active, otherwise null
     * @param authorIds string-form ids of the owner and every accepted co-author
     * @return the indexable document
     */
    @Mapping(target = "id", expression = "java(post.getId().toString())")
    @Mapping(target = "userId", expression = "java(post.getUserId().toString())")
    @Mapping(
            target = "status",
            expression = "java(post.getStatus().name().toLowerCase(java.util.Locale.ROOT))")
    @Mapping(source = "hashtagIds", target = "hashtagIds")
    @Mapping(source = "post.caption", target = "caption")
    @Mapping(source = "post.createdAt", target = "createdAt")
    @Mapping(source = "post.publishedAt", target = "publishedAt")
    @Mapping(
            target = "placeId",
            expression = "java(post.getPlaceId() == null ? null : post.getPlaceId().toString())")
    @Mapping(source = "placeName", target = "placeName")
    @Mapping(source = "authorIds", target = "authorIds")
    PostDocument toDocument(
            Post post, List<String> hashtagIds, String placeName, List<String> authorIds);
}
