package com.app.modules.recommendation.enums;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

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
 * <p>Thirty values have a producer today. {@link #SESSION_START}, {@link #SEARCH} and {@link
 * #PROFILE_VIEW} are written by {@code UserEventRecorder}; the six engagement values ({@link
 * #POST_LIKE}, {@link #POST_SAVE}, {@link #POST_VIEW}, {@link #POST_COMMENT}, {@link #POST_SHARE}
 * and {@link #COMMENT_LIKE}) by the feedback consumer; and the seven interaction control values and
 * the fourteen post creation values (publishing, scheduling, drafts, archiving, tags, collaboration
 * and mentions) that map a domain event by the import consumer. The remaining values exist in the
 * column and are not produced yet. See {@code UserEventRecorder} for why the recorder's write set
 * is deliberately small.
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
    INTERACTION_LIMIT_CHANGE,
    POST_PUBLISH,
    POST_SCHEDULE,
    POST_SCHEDULE_FAILED,
    POST_DRAFT_SAVE,
    POST_ARCHIVE,
    POST_UNARCHIVE,
    POST_TAG_ADD,
    POST_TAG_APPROVE,
    POST_TAG_REMOVE,
    POST_COLLAB_INVITE,
    POST_COLLAB_ACCEPT,
    POST_COLLAB_DECLINE,
    POST_COLLAB_LEAVE,
    POST_MENTION;

    /**
     * The signals that record an account's private interaction preferences.
     *
     * <p>They stay in ClickHouse so recommendation insights can use them, and no other reader
     * changes. The administrator activity log is the one read that must never return them: what an
     * account muted, asked to see less of, marked not interesting, or who it limited is not
     * something a moderator browsing behaviour should be able to read back.
     */
    private static final Set<UserEventType> PRIVATE_PREFERENCE_SIGNALS =
            Collections.unmodifiableSet(
                    EnumSet.of(
                            ACCOUNT_MUTE,
                            ACCOUNT_UNMUTE,
                            ACCOUNT_SHOW_FEWER,
                            ACCOUNT_SHOW_FEWER_UNDO,
                            POST_NOT_INTERESTED,
                            POST_NOT_INTERESTED_UNDO,
                            INTERACTION_LIMIT_CHANGE));

    /**
     * Whether this value records an account's private interaction preference.
     *
     * @return true when the administrator activity log must neither return nor accept it
     */
    public boolean isPrivatePreferenceSignal() {
        return PRIVATE_PREFERENCE_SIGNALS.contains(this);
    }

    /**
     * The private preference signals, for the activity log's exclusion predicate and its filter
     * vocabulary.
     *
     * @return an unmodifiable set in declaration order
     */
    public static Set<UserEventType> privatePreferenceSignals() {
        return PRIVATE_PREFERENCE_SIGNALS;
    }

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
