package com.app.common.seed.model;

/**
 * One account muting another, as authored in {@code safety/interaction_control.json}.
 *
 * <p>An {@code undone} entry writes no {@code user_mutes} row, because an unmute deletes the row;
 * it only produces the mute and unmute signals that preceded it.
 */
public record MuteSeed(
        String viewer,
        String target,
        boolean mutePosts,
        boolean muteStories,
        int daysAgo,
        boolean undone) {}
