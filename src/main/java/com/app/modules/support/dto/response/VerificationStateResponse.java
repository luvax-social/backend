package com.app.modules.support.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The caller's own verification state, in one call.
 *
 * <p>One response rather than three endpoints because the client renders one of three mutually
 * exclusive things - the form, the submitted state, or the decided state - and asking it to
 * discover which by probing would cost a request to learn something the answer already encodes.
 *
 * <p>{@code decisionReason} is populated for a decided request and for a revoked badge. It is the
 * staff response text, never the internal note.
 */
@Schema(description = "The calling account's verification state")
public record VerificationStateResponse(
        @Schema(description = "Whether the account currently holds a badge") boolean verified,
        @Schema(description = "Category of the active badge; null when unverified", nullable = true)
                String categoryKey,
        @Schema(description = "Glyph key for the active badge", nullable = true) String iconKey,
        @Schema(description = "When the badge was granted", nullable = true)
                OffsetDateTime verifiedSince,
        @Schema(
                        description = "Ticket id of an outstanding request; null when none",
                        nullable = true)
                UUID pendingRequestId,
        @Schema(
                        description = "Status of the most recent request, or null when never asked",
                        example = "open",
                        nullable = true)
                String requestStatus,
        @Schema(description = "Category of the most recent request", nullable = true)
                String requestCategoryKey,
        @Schema(description = "When the most recent request was made", nullable = true)
                OffsetDateTime requestedAt,
        @Schema(
                        description = "Staff response on a decided request or a revocation",
                        nullable = true)
                String decisionReason,
        @Schema(description = "Whether a new request may be submitted now") boolean canRequest) {}
