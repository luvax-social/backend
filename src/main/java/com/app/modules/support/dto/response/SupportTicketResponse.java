package com.app.modules.support.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.app.modules.support.enums.SupportCategory;
import com.app.modules.support.enums.SupportSource;
import com.app.modules.support.enums.SupportTicketStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A ticket as its own author sees it.
 *
 * <p>Carries no {@code internalNote}, no {@code assignedTo} and no {@code escalationReason}. Those
 * are staff workflow, and the absence is structural rather than a mapping choice: the field does
 * not exist on this record, so no future mapper edit can leak it.
 *
 * @param id ticket identifier
 * @param category what the ticket is about
 * @param subject one-line summary
 * @param body the request as submitted
 * @param status current lifecycle state
 * @param source how the ticket arrived
 * @param staffResponse the response, or null while none has been given
 * @param respondedAt when the response was given, or null
 * @param createdAt when the ticket was opened
 */
public record SupportTicketResponse(
        @Schema(description = "Ticket identifier") UUID id,
        @Schema(description = "What the ticket is about") SupportCategory category,
        @Schema(description = "One-line summary") String subject,
        @Schema(description = "The request as submitted") String body,
        @Schema(description = "Current lifecycle state") SupportTicketStatus status,
        @Schema(description = "How the ticket arrived") SupportSource source,
        @Schema(description = "The response, or null while none has been given", nullable = true)
                String staffResponse,
        @Schema(description = "When the response was given", nullable = true)
                OffsetDateTime respondedAt,
        @Schema(description = "When the ticket was opened") OffsetDateTime createdAt) {}
