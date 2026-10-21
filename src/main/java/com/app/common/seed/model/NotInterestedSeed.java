package com.app.common.seed.model;

/**
 * One account marking a post as not interesting, as authored in {@code
 * safety/interaction_control.json}. An {@code undone} entry writes no row; its events are replayed
 * through the outbox so the recommender sees the mark and its removal.
 */
public record NotInterestedSeed(String viewer, String postSeedId, int daysAgo, boolean undone) {}
