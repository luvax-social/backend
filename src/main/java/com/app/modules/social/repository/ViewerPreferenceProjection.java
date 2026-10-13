package com.app.modules.social.repository;

import java.util.UUID;

/**
 * The viewer's own mute and show-fewer choice about one account, returned by {@link
 * UserMuteRepository#findViewerPreferences}.
 *
 * <p>A row comes from exactly one of the two tables, so the mute flags and the show-fewer flag are
 * never true together on the same row; the caller merges rows that name the same account.
 */
public interface ViewerPreferenceProjection {

    UUID getOtherId();

    boolean getMutePosts();

    boolean getMuteStories();

    boolean getShowFewer();
}
