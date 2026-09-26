package com.app.modules.support.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.app.modules.support.enums.SupportCategory;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A ticket opened from the public form, by someone with no session and possibly no account.
 *
 * @param contactEmail where the response goes; proven by the confirmation step, not by this field
 * @param category what the ticket is about; appeal categories are refused here
 * @param subject one-line summary
 * @param body the request itself
 * @param turnstileToken the Cloudflare Turnstile token, verified before anything is written
 */
public record PublicSupportTicketRequest(
        @Schema(
                        description = "Where the response goes",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Email
                @Size(max = 255)
                String contactEmail,
        @Schema(
                        description = "Ticket category; appeal categories are not accepted here",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                SupportCategory category,
        @Schema(description = "One-line summary", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Size(max = 200)
                String subject,
        @Schema(description = "The request itself", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Size(max = 5000)
                String body,
        @Schema(
                        description = "Cloudflare Turnstile token",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                String turnstileToken) {}
