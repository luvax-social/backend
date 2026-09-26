package com.app.modules.support.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.modules.support.entity.SupportTicket;
import com.app.modules.support.enums.SupportCategory;
import com.app.modules.support.enums.SupportTicketStatus;
import com.app.modules.support.repository.SupportTicketRepository;

@ExtendWith(MockitoExtension.class)
class SupportTicketPreviewServiceImplTest {

    @Mock private SupportTicketRepository supportTicketRepository;

    private SupportTicketPreviewServiceImpl service;
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SupportTicketPreviewServiceImpl(supportTicketRepository);
    }

    @Test
    void loadPreviews_ownTicket_showsSubjectAndStatus() {
        SupportTicket ticket = ticket(owner);
        when(supportTicketRepository.findAllById(anyIterable())).thenReturn(List.of(ticket));

        var preview = service.loadPreviews(owner, List.of(ticket.getId())).get(ticket.getId());

        assertThat(preview.available()).isTrue();
        assertThat(preview.subject()).isEqualTo("Cannot upload");
        assertThat(preview.status()).isEqualTo("answered");
        assertThat(preview.category()).isEqualTo("bug_report");
    }

    @Test
    void loadPreviews_someoneElsesTicket_readsAsMissing() {
        SupportTicket ticket = ticket(UUID.randomUUID());
        when(supportTicketRepository.findAllById(anyIterable())).thenReturn(List.of(ticket));

        var preview = service.loadPreviews(owner, List.of(ticket.getId())).get(ticket.getId());

        assertThat(preview.available()).isFalse();
        assertThat(preview.subject()).isNull();
    }

    private static SupportTicket ticket(UUID userId) {
        return SupportTicket.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .subject("Cannot upload")
                .status(SupportTicketStatus.ANSWERED)
                .category(SupportCategory.BUG_REPORT)
                .build();
    }
}
