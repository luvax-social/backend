package com.app.modules.post.enums;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where an invitation to co-author a post stands.
 *
 * <p>{@code INVITED} and {@code ACCEPTED} are the live states and count against the per-post cap;
 * the other three are kept as history so a post's performance can still be attributed to someone
 * who held a share of it.
 */
public enum CollaborationStatus {
    INVITED,
    ACCEPTED,
    DECLINED,
    LEFT,
    REMOVED;

    /** Whether this status counts against the cap on co-authors of one post. */
    public boolean isLive() {
        return this == INVITED || this == ACCEPTED;
    }

    @JsonCreator
    public static CollaborationStatus fromJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return CollaborationStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }
}
