package com.app.modules.support.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A staff decision on a ticket.
 *
 * @param staffResponse the text the user receives; carried into the notice mail
 * @param internalNote staff-only context; never rendered to the user and never mailed
 */
public record RespondSupportTicketRequest(
        @Schema(
                        description = "The text the user receives",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Size(max = 5000)
                String staffResponse,
        @Schema(description = "Staff-only note; never leaves the system") @Size(max = 5000)
                String internalNote) {}
