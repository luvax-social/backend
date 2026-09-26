package com.app.modules.users.dto.response;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/** Notification and privacy settings for the authenticated user. */
@Schema(description = "Notification and privacy settings for the authenticated user")
public record UserSettingsResponse(
        @Schema(description = "Receive notifications for likes", example = "true")
                boolean notifyLikes,
        @Schema(description = "Receive notifications for comments", example = "true")
                boolean notifyComments,
        @Schema(description = "Receive notifications for follows", example = "true")
                boolean notifyFollows,
        @Schema(description = "Receive notifications for mentions", example = "true")
                boolean notifyMentions,
        @Schema(description = "Receive notifications for messages", example = "true")
                boolean notifyMessages,
        @Schema(description = "Show activity status to other users", example = "true")
                boolean showActivityStatus,
        @Schema(description = "Allow replies to stories", example = "true")
                boolean allowStoryReplies,
        @Schema(description = "Allow message requests from non-followers", example = "true")
                boolean allowMessageRequests,
        @Schema(
                        description =
                                "Whether this account may be offered in other people's suggestions",
                        example = "true")
                boolean suggestible,
        @Schema(description = "Last updated timestamp") OffsetDateTime updatedAt) {}
