package com.app.modules.comment.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.app.common.response.UserSummaryResponse;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A hidden comment as the owner of its post sees it in the review list: every {@link
 * CommentResponse} field plus the reason it is hidden.
 *
 * <p>Only the post owner is ever sent this shape. The author of a hidden comment is deliberately
 * not told it is hidden, so {@code hiddenReason} appears on no other response.
 */
@Schema(description = "A hidden comment in the post owner's review list")
public record HiddenCommentResponse(
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
        @Schema(description = "Whether the viewer has already reported this comment.")
                boolean hasReported,
        @Schema(description = "Trigger-maintained direct reply count.") int replyCount,
        @Schema(description = "Creation timestamp.") OffsetDateTime createdAt,
        @Schema(description = "Timestamp of the last change to the row.") OffsetDateTime updatedAt,
        @Schema(
                        description =
                                "When the author last changed the content, or null if it has"
                                        + " never been edited.",
                        nullable = true)
                OffsetDateTime editedAt,
        @Schema(description = "Always false: a review list has no pinned block.") boolean pinned,
        @Schema(
                        description =
                                "Why the comment is hidden: `hidden_words` when it matched the"
                                        + " owner's Hidden Words, `limited_interaction` when it"
                                        + " was written under the owner's interaction limit, or"
                                        + " `hidden_parent` when it is hidden only because the"
                                        + " comment it answers is hidden.",
                        allowableValues = {"hidden_words", "limited_interaction", "hidden_parent"})
                String hiddenReason) {

    /**
     * Adds the hidden reason to an assembled comment response.
     *
     * @param comment the comment as any viewer would receive it
     * @param hiddenReason the stored {@code hidden_reason} of the comment
     * @return the review-list shape of the same comment
     */
    public static HiddenCommentResponse of(CommentResponse comment, String hiddenReason) {
        return new HiddenCommentResponse(
                comment.id(),
                comment.postId(),
                comment.author(),
                comment.parentId(),
                comment.rootId(),
                comment.depth(),
                comment.content(),
                comment.likeCount(),
                comment.isLiked(),
                comment.hasReported(),
                comment.replyCount(),
                comment.createdAt(),
                comment.updatedAt(),
                comment.editedAt(),
                false,
                hiddenReason);
    }
}
