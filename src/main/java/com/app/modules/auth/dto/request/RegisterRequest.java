package com.app.modules.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.app.modules.auth.validation.ValidPassword;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;

// AUTH-022: reject payloads with unrecognised fields to prevent mass-assignment attacks
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(description = "Payload for creating a new user account")
public record RegisterRequest(
        @Schema(
                        description =
                                "Unique username; letters, digits, underscores, and dots only",
                        example = "john_doe",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Size(min = 3, max = 30)
                @Pattern(
                        regexp = "^[a-zA-Z0-9_.]+$",
                        message = "Username may only contain letters, digits, underscores and dots")
                String username,
        @Schema(
                        description = "User's email address; used for login and verification",
                        example = "john@example.com",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Email
                String email,
        @Schema(
                        description =
                                "Account password; 8 to 64 characters and at most 72 bytes when"
                                        + " encoded as UTF-8. Must contain at least one uppercase"
                                        + " letter, at least one digit or special character, and no"
                                        + " whitespace or invisible characters.",
                        example = "S3cur3P@ssword",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @ValidPassword
                String password,
        @Schema(
                        description = "Human-readable display name shown on the profile (optional)",
                        example = "John Doe")
                @Size(max = 100)
                String displayName,
        @Schema(
                        description =
                                "Cloudflare Turnstile token from the widget on the form. Carries no"
                                        + " @NotBlank on purpose: a blank token is refused by the"
                                        + " verifier, which is what lets app.turnstile.auth.enabled"
                                        + " switch the check off.",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @Size(max = 2048)
                String turnstileToken) {}
