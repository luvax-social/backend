package com.app.modules.support.mapper;

import org.springframework.stereotype.Component;

import com.app.modules.support.dto.response.SupportTicketResponse;
import com.app.modules.support.dto.response.SupportTicketStaffResponse;
import com.app.modules.support.entity.SupportTicket;

/**
 * Maps a ticket to the two shapes it is ever returned in.
 *
 * <p>Written by hand rather than generated. A generated mapper maps by name, so a field added to
 * the entity would appear in whichever response record happened to declare a matching component,
 * and {@code internalNote} is exactly the field that must never do that. Writing the two mappings
 * out makes the omission a visible line of code rather than the absence of one.
 */
@Component
public class SupportTicketMapper {

    /**
     * The shape the ticket's own author receives.
     *
     * <p>Deliberately does not read {@code internalNote}, {@code assignedTo}, {@code respondedBy},
     * {@code escalatedBy} or {@code escalationReason}. Those are staff workflow and the record has
     * no component for them.
     *
     * @param ticket the ticket
     * @return the author-facing shape
     */
    public SupportTicketResponse toOwnerResponse(SupportTicket ticket) {
        return new SupportTicketResponse(
                ticket.getId(),
                ticket.getCategory(),
                ticket.getSubject(),
                ticket.getBody(),
                ticket.getStatus(),
                ticket.getSource(),
                ticket.getStaffResponse(),
                ticket.getRespondedAt(),
                ticket.getCreatedAt());
    }

    /**
     * The shape staff receive, and the only one carrying {@code internalNote}.
     *
     * @param ticket the ticket
     * @return the staff-facing shape
     */
    public SupportTicketStaffResponse toStaffResponse(SupportTicket ticket) {
        return new SupportTicketStaffResponse(
                ticket.getId(),
                ticket.getUserId(),
                ticket.getContactEmail(),
                ticket.getCategory(),
                ticket.getSubject(),
                ticket.getBody(),
                ticket.getStatus(),
                ticket.getSource(),
                ticket.getAdminActionId(),
                ticket.getAssignedTo(),
                ticket.getAssignedAt(),
                ticket.getStaffResponse(),
                ticket.getInternalNote(),
                ticket.getRespondedBy(),
                ticket.getRespondedAt(),
                ticket.getEscalatedBy(),
                ticket.getEscalatedAt(),
                ticket.getEscalationReason(),
                ticket.getCreatedAt());
    }
}
