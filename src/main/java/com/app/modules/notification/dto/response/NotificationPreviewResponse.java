package com.app.modules.notification.dto.response;

import java.time.OffsetDateTime;

import com.app.common.response.PreviewMediaResponse;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The content shown beside a notification: a post or story thumbnail and the comment text. Null on
 * the item when the target is unavailable, so nothing of a removed target is ever shown.
 */
@Schema(description = "Content shown beside a notification")
public record NotificationPreviewResponse(
        @Schema(description = "Post or story thumbnail", nullable = true)
                PreviewMediaResponse media,
        @Schema(
                        description =
                                "The comment the notification is about, shortened to 140"
                                        + " characters",
                        nullable = true)
                String commentSnippet,
        @Schema(
                        description = "For a reply: the recipient's comment it answers, shortened",
                        nullable = true)
                String parentCommentSnippet,
        @Schema(description = "For a story: when it stops being viewable", nullable = true)
                OffsetDateTime storyExpiresAt) {}
