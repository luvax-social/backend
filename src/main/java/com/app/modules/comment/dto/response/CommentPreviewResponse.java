package com.app.modules.comment.dto.response;

import java.util.UUID;

/**
 * What another module may show of a comment beside a reference to it.
 *
 * <p>Covers the comment's own tombstones only. Whether the viewer can see the post it belongs to is
 * the post module's decision, which the caller combines with this one through {@code postId}.
 *
 * @param postId the post the comment belongs to; null when the comment no longer exists
 * @param parentId the comment it replies to; null for a top-level comment
 * @param snippet the comment text, shortened; null when unavailable
 * @param parentSnippet the parent's text, shortened; null for a top-level comment or when the
 *     parent is unavailable
 * @param available false when the comment was deleted by its author or removed by moderation
 */
public record CommentPreviewResponse(
        UUID commentId,
        UUID postId,
        UUID parentId,
        String snippet,
        String parentSnippet,
        boolean available) {

    public static CommentPreviewResponse unavailable(UUID commentId, UUID postId) {
        return new CommentPreviewResponse(commentId, postId, null, null, null, false);
    }
}
