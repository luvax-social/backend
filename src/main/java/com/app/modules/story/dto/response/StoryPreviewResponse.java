package com.app.modules.story.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.app.common.response.PreviewMediaResponse;

/**
 * What another module may show of a story beside a reference to it.
 *
 * @param available false once the story expired, was deleted by its author or removed by
 *     moderation, or when the viewer cannot see it
 * @param media the story's media for a thumbnail; null when unavailable
 * @param expiresAt when the story stops being viewable; null when the story no longer exists
 */
public record StoryPreviewResponse(
        UUID storyId, boolean available, PreviewMediaResponse media, OffsetDateTime expiresAt) {

    public static StoryPreviewResponse unavailable(UUID storyId, OffsetDateTime expiresAt) {
        return new StoryPreviewResponse(storyId, false, null, expiresAt);
    }
}
