package com.app.modules.hashtag.enums;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/** Why a hashtag appears in a trending list. */
public enum TrendingSource {

    /** Ranked highly on the platform-wide snapshot. */
    PLATFORM,

    /** Ranked highly in this user's own affinity, whether or not it is trending platform-wide. */
    AFFINITY,

    /**
     * Adjacent to the user's interests but not among them: it co-occurs on posts with hashtags the
     * user engages with, while the user has no affinity row for it. Reserved slots hold these so
     * the personalised list cannot collapse into a filter bubble of what the user already reads.
     */
    NOVEL;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }
}
