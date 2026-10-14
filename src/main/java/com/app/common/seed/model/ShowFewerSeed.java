package com.app.common.seed.model;

/**
 * One account asking to see less of another author, as authored in {@code
 * safety/interaction_control.json}. An {@code undone} entry writes no row and only produces the
 * signals of a preference that was set and then taken back.
 */
public record ShowFewerSeed(String viewer, String author, int daysAgo, boolean undone) {}
