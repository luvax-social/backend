package com.app.modules.support.service.impl;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.modules.support.dto.response.SupportTicketPreviewResponse;
import com.app.modules.support.entity.SupportTicket;
import com.app.modules.support.repository.SupportTicketRepository;
import com.app.modules.support.service.SupportTicketPreviewService;

@Service
public class SupportTicketPreviewServiceImpl implements SupportTicketPreviewService {

    private final SupportTicketRepository supportTicketRepository;

    public SupportTicketPreviewServiceImpl(SupportTicketRepository supportTicketRepository) {
        this.supportTicketRepository = supportTicketRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, SupportTicketPreviewResponse> loadPreviews(
            UUID ownerId, Collection<UUID> ticketIds) {
        Map<UUID, SupportTicketPreviewResponse> previews = new HashMap<>();
        if (ticketIds == null || ticketIds.isEmpty()) {
            return previews;
        }
        Set<UUID> requested = Set.copyOf(ticketIds);
        for (SupportTicket ticket : supportTicketRepository.findAllById(requested)) {
            if (!ownerId.equals(ticket.getUserId())) {
                continue;
            }
            previews.put(
                    ticket.getId(),
                    new SupportTicketPreviewResponse(
                            ticket.getId(),
                            ticket.getSubject(),
                            ticket.getStatus().name().toLowerCase(Locale.ROOT),
                            ticket.getCategory().name().toLowerCase(Locale.ROOT),
                            true));
        }
        for (UUID id : requested) {
            previews.putIfAbsent(id, SupportTicketPreviewResponse.unavailable(id));
        }
        return previews;
    }
}
