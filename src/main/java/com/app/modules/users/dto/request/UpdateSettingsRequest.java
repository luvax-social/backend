package com.app.modules.users.dto.request;

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
        @Schema(description = "Allow message requests from non-followers", example = "true")
                Boolean allowMessageRequests,
        @Schema(
                        description =
                                "Set false to stop this account being offered in other people's"
                                        + " suggestions",
                        nullable = true)
                Boolean suggestible) {}
