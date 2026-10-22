package com.app.modules.comment.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.app.common.response.UserSummaryResponse;
import com.app.modules.post.dto.response.PostMentionResponse;

import io.swagger.v3.oas.annotations.media.Schema;

/** API response representing a single comment. */
@Schema(description = "A comment on a post")
public record CommentResponse(
        @Schema(description = "Comment identifier.") UUID id,
        @Schema(description = "Post the comment belongs to.") UUID postId,
        @Schema(description = "Author of the comment.") UserSummaryResponse author,
        @Schema(
                        description = "Parent comment identifier; null for a top-level comment.",
                        nullable = true)
                UUID parentId,
        @Schema(
                        description =
                                "Top-level ancestor identifier; null for a top-level comment.",
                        nullable = true)
                UUID rootId,
        @Schema(description = "Nesting depth, 0 for top-level.") short depth,
        @Schema(description = "Comment body.") String content,
        @Schema(description = "Trigger-maintained like count.") int likeCount,
        @Schema(description = "Whether the viewer has liked this comment.") boolean isLiked,
        @Schema(
                        description =
                                "Whether the viewer has already reported this comment. True"
                                        + " exactly when a new report from this viewer against"
                                        + " this comment would be rejected as a duplicate, so a"
                                        + " client can disable the report control instead of"
                                        + " submitting and handling the rejection. Resolved and"
                                        + " dismissed reports no longer make this true. Always"
                                        + " false for an anonymous viewer.",
                        example = "false")
                boolean hasReported,
        @Schema(description = "Trigger-maintained direct reply count.") int replyCount,
        @Schema(description = "Creation timestamp.") OffsetDateTime createdAt,
        @Schema(
                        description =
                                "Timestamp of the last change to the row, including changes made"
                                        + " by the like and reply counters. Do not derive an"
                                        + " edited marker from this; use editedAt.")
                OffsetDateTime updatedAt,
        @Schema(
                        description =
                                "When the author last changed the content, or null if it has"
                                        + " never been edited. This is the only field that"
                                        + " answers whether a comment was edited.",
                        example = "2026-08-11T11:52:41.512961Z",
                        nullable = true)
                OffsetDateTime editedAt,
        @Schema(
                        description =
                                "Whether this comment was returned as part of the pinned"
                                        + " top-comments block. Only ever true on the first page of a"
                                        + " post's comment list; always false elsewhere, including on"
                                        + " single-comment responses.")
                boolean pinned,
        @Schema(
                        description =
                                "Mentions stored for the comment. Link an @handle only when it is"
                                        + " listed here, using the username given; every other @name is"
                                        + " plain text.")
                List<PostMentionResponse> mentions) {

    public CommentResponse {
        mentions = mentions == null ? List.of() : mentions;
    }

    /**
     * Returns a copy marked as part of the pinned top-comments block.
     *
     * @return the same comment with {@code pinned} set to true
     */
    public CommentResponse asPinned() {
        return new CommentResponse(
                id,
                postId,
                author,
                parentId,
                rootId,
                depth,
                content,
                likeCount,
                isLiked,
                hasReported,
                replyCount,
                createdAt,
                updatedAt,
                editedAt,
                true,
                mentions);
    }

    /**
     * Returns a copy carrying the given stored mentions.
     *
     * @param stored the mentions to show; null is read as none
     * @return the same comment with its mentions replaced
     */
    public CommentResponse withMentions(List<PostMentionResponse> stored) {
        return new CommentResponse(
                id,
                postId,
                author,
                parentId,
                rootId,
                depth,
                content,
                likeCount,
                isLiked,
                hasReported,
                replyCount,
                createdAt,
                updatedAt,
                editedAt,
                pinned,
                stored);
    }
}
