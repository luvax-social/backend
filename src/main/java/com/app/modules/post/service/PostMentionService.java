package com.app.modules.post.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.app.modules.post.dto.response.PostMentionResponse;
import com.app.modules.post.entity.Post;

/** Stores the {@code @mentions} of a post caption by user id and reads them back for display. */
public interface PostMentionService {

    /**
     * What a caption write changed in the stored mentions.
     *
     * @param stored every account now mentioned
     * @param notifiable the accounts to tell at first publication: mentioned outright and not the
     *     author
     * @param added the accounts mentioned now that were not before this write
     */
    record MentionChange(List<UUID> stored, List<UUID> notifiable, List<UUID> added) {}

    /**
     * Re-parses the caption and replaces the stored mentions of the post.
     *
     * <p>A handle becomes a stored mention only when it names an active account (matched without
     * regard to case) and the interaction policy lets the author mention it: a blocked pair stores
     * nothing, and a target whose interaction limit applies is stored without being notified. At
     * most ten distinct handles are read. Nothing is announced here; the caller decides whether the
     * post is published and who is told.
     *
     * @param post the post whose caption is parsed; its owner is the mentioning account
     * @return what changed
     */
    MentionChange replaceCaptionMentions(Post post);

    /**
     * Enqueues {@code post.mention.created.v1} for each account.
     *
     * @param post the published post
     * @param userIds the accounts mentioned
     * @param notifiable the subset of {@code userIds} to notify; the event carries the flag
     */
    void announce(Post post, Collection<UUID> userIds, Collection<UUID> notifiable);

    /**
     * Loads the mentions to show a viewer for many posts at once.
     *
     * <p>A mention of an account that is blocked with the viewer, in either direction, is left out,
     * and the renderer shows that {@code @name} as plain text. The username returned is the
     * account's current one.
     *
     * @param viewerId the viewer
     * @param postIds the posts
     * @return mentions keyed by post id; absent when a post has none
     */
    Map<UUID, List<PostMentionResponse>> loadForPosts(UUID viewerId, Collection<UUID> postIds);
}
