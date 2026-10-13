package com.app.modules.admin.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One post as an investigating moderator sees it.
 *
 * <p>Deliberately not the public {@code PostResponse}. That carries viewer state, which is
 * meaningless here for the same reason it is on the report target: whether the reviewing moderator
 * has liked or saved the content is not part of the decision, and computing it would imply the
 * moderator is a viewer of the content in the ordinary sense.
 *
 * @param id post identifier
 * @param userId the author
 * @param username the author's username, or null once that account is deleted
 * @param status the post's lifecycle status, including {@code removed}
 * @param caption the caption, or null when the post carries none
 * @param removed whether the post is soft-deleted, so a moderator does not act twice
 * @param likeCount trigger-maintained like count
 * @param commentCount trigger-maintained comment count
 * @param createdAt when the post was created
 */
@Schema(description = "One post rendered for an administrative content review")
public record AdminPostSummaryResponse(
        @Schema(description = "Post identifier") UUID id,
        @Schema(description = "Author identifier") UUID userId,
        @Schema(
                        description = "Author's username; null once that account is deleted",
                        nullable = true)
                String username,
        @Schema(description = "Lifecycle status, including removed", example = "published")
                String status,
        @Schema(description = "Caption; null when the post carries none", nullable = true)
                String caption,
        @Schema(description = "Whether the post is soft-deleted") boolean removed,
        @Schema(description = "Number of likes; trigger-maintained") int likeCount,
        @Schema(description = "Number of comments; trigger-maintained") int commentCount,
        @Schema(description = "Creation timestamp") OffsetDateTime createdAt,
        @Schema(
                        description =
                                "CDN URLs of the attached media, in carousel order. Empty for a"
                                        + " text post. The same shape the report-anchored"
                                        + " moderation view returns, so a reviewer sees the same"
                                        + " thing on both screens.")
                List<String> mediaUrls,
        @Schema(description = "Whether moderation can restore this post without undoing owner deletion")
                boolean restorable) {}
