package com.app.modules.story.service.impl;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.response.PreviewMediaResponse;
import com.app.modules.story.dto.response.StoryPreviewResponse;
import com.app.modules.story.repository.StoryPreviewRepository;
import com.app.modules.story.repository.StoryPreviewRepository.StoryPreviewRow;
import com.app.modules.story.repository.StoryRepository;
import com.app.modules.story.service.StoryPreviewService;
import com.app.modules.story.service.StoryVisibilityService;

@Service
public class StoryPreviewServiceImpl implements StoryPreviewService {

    private final StoryPreviewRepository storyPreviewRepository;
    private final StoryRepository storyRepository;
    private final StoryVisibilityService storyVisibilityService;

    public StoryPreviewServiceImpl(
            StoryPreviewRepository storyPreviewRepository,
            StoryRepository storyRepository,
            StoryVisibilityService storyVisibilityService) {
        this.storyPreviewRepository = storyPreviewRepository;
        this.storyRepository = storyRepository;
        this.storyVisibilityService = storyVisibilityService;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, StoryPreviewResponse> loadPreviews(UUID viewerId, Collection<UUID> storyIds) {
        Map<UUID, StoryPreviewResponse> previews = new HashMap<>();
        if (storyIds == null || storyIds.isEmpty()) {
            return previews;
        }
        Set<UUID> requested = Set.copyOf(storyIds);
        for (StoryPreviewRow row : storyPreviewRepository.findPreviewRows(requested)) {
            boolean available = !row.hidden() && !row.expired() && isVisible(viewerId, row);
            previews.put(
                    row.id(),
                    available
                            ? new StoryPreviewResponse(row.id(), true, media(row), row.expiresAt())
                            : StoryPreviewResponse.unavailable(row.id(), row.expiresAt()));
        }
        for (UUID id : requested) {
            previews.putIfAbsent(id, StoryPreviewResponse.unavailable(id, null));
        }
        return previews;
    }

    // Notifications only ever reference a story to its owner (a view of their own story), so the
    // per-story visibility lookup below runs only for the rare other viewer.
    private boolean isVisible(UUID viewerId, StoryPreviewRow row) {
        if (viewerId.equals(row.ownerId())) {
            return true;
        }
        return storyRepository
                .findById(row.id())
                .map(story -> storyVisibilityService.isVisibleTo(viewerId, story))
                .orElse(false);
    }

    private static PreviewMediaResponse media(StoryPreviewRow row) {
        if (row.cdnUrl() == null) {
            return null;
        }
        return new PreviewMediaResponse(
                row.cdnUrl(), row.blurhash(), row.mediaType(), row.width(), row.height());
    }
}
