package com.app.modules.support.dto.response;

import java.util.UUID;

/**
 * What another module may show of a support ticket beside a reference to it.
 *
 * @param status the lowercase ticket status; null when unavailable
 * @param category the lowercase ticket category; null when unavailable
 * @param available false when the ticket does not exist or is not the viewer's
 */
public record SupportTicketPreviewResponse(
        UUID ticketId, String subject, String status, String category, boolean available) {

    public static SupportTicketPreviewResponse unavailable(UUID ticketId) {
        return new SupportTicketPreviewResponse(ticketId, null, null, null, false);
    }
}
