package com.app.modules.post.enums;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum PostStatus {
    DRAFT,
    SCHEDULED,
    PUBLISHED,
    ARCHIVED,
    REMOVED;

    @JsonCreator
    public static PostStatus fromJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return PostStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }
}
