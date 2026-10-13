package com.app.modules.users.enums;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Who may start a direct conversation with an account, mirroring {@code message_permission}. */
public enum MessagePermission {
    EVERYONE,
    FOLLOWING,
    NOBODY;

    @JsonCreator
    public static MessagePermission fromJson(String value) {
        return value == null || value.isBlank()
                ? null
                : MessagePermission.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }
}
