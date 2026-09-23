package com.app.modules.post.dto.response;

import java.util.UUID;

import com.app.common.response.PreviewMediaResponse;

/**
 * What another module may show of a post beside a reference to it: whether the viewer can open it,
 * and its first media item for a thumbnail.
 *
 * @param available whether the viewer can open the post now
 * @param media the first media item; null for a text post, and always null when unavailable
 */
public record PostPreviewResponse(UUID postId, boolean available, PreviewMediaResponse media) {

    public static PostPreviewResponse unavailable(UUID postId) {
        return new PostPreviewResponse(postId, false, null);
    }
}
