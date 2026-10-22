package com.app.modules.post.service;

import com.app.modules.post.entity.Post;

/**
 * The one routine that moves a post to {@code published}.
 *
 * <p>Manual publication, create-as-published, unarchiving and the scheduled publisher all go
 * through it, so every publication runs the same checks and the same side effects. Callers hold the
 * post row lock and an open transaction.
 */
public interface PostPublicationService {

    /** How the publication was triggered; recorded with the {@code post.published.v1} signal. */
    enum Source {
        OWNER,
        SCHEDULER
    }

    /**
     * Checks that the post can be published, writing nothing.
     *
     * <p>Not transactional on purpose: the scheduled publisher calls it and treats a refusal as a
     * failed schedule, which it must be able to record in the same transaction. A transactional
     * proxy would mark that transaction rollback-only the moment this threw.
     *
     * @param post the post, locked by the caller
     * @throws PostPublicationException when the owner is not active, the status does not allow
     *     publication, a media asset is gone or no longer the owner's, the caption names a banned
     *     hashtag, or the place was removed by an administrator
     */
    void validate(Post post);

    /**
     * Publishes the post.
     *
     * <p>Validates, sets the status (the database trigger stamps {@code published_at} the first
     * time), associates the caption hashtags and enqueues the search-index upsert. On first
     * publication it also re-decides the stored mentions, tags and invitations against the
     * interaction policy and enqueues the events that tell the people involved; nothing is
     * announced before this point. Unarchiving a post that was published before only restores it to
     * the index.
     *
     * @param post the post, locked by the caller; draft, scheduled or archived
     * @param source what triggered the publication
     * @throws PostPublicationException when {@link #validate} refuses
     */
    void publish(Post post, Source source);

    /**
     * Returns a scheduled post that cannot be published to drafts and records the failure.
     *
     * <p>Clears the scheduled time and enqueues {@code post.schedule-failed.v1}, which the owner is
     * notified of.
     *
     * @param post the post, locked by the caller
     * @param failure why publication was refused
     */
    void failSchedule(Post post, PostPublicationException failure);
}
