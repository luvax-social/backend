package com.app.modules.post.enums;

/**
 * The feed a viewer's personal interaction preferences are being applied to.
 *
 * <p>The surface decides which preferences apply. Muting an account's posts removes them from every
 * surface. Asking to see fewer posts from an author only shapes recommendations, so it applies to
 * {@link #FOR_YOU} and {@link #FOR_YOU_FALLBACK} and never to {@link #FOLLOWING}, where the viewer
 * chose the accounts themselves. Marking a post not interested removes that post from every surface
 * and so does not depend on this value.
 */
public enum FeedSurface {
    /** The chronological feed of accounts the viewer follows. */
    FOLLOWING,

    /** The ranked feed and Explore, served from the recommender's candidates. */
    FOR_YOU,

    /**
     * The chronological feed served in place of the ranked one, both on its first page and on the
     * pages that continue it.
     *
     * <p>It reads the same posts as {@link #FOLLOWING}, but it is the For You tab, so the show
     * fewer preference still applies.
     */
    FOR_YOU_FALLBACK;

    /**
     * Whether a show-fewer request suppresses its author on this surface.
     *
     * @return true for the two recommendation surfaces, false for the following feed
     */
    public boolean appliesShowFewer() {
        return this != FOLLOWING;
    }
}
