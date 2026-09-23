package com.app.modules.story.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.app.modules.story.dto.response.StoryPreviewResponse;

/** Batched story previews for surfaces owned by other modules, such as notifications. */
public interface StoryPreviewService {

    /**
     * Resolves whether the viewer can still open each story, and its media for a thumbnail.
     *
     * <p>A story is unavailable once it expires, is deleted by its author or removed by moderation,
     * and for a viewer other than its owner whenever {@link StoryVisibilityService} hides it.
     * Expiry is checked on every read; a story row outliving its expiry until the cleanup job runs
     * never makes it available again.
     *
     * @param viewerId the account the previews are for
     * @param storyIds the stories; duplicates are collapsed
     * @return a preview for every requested id; an id naming no story maps to an unavailable one
     */
    Map<UUID, StoryPreviewResponse> loadPreviews(UUID viewerId, Collection<UUID> storyIds);
}
