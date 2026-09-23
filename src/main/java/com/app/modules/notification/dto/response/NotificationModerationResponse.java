package com.app.modules.notification.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The moderation decision behind a platform notice: kind, date and a text snippet of the affected
 * content, shown only to its author, never media.
 */
@Schema(description = "Moderation decision behind a platform notice")
public record NotificationModerationResponse(
        @Schema(description = "The admin_action_type label, e.g. remove_comment", nullable = true)
                String actionType,
        @Schema(description = "The reason the moderator recorded", nullable = true) String reason,
        @Schema(description = "post, comment, story, message or account", nullable = true)
                String affectedKind,
        @Schema(
                        description = "The affected text, shortened; only for the content's author",
                        nullable = true)
                String affectedSnippet,
        @Schema(description = "When the affected content was created", nullable = true)
                OffsetDateTime affectedAt,
        @Schema(description = "The decision to appeal; set only when appealable", nullable = true)
                UUID appealActionId,
        @Schema(
                        description = "Whether the recipient can appeal in product",
                        requiredMode = REQUIRED)
                boolean appealable) {}
