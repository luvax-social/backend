package com.app.common.seed.model;

/**
 * One place of the registry, as {@code places.json} authors it.
 *
 * @param name the label as a person would have typed it
 * @param status the lifecycle state the place holds at the end of the run: active, banned or
 *     deleted
 * @param history what the registry shows happened to it on the way there: {@code none}, or {@code
 *     unbanned} for an active place an administrator once banned and then restored
 * @param statusNote the administrator justification recorded with a banned or deleted state, or
 *     with the ban that was later lifted; absent for a place nobody has touched
 */
public record PlaceSeed(String name, String status, String history, String statusNote) {}
