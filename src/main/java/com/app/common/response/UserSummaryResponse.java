package com.app.common.response;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Shared public projection of a user, embedded wherever a response references an author or actor.
 *
 * <p>Carries only publicly renderable identity fields. It never carries email, role, or account
 * status; those are not the concern of a reader rendering someone else's content. A soft-deleted or
 * unknown user resolves to a placeholder whose {@code username} is null and whose {@code
 * displayName} is a fixed marker, so a client never has to branch on a missing author.
 */
@Schema(description = "Public identity summary of a user embedded as an author or actor")
public record UserSummaryResponse(
        @Schema(
                        description = "Unique user identifier",
                        example = "550e8400-e29b-41d4-a716-446655440000")
                UUID id,
        @Schema(
                        description = "Unique username; null when the user is deleted or unknown",
                        nullable = true)
                String username,
        @Schema(
                        description = "Display name shown on the profile",
                        example = "John Doe",
                        nullable = true)
                String displayName,
        @Schema(description = "Avatar CDN URL; null when absent", nullable = true) String avatarUrl,
        @Schema(description = "Verified badge flag", example = "false") boolean isVerified,
        @Schema(
                        description =
                                "Category of the verified badge, or null when the account is not"
                                        + " verified. The client maps this to the glyph inside the"
                                        + " badge; the badge container itself is the same for every"
                                        + " category.",
                        example = "music",
                        nullable = true)
                String verifiedCategory) {

    /**
     * Builds a summary for a caller that has no verification context to supply.
     *
     * <p>The placeholder for a deleted or unknown account is the only correct use: it is not
     * verified, so it has no category either. A caller that does know the account is verified must
     * pass the category, because a badge whose glyph is missing renders as a bare container and
     * says less than no badge at all.
     */
    public UserSummaryResponse(
            UUID id, String username, String displayName, String avatarUrl, boolean isVerified) {
        this(id, username, displayName, avatarUrl, isVerified, null);
    }
}
