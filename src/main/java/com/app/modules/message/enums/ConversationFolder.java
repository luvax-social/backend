package com.app.modules.message.enums;

import java.util.Locale;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;

/** The three views of a user's conversations, selected by the {@code folder} query parameter. */
public enum ConversationFolder {
    /** Accepted conversations plus the caller's own outgoing requests. */
    PRIMARY,
    /** Incoming pending requests that carry at least one message. */
    REQUESTS,
    /** Incoming pending requests filed as hidden by a limit or a hidden word. */
    HIDDEN_REQUESTS;

    /**
     * Parses the query parameter value.
     *
     * @param value the parameter as sent, or null for the default folder
     * @return the folder, {@link #PRIMARY} when the value is null or blank
     * @throws AppException BAD_REQUEST when the value names no folder
     */
    public static ConversationFolder fromParam(String value) {
        if (value == null || value.isBlank()) {
            return PRIMARY;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AppException(ApiErrorCode.BAD_REQUEST, "Unknown conversation folder");
        }
    }

    /**
     * The folder's name as the native list queries compare it.
     *
     * @return the lowercase name, such as {@code hidden_requests}
     */
    public String sqlName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
