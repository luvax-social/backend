package com.app.modules.admin.enums;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Moderation action types persisted in the PostgreSQL {@code admin_action_type} enum. */
public enum AdminActionType {
    BAN_USER,
    UNBAN_USER,
    SUSPEND_USER,
    UNSUSPEND_USER,
    REMOVE_POST,
    RESTORE_POST,
    REMOVE_COMMENT,
    RESTORE_COMMENT,
    REMOVE_STORY,
    RESTORE_STORY,
    REMOVE_MESSAGE,
    RESTORE_MESSAGE,
    RESOLVE_REPORT,
    DISMISS_REPORT,
    CHANGE_USER_ROLE,
    WARN_USER,
    REVOKE_WARNING,
    ISSUE_STRIKE,
    REVOKE_STRIKE,
    ESCALATE_REPORT,
    FORCE_LOGOUT,
    REVOKE_SESSION,
    // Support centre. Claiming a ticket is deliberately not audited: it is a queue mechanic rather
    // than a decision about a person, and a row for every claim would bury the rows that record
    // verdicts.
    RESPOND_SUPPORT_TICKET,
    REJECT_SUPPORT_TICKET,
    ESCALATE_SUPPORT_TICKET,
    CREATE_HASHTAG,
    EDIT_HASHTAG,
    BAN_HASHTAG,
    UNBAN_HASHTAG,
    DELETE_HASHTAG,
    PIN_HASHTAG,
    UNPIN_HASHTAG,
    GRANT_VERIFICATION,
    REJECT_VERIFICATION,
    REVOKE_VERIFICATION;

    @JsonCreator
    public static AdminActionType fromJson(String value) {
        return value == null || value.isBlank()
                ? null
                : AdminActionType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }
}
