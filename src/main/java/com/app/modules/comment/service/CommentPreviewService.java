package com.app.modules.comment.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.app.modules.comment.dto.response.CommentPreviewResponse;

/** Batched comment previews for surfaces owned by other modules, such as notifications. */
public interface CommentPreviewService {

    /**
     * Resolves each comment's text, its parent's text for a reply, and whether it still exists, in
     * one query however many comments are asked for.
     *
     * <p>A comment deleted by its author or removed by moderation is unavailable and carries no
     * text. A reply's parent text is included only while the parent itself is available. Whether
     * the viewer can see the post is not decided here; see {@link CommentPreviewResponse}.
     *
     * @param commentIds the comments; duplicates are collapsed
     * @return a preview for every requested id; an id naming no comment maps to an unavailable one
     */
    Map<UUID, CommentPreviewResponse> loadPreviews(Collection<UUID> commentIds);
}
