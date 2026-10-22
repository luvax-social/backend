package com.app.modules.users.enums;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Who may tag an account in a post, mirroring {@code tag_permission}. */
public enum TagPermission {
    EVERYONE,
    FOLLOWING,
    NOBODY;

    @JsonCreator
    public static TagPermission fromJson(String value) {
        return value == null || value.isBlank()
                ? null
                : TagPermission.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }
}
