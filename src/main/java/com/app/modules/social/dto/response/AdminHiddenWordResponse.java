package com.app.modules.social.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** One phrase of the platform-wide hidden word list as the administrator registry shows it. */
@Schema(description = "A phrase on the platform-wide hidden word list")
public record AdminHiddenWordResponse(
        @Schema(description = "Entry identifier") UUID id,
        @Schema(description = "The phrase as the administrator typed it", example = "Đồ Ngu")
                String phrase,
        @Schema(
                        description = "The form matching compares, with accents and case removed",
                        example = "do ngu")
                String normalizedPhrase,
        @Schema(
                        description =
                                "Administrator who added the phrase, or null once that account"
                                        + " is gone",
                        nullable = true)
                UUID createdBy,
        @Schema(description = "UTC creation timestamp") OffsetDateTime createdAt,
        @Schema(description = "UTC timestamp of the last edit") OffsetDateTime updatedAt) {}
