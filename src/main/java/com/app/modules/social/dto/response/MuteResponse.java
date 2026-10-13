package com.app.modules.social.dto.response;

import java.util.UUID;

import com.app.common.response.UserSummaryResponse;

import io.swagger.v3.oas.annotations.media.Schema;

/** One muted account with the scopes the caller muted, as set and as listed. */
@Schema(description = "A muted account and what is muted")
public record MuteResponse(
        @Schema(description = "The muted account's identifier") UUID userId,
        @Schema(description = "The account's posts are muted; false for both scopes means none")
                boolean mutePosts,
        @Schema(description = "The account's stories are muted") boolean muteStories,
        @Schema(description = "Public summary of the muted account") UserSummaryResponse user) {}
