package com.app.modules.recommendation.enums;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Behavioural event types stored in the ClickHouse {@code Enum8} {@code user_events.event_type}.
 *
 * <p>Every value the column declares is listed, because the activity log filters on this column and
 * a filter that cannot name a stored value would be a hole in the read surface. Adding a value
 * means adding it here and, in the same commit, extending the column with a new ClickHouse
 * migration script; the declaration order is the column's ordinal order.
 *
 * <p>Sixteen values have a producer today. {@link #SESSION_START}, {@link #SEARCH} and {@link
 * #PROFILE_VIEW} are written by {@code UserEventRecorder}; the six engagement values ({@link
 * #POST_LIKE}, {@link #POST_SAVE}, {@link #POST_VIEW}, {@link #POST_COMMENT}, {@link #POST_SHARE}
 * and {@link #COMMENT_LIKE}) by the feedback consumer; and the seven interaction control values
 * that map a domain event by the import consumer. The remaining values exist in the column and are
 * not produced yet. See {@code UserEventRecorder} for why the recorder's write set is deliberately
 * small.
 */
public enum UserEventType {
    POST_VIEW,
    POST_LIKE,
    POST_UNLIKE,
    POST_SAVE,
    POST_UNSAVE,
    POST_SHARE,
    POST_COMMENT,
    STORY_VIEW,
    STORY_REPLY,
    PROFILE_VIEW,
    PROFILE_FOLLOW,
    PROFILE_UNFOLLOW,
    SEARCH,
    HASHTAG_CLICK,
    COMMENT_LIKE,
    COMMENT_REPLY,
    MESSAGE_SEND,
    SESSION_START,
    SESSION_END,
    APP_OPEN,
    POST_NOT_INTERESTED,
    POST_NOT_INTERESTED_UNDO,
    ACCOUNT_SHOW_FEWER,
    ACCOUNT_SHOW_FEWER_UNDO,
    ACCOUNT_MUTE,
    ACCOUNT_UNMUTE,
    MESSAGE_REACTION,
    MESSAGE_REACTION_REMOVE,
    MESSAGE_REQUEST_ACCEPT,
    MESSAGE_REQUEST_DELETE,
    COMMENT_HIDDEN,
    COMMENT_UNHIDE,
    INTERACTION_LIMIT_CHANGE;

    @JsonCreator
    public static UserEventType fromJson(String value) {
        return value == null || value.isBlank()
                ? null
                : UserEventType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }
}
