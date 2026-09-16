package com.app.modules.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.app.modules.auth.validation.ValidPassword;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;

// AUTH-022: reject payloads with unrecognised fields to prevent mass-assignment attacks
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(description = "Payload to complete a password reset using the one-time token")
public record ResetPasswordRequest(
        @Schema(
                        description = "One-time reset token from the password-reset email link",
                        example = "a1b2c3d4e5f6...",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                String token,
        @Schema(
                        description =
                                "New password to set; 8 to 64 characters and at most 72 bytes when"
                                        + " encoded as UTF-8. Must contain at least one uppercase"
                                        + " letter, at least one digit or special character, and no"
                                        + " whitespace or invisible characters.",
                        example = "N3wS3cur3P@ss",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @ValidPassword
                String newPassword,
        @Schema(
                        description =
                                "Cloudflare Turnstile token from the widget on the form. Carries no"
                                        + " @NotBlank on purpose: a blank token is refused by the"
                                        + " verifier, which is what lets app.turnstile.auth.enabled"
                                        + " switch the check off.",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @Size(max = 2048)
                String turnstileToken) {}
