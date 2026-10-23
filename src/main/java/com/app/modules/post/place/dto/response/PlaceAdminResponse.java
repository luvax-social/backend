package com.app.modules.post.place.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.app.modules.post.place.enums.PlaceStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** Administrative view of a place, spanning every lifecycle status. */
@Schema(description = "Place with its lifecycle status, for the administrative registry")
public record PlaceAdminResponse(
        @Schema(description = "Place identifier.") UUID id,
        @Schema(description = "The place name as first written.") String name,
        @Schema(description = "The folded key the registry deduplicates on.") String normalizedName,
        @Schema(description = "Lifecycle status.") PlaceStatus status,
        @Schema(description = "Administrator's note for the current status.", nullable = true)
                String statusNote,
        @Schema(description = "When the current status was set.", nullable = true)
                OffsetDateTime statusAt,
        @Schema(description = "Administrator that set the current status.", nullable = true)
                UUID statusBy,
        @Schema(description = "Account that first used the name.", nullable = true) UUID createdBy,
        @Schema(description = "Published posts at this place; trigger-maintained.") int postCount,
        @Schema(description = "Creation timestamp.") OffsetDateTime createdAt) {}
