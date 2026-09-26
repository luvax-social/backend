package com.app.modules.support.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.app.modules.support.enums.SupportCategory;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A ticket opened by an authenticated account.
 *
 * @param category what the ticket is about
 * @param subject one-line summary
 * @param body the request itself, immutable once written
 */
public record CreateSupportTicketRequest(
        @Schema(description = "Ticket category", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                SupportCategory category,
        @Schema(description = "One-line summary", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Size(max = 200)
                String subject,
        @Schema(description = "The request itself", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Size(max = 5000)
                String body) {}
