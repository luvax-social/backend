package com.app.modules.message.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** API response summarizing one member of a conversation. */
@Schema(description = "Conversation participant summary")
public record ParticipantResponse(
        @Schema(description = "User identifier.") UUID userId,
        @Schema(description = "Unique username.", nullable = true) String username,
        @Schema(description = "Whether this participant carries a verified badge.")
                boolean isVerified,
        @Schema(
                        description =
                                "Category of the participant's badge; null when not verified.",
                        nullable = true)
                String verifiedCategory,
        @Schema(description = "Display name shown on the profile.", nullable = true)
                String displayName,
        @Schema(description = "Avatar CDN URL.", nullable = true) String avatarUrl,
        @Schema(description = "When this member joined.") OffsetDateTime joinedAt,
        @Schema(description = "When this member left, or null if still active.", nullable = true)
                OffsetDateTime leftAt,
        @Schema(
                        description =
                                "This row's own private label for the other participant; null if"
                                        + " that user has not set one. Only meaningful on the"
                                        + " caller's own row - it is never another user's label"
                                        + " for them.",
                        nullable = true)
                String nickname) {}
