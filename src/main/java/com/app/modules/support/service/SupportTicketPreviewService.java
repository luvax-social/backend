package com.app.modules.support.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.app.modules.support.dto.response.SupportTicketPreviewResponse;

/** Batched support ticket previews for surfaces owned by other modules, such as notifications. */
public interface SupportTicketPreviewService {

    /**
     * Resolves the subject and status of each ticket, for its owner only.
     *
     * <p>A ticket belonging to another account is reported unavailable, exactly like one that does
     * not exist, so the result is never an oracle for someone else's ticket.
     *
     * @param ownerId the account the previews are for
     * @param ticketIds the tickets; duplicates are collapsed
     * @return a preview for every requested id
     */
    Map<UUID, SupportTicketPreviewResponse> loadPreviews(UUID ownerId, Collection<UUID> ticketIds);
}
