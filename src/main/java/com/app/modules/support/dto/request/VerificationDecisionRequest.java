package com.app.modules.support.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A moderator's decision on a verification request, or a revocation of a granted badge.
 *
 * <p>{@code reason} is required. Rejecting and revoking both deny or withdraw something the account
 * can see, and the person on the other end is told why, which is the same bar {@code
 * moderation_action_configs.requires_reason} records for both actions.
 *
 * <p>{@code internalNote} never leaves the system and is never carried into any mail, exactly as
 * the support ticket's note is not.
 */
@Schema(description = "A staff decision on a verification request")
public record VerificationDecisionRequest(
        @Schema(description = "Why the request was decided this way; reaches the requester")
                @NotBlank
                @Size(max = 2000)
                String reason,
        @Schema(description = "Staff-only note; never sent to the requester", nullable = true)
                @Size(max = 2000)
                String internalNote) {}
