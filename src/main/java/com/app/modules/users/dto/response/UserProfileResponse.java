package com.app.modules.users.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Full profile of the authenticated user.
 *
 * <p>Note on verification fields: {@code isVerified} reflects {@code users.is_verified}, the
 * administrator-set platform badge (analogous to a blue checkmark). It is {@code false} for all
 * regular accounts until an admin explicitly grants it. It is <em>not</em> the same as email
 * confirmation status, which is tracked in {@code user_credentials.email_verified} and exposed as
 * {@code emailVerified} in the auth response ({@link
 * com.app.modules.auth.dto.response.AuthResponse}).
 */
@Schema(description = "Full profile of the authenticated user")
public record UserProfileResponse(
        @Schema(
                        description = "Unique user identifier",
                        example = "550e8400-e29b-41d4-a716-446655440000",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                UUID id,
        @Schema(description = "Unique username", example = "jane_doe") String username,
        @Schema(description = "Email address", example = "jane@example.com") String email,
        @Schema(description = "Display name", example = "Jane Doe", nullable = true)
                String displayName,
        @Schema(description = "Short bio", nullable = true) String bio,
        @Schema(description = "Avatar CDN URL", nullable = true) String avatarUrl,
        @Schema(description = "Banner (cover image) CDN URL", nullable = true) String bannerUrl,
        @Schema(description = "Personal website URL", nullable = true) String websiteUrl,
        @Schema(description = "Whether the account is private", example = "false")
                boolean isPrivate,
        @Schema(
                        description =
                                "Whether the account holds the administrator-granted platform"
                                        + " verification badge. Always false for regular users."
                                        + " Not the same as email verification status"
                                        + " (see emailVerified in the auth response).",
                        example = "false")
                boolean isVerified,
        @Schema(
                        description = "Category of the verified badge; null when not verified",
                        example = "music",
                        nullable = true)
                String verifiedCategory,
        @Schema(description = "Number of followers", example = "120") int followerCount,
        @Schema(description = "Number of accounts followed", example = "80") int followingCount,
        @Schema(description = "Number of published posts", example = "15") int postCount,
        @Schema(description = "Account creation timestamp") OffsetDateTime createdAt) {}
