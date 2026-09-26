package com.app.modules.support.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.app.modules.support.enums.SupportCategory;
import com.app.modules.support.enums.SupportSource;
import com.app.modules.support.enums.SupportTicketStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A ticket as staff see it.
 *
 * <p>This is the only shape that carries {@code internalNote}, and it is only ever returned from an
 * endpoint gated to staff in both the controller annotation and the service.
 *
 * @param id ticket identifier
 * @param userId the account that opened it, or null for an unresolved public submission
 * @param contactEmail where the response goes
 * @param category what the ticket is about
 * @param subject one-line summary
 * @param body the request as submitted
 * @param status current lifecycle state
 * @param source how the ticket arrived
 * @param adminActionId the audit row being appealed, or null
 * @param assignedTo the staff member holding the claim, or null
 * @param assignedAt when it was claimed, or null
 * @param staffResponse the response, or null
 * @param internalNote staff-only note, or null
 * @param respondedBy who responded, or null
 * @param respondedAt when, or null
 * @param escalatedBy who escalated it, or null
 * @param escalatedAt when, or null
 * @param escalationReason why, or null
 * @param createdAt when the ticket was opened
 */
public record SupportTicketStaffResponse(
        @Schema(description = "Ticket identifier") UUID id,
        @Schema(description = "The account that opened it", nullable = true) UUID userId,
        @Schema(description = "Where the response goes") String contactEmail,
        @Schema(description = "What the ticket is about") SupportCategory category,
        @Schema(description = "One-line summary") String subject,
        @Schema(description = "The request as submitted") String body,
        @Schema(description = "Current lifecycle state") SupportTicketStatus status,
        @Schema(description = "How the ticket arrived") SupportSource source,
        @Schema(description = "The audit row being appealed", nullable = true) UUID adminActionId,
        @Schema(description = "The staff member holding the claim", nullable = true)
                UUID assignedTo,
        @Schema(description = "When it was claimed", nullable = true) OffsetDateTime assignedAt,
        @Schema(description = "The response", nullable = true) String staffResponse,
        @Schema(description = "Staff-only note", nullable = true) String internalNote,
        @Schema(description = "Who responded", nullable = true) UUID respondedBy,
        @Schema(description = "When the response was given", nullable = true)
                OffsetDateTime respondedAt,
        @Schema(description = "Who escalated it", nullable = true) UUID escalatedBy,
        @Schema(description = "When it was escalated", nullable = true) OffsetDateTime escalatedAt,
        @Schema(description = "Why it was escalated", nullable = true) String escalationReason,
        @Schema(description = "When the ticket was opened") OffsetDateTime createdAt) {}
