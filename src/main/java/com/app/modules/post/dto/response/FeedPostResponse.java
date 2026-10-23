package com.app.modules.post.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.app.common.response.UserSummaryResponse;
import com.app.modules.hashtag.dto.response.HashtagSummaryResponse;
import com.app.modules.post.enums.CollaborationStatus;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.enums.PostType;
import com.app.modules.post.place.dto.response.PlaceSummaryResponse;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * API response for a post in a feed, including ordered media, trigger-maintained counters, and an
 * optional ranking score populated by the recommendation feed.
 */
@Schema(
        description =
                "Post entry in a feed, with engagement counters and an optional ranking score")
public record FeedPostResponse(
        @Schema(description = "Post identifier.") UUID id,
        @Schema(description = "Post author, the owner who can edit and delete it.")
                UserSummaryResponse author,
        @Schema(description = "Post caption.", nullable = true) String caption,
        @Schema(description = "Post type.") PostType postType,
        @Schema(description = "Lifecycle status.") PostStatus status,
        @Schema(description = "Number of likes; trigger-maintained.") int likeCount,
        @Schema(description = "Number of comments; trigger-maintained.") int commentCount,
        @Schema(description = "Number of saves; trigger-maintained.") int saveCount,
        @Schema(description = "Whether the viewer has liked this post.") boolean isLiked,
        @Schema(description = "Whether the viewer has saved this post.") boolean isSaved,
        @Schema(
                        description =
                                "Whether the viewer has already reported this post. True exactly"
                                        + " when a new report from this viewer against this post"
                                        + " would be rejected as a duplicate, so a client can"
                                        + " disable the report control instead of submitting and"
                                        + " handling the rejection. Resolved and dismissed"
                                        + " reports no longer make this true. Always false for an"
                                        + " anonymous viewer.",
                        example = "false")
                boolean hasReported,
        @Schema(description = "Number of views; updated by a background job and may lag.")
                int viewCount,
        @Schema(
                        description =
                                "The place the post is tagged at; null when it has none or an"
                                        + " administrator removed it.",
                        nullable = true)
                PlaceSummaryResponse place,
        @Schema(description = "Ordered media items.") List<PostMediaResponse> media,
        @Schema(
                        description =
                                "Hashtags the caption names, excluding deleted ones. The same set"
                                        + " and the same shape the post detail carries, so one card"
                                        + " component renders a feed post and a profile post"
                                        + " identically.")
                List<HashtagSummaryResponse> hashtags,
        @Schema(description = "Mentions stored for the caption, by user id.")
                List<PostMentionResponse> mentions,
        @Schema(description = "Co-authors who accepted; every author appears on the card.")
                List<PostCollaboratorResponse> collaborators,
        @Schema(
                        description = "The viewer's own invitation or co-authorship, or null.",
                        nullable = true)
                CollaborationStatus viewerCollaboration,
        @Schema(description = "Creation timestamp.") OffsetDateTime createdAt,
        @Schema(description = "Last update timestamp.") OffsetDateTime updatedAt,
        @Schema(
                        description =
                                "When the post first became published; the feed orders by this.",
                        nullable = true)
                OffsetDateTime publishedAt,
        @Schema(
                        description =
                                "Ranking score assigned by the recommendation feed; null in the"
                                        + " chronological following feed.",
                        nullable = true)
                Double rankingScore) {

    /**
     * Returns a copy of this response carrying the given ranking score.
     *
     * @param score ranking score assigned by the recommendation pipeline; may be null
     * @return a new instance identical to this one except for {@code rankingScore}
     */
    public FeedPostResponse withRankingScore(Double score) {
        return new FeedPostResponse(
                id,
                author,
                caption,
                postType,
                status,
                likeCount,
                commentCount,
                saveCount,
                isLiked,
                isSaved,
                hasReported,
                viewCount,
                place,
                media,
                hashtags,
                mentions,
                collaborators,
                viewerCollaboration,
                createdAt,
                updatedAt,
                publishedAt,
                score);
    }
}
