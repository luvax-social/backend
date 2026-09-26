package com.app.modules.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Payload to request a new email verification link")
public record ResendVerificationRequest(
        @Schema(
                        description = "Email address of the unverified account",
                        example = "john@example.com",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Email
                String email,
        @Schema(
                        description =
                                "Cloudflare Turnstile token from the widget on the form. Carries no"
                                        + " @NotBlank on purpose: a blank token is refused by the"
                                        + " verifier, which is what lets app.turnstile.auth.enabled"
                                        + " switch the check off.",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @Size(max = 2048)
                String turnstileToken) {}
