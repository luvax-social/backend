package com.app.modules.users.dto.request;

import com.app.modules.users.enums.MessagePermission;
import com.app.modules.users.enums.TagPermission;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;

@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(
        description =
                "Payload for updating notification and privacy settings; omit fields to leave unchanged")
public record UpdateSettingsRequest(
        @Schema(description = "Receive notifications for likes", example = "true")
                Boolean notifyLikes,
        @Schema(description = "Receive notifications for comments", example = "true")
                Boolean notifyComments,
        @Schema(description = "Receive notifications for follows", example = "true")
                Boolean notifyFollows,
        @Schema(description = "Receive notifications for mentions", example = "true")
                Boolean notifyMentions,
        @Schema(description = "Receive notifications for messages", example = "true")
                Boolean notifyMessages,
        @Schema(description = "Show activity status to other users", example = "true")
                Boolean showActivityStatus,
        @Schema(description = "Allow replies to stories", example = "true")
                Boolean allowStoryReplies,
        @Schema(
                        description = "Who may start a direct conversation with this account",
                        example = "everyone")
                MessagePermission messagePermission,
        @Schema(
                        description =
                                "Set false to stop this account being offered in other people's"
                                        + " suggestions",
                        nullable = true)
                Boolean suggestible,
        @Schema(
                        description =
                                "Set false to stop the platform-wide hidden word list applying to"
                                        + " this account; the personal list is unaffected",
                        nullable = true)
                Boolean hiddenWordsSystemEnabled,
        @Schema(
                        description = "Who may tag this account in a post",
                        example = "everyone",
                        nullable = true)
                TagPermission tagPermission,
        @Schema(
                        description =
                                "Set true to approve each tag of this account before it is shown",
                        nullable = true)
                Boolean manualTagApproval) {}
