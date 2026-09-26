package com.app.modules.admin.service.impl;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.text.Snippets;
import com.app.modules.admin.dto.response.ModerationNoticeResponse;
import com.app.modules.admin.messaging.AppealCategories;
import com.app.modules.admin.repository.ModerationNoticeRepository;
import com.app.modules.admin.repository.ModerationNoticeRepository.NoticeRow;
import com.app.modules.admin.service.ModerationNoticeService;

@Service
public class ModerationNoticeServiceImpl implements ModerationNoticeService {

    private static final Set<String> CONTENT_KINDS = Set.of("post", "comment", "story", "message");

    private final ModerationNoticeRepository moderationNoticeRepository;

    public ModerationNoticeServiceImpl(ModerationNoticeRepository moderationNoticeRepository) {
        this.moderationNoticeRepository = moderationNoticeRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ModerationNoticeResponse> loadNotices(
            UUID recipientId, Collection<UUID> adminActionIds) {
        Map<UUID, ModerationNoticeResponse> notices = new HashMap<>();
        if (adminActionIds == null || adminActionIds.isEmpty()) {
            return notices;
        }
        for (NoticeRow row :
                moderationNoticeRepository.findNoticeRows(Set.copyOf(adminActionIds))) {
            boolean aboutRecipient = recipientId.equals(row.targetUserId());
            String kind = affectedKind(row);
            boolean showContent = aboutRecipient && CONTENT_KINDS.contains(kind);
            notices.put(
                    row.id(),
                    new ModerationNoticeResponse(
                            row.id(),
                            row.actionType(),
                            row.reason(),
                            kind,
                            showContent ? Snippets.of(row.affectedText()) : null,
                            showContent ? row.affectedAt() : null,
                            aboutRecipient
                                    && AppealCategories.appealableActionTypeLabels()
                                            .contains(row.actionType())));
        }
        return notices;
    }

    private static String affectedKind(NoticeRow row) {
        if (row.targetEntityType() != null && CONTENT_KINDS.contains(row.targetEntityType())) {
            return row.targetEntityType();
        }
        return row.targetUserId() != null ? "account" : null;
    }
}
