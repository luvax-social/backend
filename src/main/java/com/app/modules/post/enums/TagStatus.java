package com.app.modules.post.enums;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Whether the tagged person has accepted a tag.
 *
 * <p>A {@code PENDING} tag is visible only to the post owner and the tagged person; an {@code
 * APPROVED} tag is shown to everyone who can see the post.
 */
public enum TagStatus {
    PENDING,
    APPROVED;

    @JsonCreator
    public static TagStatus fromJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return TagStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }
}
