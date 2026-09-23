package com.app.modules.post.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.app.modules.post.dto.response.PostPreviewResponse;

/** Batched post previews for surfaces owned by other modules, such as notifications. */
public interface PostPreviewService {

    /**
     * Resolves whether the viewer can open each post and its first media item, in a fixed number of
     * queries however many posts are asked for.
     *
     * <p>A post is unavailable when it is soft-deleted, removed by moderation, not published (its
     * owner still sees an archived post), or its owner is hidden from the viewer by a block, by
     * deletion, or by being private and not followed. An unavailable preview carries no media, so a
     * removed image is never shown through a notification.
     *
     * @param viewerId the account the previews are for
     * @param postIds the posts; duplicates are collapsed
     * @return a preview for every requested id; an id naming no post maps to an unavailable preview
     */
    Map<UUID, PostPreviewResponse> loadPreviews(UUID viewerId, Collection<UUID> postIds);
}
