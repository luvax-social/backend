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

/** API response for a post, including ordered media and trigger-maintained counters. */
@Schema(description = "Post with ordered media and engagement counters")
public record PostResponse(
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
                                "The place the post is tagged at. Null when the post has none, and"
                                        + " also when an administrator removed the place, so a"
                                        + " removed place shows on no surface.",
                        nullable = true)
                PlaceSummaryResponse place,
        @Schema(description = "Ordered media items.") List<PostMediaResponse> media,
        @Schema(
                        description =
                                "Hashtags this post is associated with, ordered by name. A hashtag"
                                        + " an administrator has deleted is omitted, while the"
                                        + " caption keeps its literal #tag text, so a client"
                                        + " renders that text without linking it. A banned hashtag"
                                        + " is still listed: banning hides the tag's own surfaces,"
                                        + " not the posts that used it. Empty for a post that is not"
                                        + " published, because associations exist only while a post"
                                        + " is published.")
                List<HashtagSummaryResponse> hashtags,
        @Schema(
                        description =
                                "Mentions stored for the caption. Link an @handle only when it is"
                                        + " listed here, using the username given.")
                List<PostMentionResponse> mentions,
        @Schema(
                        description =
                                "Co-authors who accepted, and for the owner or the invitee the"
                                        + " invitations still waiting.")
                List<PostCollaboratorResponse> collaborators,
        @Schema(
                        description =
                                "The viewer's own invitation or co-authorship on this post, or null.",
                        nullable = true)
                CollaborationStatus viewerCollaboration,
        @Schema(description = "Creation timestamp.") OffsetDateTime createdAt,
        @Schema(description = "Last update timestamp.") OffsetDateTime updatedAt,
        @Schema(
                        description =
                                "When the post first became published; null while it has never been"
                                        + " published. Every ordering of posts uses this.",
                        nullable = true)
                OffsetDateTime publishedAt,
        @Schema(
                        description = "When a scheduled post is due; present only for the owner.",
                        nullable = true)
                OffsetDateTime scheduledAt) {}
