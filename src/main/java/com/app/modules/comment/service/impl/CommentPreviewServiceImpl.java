package com.app.modules.comment.service.impl;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.text.Snippets;
import com.app.modules.comment.dto.response.CommentPreviewResponse;
import com.app.modules.comment.repository.CommentPreviewRepository;
import com.app.modules.comment.repository.CommentPreviewRepository.CommentPreviewRow;
import com.app.modules.comment.service.CommentPreviewService;

@Service
public class CommentPreviewServiceImpl implements CommentPreviewService {

    private final CommentPreviewRepository commentPreviewRepository;

    public CommentPreviewServiceImpl(CommentPreviewRepository commentPreviewRepository) {
        this.commentPreviewRepository = commentPreviewRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, CommentPreviewResponse> loadPreviews(
            UUID viewerId, Collection<UUID> commentIds) {
        Map<UUID, CommentPreviewResponse> previews = new HashMap<>();
        if (commentIds == null || commentIds.isEmpty()) {
            return previews;
        }
        Set<UUID> requested = Set.copyOf(commentIds);
        for (CommentPreviewRow row :
                commentPreviewRepository.findPreviewRows(viewerId, requested)) {
            previews.put(
                    row.id(),
                    unavailable(
                                    row.hidden(),
                                    row.moderationStatus(),
                                    row.authorId(),
                                    row.blocked(),
                                    viewerId)
                            ? CommentPreviewResponse.unavailable(row.id(), row.postId())
                            : new CommentPreviewResponse(
                                    row.id(),
                                    row.postId(),
                                    row.parentId(),
                                    Snippets.of(row.content()),
                                    row.parentId() == null
                                                    || unavailable(
                                                            row.parentHidden(),
                                                            row.parentModerationStatus(),
                                                            row.parentAuthorId(),
                                                            row.parentBlocked(),
                                                            viewerId)
                                            ? null
                                            : Snippets.of(row.parentContent()),
                                    true));
        }
        for (UUID id : requested) {
            previews.putIfAbsent(id, CommentPreviewResponse.unavailable(id, null));
        }
        return previews;
    }

    private static boolean unavailable(
            boolean tombstoned,
            String moderationStatus,
            UUID authorId,
            boolean blocked,
            UUID viewerId) {
        return tombstoned
                || blocked
                || (!"approved".equals(moderationStatus) && !viewerId.equals(authorId));
    }
}
