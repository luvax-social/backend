package com.app.modules.comment.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.app.modules.post.dto.response.PostMentionResponse;

/** Stores the {@code @mentions} of a comment by user id and reads them back for display. */
public interface CommentMentionService {

    /**
     * Re-parses the comment text and replaces its stored mentions.
     *
     * <p>A handle is read with the username grammar of registration, letters, digits, underscore
     * and dot up to thirty characters, with trailing dots left out so a mention that ends a
     * sentence still resolves, and matched against usernames without regard to case. At most ten
     * distinct handles are read. The interaction policy then decides each: a blocked pair stores
     * nothing and is indistinguishable from an unknown name, and a target whose interaction limit
     * applies is stored but never notified.
     *
     * @param commentId the comment, already persisted
     * @param actorId the comment author
     * @param content the normalised comment text
     * @return the ids of the accounts to notify: mentioned outright, and not the author
     */
    List<String> replaceMentions(UUID commentId, UUID actorId, String content);

    /**
     * Loads the mentions to show a viewer for many comments at once.
     *
     * <p>A mention of an account blocked with the viewer, in either direction, is left out, as is
     * one whose account no longer exists. The username returned is the current one.
     *
     * @param viewerId the viewer, or null for an anonymous read
     * @param commentIds the comments
     * @return mentions keyed by comment id; absent when a comment has none
     */
    Map<UUID, List<PostMentionResponse>> loadForComments(
            UUID viewerId, Collection<UUID> commentIds);
}
