package com.app.modules.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Credentials for email or username login")
public record LoginRequest(
        @Schema(
                        description =
                                "Email address or username. The server resolves the account type"
                                        + " by '@' presence.",
                        example = "john@example.com or john_doe",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                String identifier,
        @Schema(
                        description = "Account password",
                        example = "S3cur3P@ssword",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                String password,
        @Schema(
                        description =
                                "Cloudflare Turnstile token from the widget on the form. Carries no"
                                        + " @NotBlank on purpose: a blank token is refused by the"
                                        + " verifier, which is what lets app.turnstile.auth.enabled"
                                        + " switch the check off.",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @Size(max = 2048)
                String turnstileToken) {}
