package com.app.modules.users.dto.response;

import java.time.OffsetDateTime;

import com.app.modules.users.enums.MessagePermission;
import com.app.modules.users.enums.TagPermission;

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
        @Schema(
                        description = "Who may start a direct conversation with this account",
                        example = "everyone")
                MessagePermission messagePermission,
        @Schema(
                        description =
                                "Whether this account may be offered in other people's suggestions",
                        example = "true")
                boolean suggestible,
        @Schema(
                        description =
                                "Whether the platform-wide hidden word list applies to this"
                                        + " account",
                        example = "true")
                boolean hiddenWordsSystemEnabled,
        @Schema(description = "Who may tag this account in a post", example = "everyone")
                TagPermission tagPermission,
        @Schema(
                        description = "Whether each tag of this account waits for its approval",
                        example = "false")
                boolean manualTagApproval,
        @Schema(description = "Last updated timestamp") OffsetDateTime updatedAt) {}
