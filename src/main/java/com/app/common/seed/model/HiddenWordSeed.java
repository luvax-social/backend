package com.app.common.seed.model;

/**
 * One Hidden Words entry, as authored in {@code safety/interaction_control.json}.
 *
 * <p>A non-null {@code owner} is a personal entry. A null {@code owner} is a platform entry that
 * {@code admin} created {@code daysAgo} days before the seed clock, with {@code reason} recorded on
 * the audit row. {@code previousPhrase} marks a platform entry an administrator later edited, and
 * {@code deleted} marks one that was created and later removed, so it leaves no row but keeps its
 * audit history.
 */
public record HiddenWordSeed(
        String owner,
        String phrase,
        String previousPhrase,
        boolean deleted,
        String admin,
        int daysAgo,
        String reason) {}
