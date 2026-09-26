package com.app.modules.hashtag.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.app.modules.hashtag.enums.HashtagStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A single hashtag resolved by name or id for the hashtag detail surface.
 *
 * <p>Carries the lifecycle status even though every successful response holds {@code ACTIVE}: a
 * banned or deleted hashtag is refused with {@code HASHTAG_UNAVAILABLE} rather than returned, so
 * the field exists to keep the contract explicit rather than to be branched on by a client.
 */
@Schema(description = "A hashtag with its usage counter and lifecycle status")
public record HashtagDetailResponse(
        @Schema(
                        description = "Unique hashtag identifier",
                        example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
                UUID id,
        @Schema(
                        description = "Hashtag name without the # prefix, always lowercase",
                        example = "devlife")
                String name,
        @Schema(
                        description =
                                "Number of published posts currently tagged with this hashtag",
                        example = "10")
                int postCount,
        @Schema(description = "Lifecycle status; always ACTIVE on a successful response")
                HashtagStatus status,
        @Schema(
                        description = "UTC timestamp when the hashtag was first used",
                        example = "2024-01-15T10:30:00Z")
                OffsetDateTime createdAt,
        @Schema(description = "Whether an administrator has pinned this hashtag platform-wide")
                boolean pinned) {}
