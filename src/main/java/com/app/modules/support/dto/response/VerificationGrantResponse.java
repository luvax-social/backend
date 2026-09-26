package com.app.modules.support.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One historical or current badge grant, for the moderator reviewing a resubmission.
 *
 * <p>{@code revocationActor} is the field that matters here. A badge withdrawn by {@code system}
 * was swept away by a suspension or a ban and carries no judgement about the claim; one withdrawn
 * by a {@code moderator} does.
 */
@Schema(description = "A previous or current verified badge")
public record VerificationGrantResponse(
        @Schema(description = "Grant identifier") UUID id,
        @Schema(description = "Category granted", example = "music") String categoryKey,
        @Schema(description = "When it was granted") OffsetDateTime grantedAt,
        @Schema(description = "When it was withdrawn; null while active", nullable = true)
                OffsetDateTime revokedAt,
        @Schema(
                        description = "moderator or system; null while active",
                        example = "moderator",
                        nullable = true)
                String revocationActor,
        @Schema(description = "Why it was withdrawn", nullable = true) String revocationReason) {}
