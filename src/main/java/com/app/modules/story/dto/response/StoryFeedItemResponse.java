package com.app.modules.story.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** One author's tray entry in the story feed, with their active stories in playback order. */
@Schema(description = "Story feed tray entry grouped by author")
public record StoryFeedItemResponse(
        @Schema(description = "Author user identifier.") UUID userId,
        @Schema(description = "Author username.") String username,
        @Schema(description = "Whether the author carries a verified badge.") boolean isVerified,
        @Schema(
                        description = "Category of the author's badge; null when not verified.",
                        nullable = true)
                String verifiedCategory,
        @Schema(description = "Author display name.", nullable = true) String userDisplayName,
        @Schema(description = "Author avatar CDN URL.", nullable = true) String userAvatarUrl,
        @Schema(description = "True when at least one story is unseen by the viewer.")
                boolean hasUnseen,
        @Schema(description = "Creation time of the author's newest active story.")
                OffsetDateTime latestStoryAt,
        @Schema(description = "Active stories in playback order (oldest first).")
                List<StoryResponse> stories) {}
