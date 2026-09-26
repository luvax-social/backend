package com.app.modules.support.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A ticket opened by redeeming the single-use link in a moderation notice.
 *
 * <p>Carries no category: the token names the audit row being appealed, and the category follows
 * from the action that row records. A client-supplied category would let the submitter choose a
 * category the token never authorised.
 *
 * @param token the single-use token from the link
 * @param subject one-line summary
 * @param body the appeal itself
 */
public record SignedAppealRequest(
        @Schema(
                        description = "Single-use token from the appeal link",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                String token,
        @Schema(description = "One-line summary", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Size(max = 200)
                String subject,
        @Schema(description = "The appeal itself", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Size(max = 5000)
                String body) {}
