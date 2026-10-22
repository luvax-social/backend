package com.app.modules.post.place.enums;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Lifecycle of a place in the registry, matching the hashtag lifecycle. */
public enum PlaceStatus {

    /** Usable and discoverable, and the only state a place reaches through ordinary creation. */
    ACTIVE,

    /**
     * Hidden from every surface and refused on every post write path.
     *
     * <p>Posts already tagged with the place are untouched; banning hides the name, never the
     * posts.
     */
    BANNED,

    /**
     * Hidden from every surface in the same way as a banned place.
     *
     * <p>Never a row removal: the posts that used the place keep their reference.
     */
    DELETED;

    @JsonCreator
    public static PlaceStatus fromJson(String value) {
        return value == null || value.isBlank()
                ? null
                : PlaceStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }
}
