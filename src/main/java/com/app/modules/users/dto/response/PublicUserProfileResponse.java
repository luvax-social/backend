package com.app.modules.users.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.app.common.response.ViewerRelationshipResponse;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Public-facing profile of another user.
 *
 * <p>Note on {@code isVerified}: this is the administrator-granted platform badge ({@code
 * users.is_verified}), not email confirmation status.
 */
@Schema(description = "Public profile of a user")
public record PublicUserProfileResponse(
        @Schema(
                        description = "Unique user identifier",
                        example = "550e8400-e29b-41d4-a716-446655440000",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                UUID id,
        @Schema(description = "Unique username", example = "jane_doe") String username,
        @Schema(description = "Display name", example = "Jane Doe", nullable = true)
                String displayName,
        @Schema(description = "Short bio", nullable = true) String bio,
        @Schema(description = "Avatar CDN URL", nullable = true) String avatarUrl,
        @Schema(description = "Banner (cover image) CDN URL", nullable = true) String bannerUrl,
        @Schema(description = "Personal website URL", nullable = true) String websiteUrl,
        @Schema(description = "Whether the account is private", example = "false")
                boolean isPrivate,
        @Schema(description = "Whether the account has a verified badge", example = "false")
                boolean isVerified,
        @Schema(
                        description = "Category of the verified badge; null when not verified",
                        example = "music",
                        nullable = true)
                String verifiedCategory,
        @Schema(
                        description = "Number of followers; null when caller is unauthenticated",
                        example = "120",
                        nullable = true)
                Integer followerCount,
        @Schema(
                        description =
                                "Number of accounts followed; null when caller is unauthenticated",
                        example = "80",
                        nullable = true)
                Integer followingCount,
        @Schema(
                        description =
                                "Number of published posts; null when caller is unauthenticated",
                        example = "15",
                        nullable = true)
                Integer postCount,
        @Schema(description = "Account creation timestamp") OffsetDateTime createdAt,
        @Schema(
                        description =
                                "The viewer's relationship to this user; always false for an"
                                        + " anonymous caller or the owner viewing their own"
                                        + " profile.")
                ViewerRelationshipResponse viewerState) {}
