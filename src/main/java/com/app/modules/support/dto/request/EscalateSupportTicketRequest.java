package com.app.modules.support.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A moderator handing a ticket up to an administrator.
 *
 * @param reason why it needs an administrator
 */
public record EscalateSupportTicketRequest(
        @Schema(
                        description = "Why the ticket needs an administrator",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Size(max = 2000)
                String reason) {}
