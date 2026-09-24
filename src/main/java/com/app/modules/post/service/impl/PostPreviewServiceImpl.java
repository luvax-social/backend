package com.app.modules.post.service.impl;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.response.PreviewMediaResponse;
import com.app.modules.post.dto.response.PostPreviewResponse;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.repository.PostPreviewRepository;
import com.app.modules.post.repository.PostPreviewRepository.PostPreviewRow;
import com.app.modules.post.service.PostPreviewService;
import com.app.modules.post.service.PostVisibilityService;

@Service
public class PostPreviewServiceImpl implements PostPreviewService {

    private final PostPreviewRepository postPreviewRepository;
    private final PostVisibilityService postVisibilityService;

    public PostPreviewServiceImpl(
            PostPreviewRepository postPreviewRepository,
            PostVisibilityService postVisibilityService) {
        this.postPreviewRepository = postPreviewRepository;
        this.postVisibilityService = postVisibilityService;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, PostPreviewResponse> loadPreviews(UUID viewerId, Collection<UUID> postIds) {
        Map<UUID, PostPreviewResponse> previews = new HashMap<>();
        if (postIds == null || postIds.isEmpty()) {
            return previews;
        }
        Set<UUID> requested = Set.copyOf(postIds);
        List<PostPreviewRow> rows = postPreviewRepository.findPreviewRows(requested);
        Set<UUID> visibleOwners =
                postVisibilityService.filterVisibleOwnerIds(
                        viewerId, rows.stream().map(PostPreviewRow::ownerId).distinct().toList());
        for (PostPreviewRow row : rows) {
            boolean available =
                    !row.deleted()
                            && !row.moderated()
                            && isOpenableStatus(row, viewerId)
                            && visibleOwners.contains(row.ownerId());
            previews.put(
                    row.id(),
                    available
                            ? new PostPreviewResponse(row.id(), true, media(row))
                            : PostPreviewResponse.unavailable(row.id()));
        }
        for (UUID id : requested) {
            previews.putIfAbsent(id, PostPreviewResponse.unavailable(id));
        }
        return previews;
    }

    private static boolean isOpenableStatus(PostPreviewRow row, UUID viewerId) {
        PostStatus status = PostStatus.fromJson(row.status());
        return status == PostStatus.PUBLISHED
                || (status == PostStatus.ARCHIVED && row.ownerId().equals(viewerId));
    }

    private static PreviewMediaResponse media(PostPreviewRow row) {
        if (row.cdnUrl() == null) {
            return null;
        }
        return new PreviewMediaResponse(
                row.cdnUrl(), row.blurhash(), row.mediaType(), row.width(), row.height());
    }
}
