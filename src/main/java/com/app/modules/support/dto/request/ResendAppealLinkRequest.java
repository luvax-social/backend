package com.app.modules.support.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A request to re-send the appeal link for the most recent decision an account has not yet
 * contested.
 *
 * <p>Carries no identifier for the decision, and cannot: the whole premise is that the requester
 * has lost the only message that named one. The account is resolved from the address, and which
 * decision the link addresses is decided server-side.
 *
 * @param contactEmail the address the moderation notice was sent to
 * @param turnstileToken the Turnstile challenge response
 */
public record ResendAppealLinkRequest(
        @Schema(
                        description = "The address the moderation notice was sent to",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Email
                @Size(max = 255)
                String contactEmail,
        @Schema(
                        description = "Turnstile challenge response",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                String turnstileToken) {}
