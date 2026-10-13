package com.app.modules.comment.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.app.modules.comment.dto.response.CommentPreviewResponse;

/** Batched comment previews for surfaces owned by other modules, such as notifications. */
public interface CommentPreviewService {

    /**
     * Resolves each comment's text, its parent's text for a reply, and viewer-specific visibility
     * in one query however many comments are asked for.
     *
     * <p>A tombstoned, blocked, or unapproved comment is unavailable, except that its author may
     * see unapproved text. A reply's parent text follows the same rule. Post visibility is decided
     * by the caller; see {@link CommentPreviewResponse}.
     *
     * @param viewerId the account reading the preview
     * @param commentIds the comments; duplicates are collapsed
     * @return a preview for every requested id; an id naming no comment maps to an unavailable one
     */
    Map<UUID, CommentPreviewResponse> loadPreviews(UUID viewerId, Collection<UUID> commentIds);
}
