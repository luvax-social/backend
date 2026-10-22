package com.app.modules.notification.entity.enums;

import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum NotificationType {
    LIKE_POST,
    LIKE_COMMENT,
    COMMENT_POST,
    REPLY_COMMENT,
    FOLLOW,
    FOLLOW_REQUEST,
    MENTION_POST,
    MENTION_COMMENT,
    STORY_VIEW,
    MESSAGE,
    WARNING,
    // Answered, rejected or escalated. The mail carries the response text; this is the
    // in-product half, and like WARNING it is not user-toggleable: an account that could switch it
    // off would ask a question and never be told it had been answered.
    SUPPORT_TICKET_UPDATE,
    POST_REMOVED,
    // The other three enforcement removals. Post removal was the only one with an in-product
    // notification, so a removed comment, story or message simply vanished for an owner who
    // still held a valid session. Like WARNING they are not user-toggleable. Ban and
    // suspension deliberately have no type at all: neither account can load an authenticated
    // surface, so the row would be written and never read.
    COMMENT_REMOVED,
    STORY_REMOVED,
    MESSAGE_REMOVED,
    // A message reaction an administrator removed. Like the other enforcement notices it is not
    // user-toggleable, so the reactor is always told and shown the appeal route.
    REACTION_REMOVED,
    REPORT_POST_REMOVED,
    POST_RESTORED,
    REPORT_DISMISSED,
    // Being tagged in a post. The tag is shown at once (POST_TAG) or waits for the tagged account's
    // approval (POST_TAG_REQUEST). Both follow the recipient's mentions preference, because each
    // is someone involving the recipient in a post.
    POST_TAG,
    POST_TAG_REQUEST,
    // An invitation to co-author a post. Not user-toggleable: it asks a question, and an account
    // that could switch it off would never see the question.
    POST_COLLABORATION_INVITE,
    POST_COLLABORATION_ACCEPTED,
    // A scheduled post that could not be published and went back to drafts. A platform notice with
    // no actor, and not user-toggleable, because it is the only way the owner learns the post did
    // not go out.
    SCHEDULED_POST_FAILED;

    /** The feed filter this type belongs to; written to {@code notifications.category}. */
    public NotificationCategory category() {
        return switch (this) {
            case LIKE_POST, LIKE_COMMENT -> NotificationCategory.LIKE;
            case COMMENT_POST, REPLY_COMMENT -> NotificationCategory.COMMENT;
            case MENTION_POST,
                            MENTION_COMMENT,
                            POST_TAG,
                            POST_TAG_REQUEST,
                            POST_COLLABORATION_INVITE,
                            POST_COLLABORATION_ACCEPTED ->
                    NotificationCategory.MENTION;
            case FOLLOW, FOLLOW_REQUEST -> NotificationCategory.FOLLOW;
            case STORY_VIEW -> NotificationCategory.STORY;
            case MESSAGE -> NotificationCategory.MESSAGE;
            case WARNING,
                            SUPPORT_TICKET_UPDATE,
                            POST_REMOVED,
                            COMMENT_REMOVED,
                            STORY_REMOVED,
                            MESSAGE_REMOVED,
                            REACTION_REMOVED,
                            REPORT_POST_REMOVED,
                            POST_RESTORED,
                            REPORT_DISMISSED,
                            SCHEDULED_POST_FAILED ->
                    NotificationCategory.SYSTEM;
        };
    }

    /**
     * Whether events of this type join one notification per target instead of one row each.
     *
     * <p>Only identical, content-free events aggregate: a like, a story view or a follow says
     * nothing beyond who did it. A comment, a mention, a follow request (each needs its own
     * decision) and every platform notice stay one row per event.
     */
    public boolean isAggregatable() {
        return this == LIKE_POST || this == LIKE_COMMENT || this == STORY_VIEW || this == FOLLOW;
    }

    /**
     * The key that identifies the group an event of this type joins.
     *
     * @param targetId the liked post or comment, or the viewed story; ignored for {@code FOLLOW},
     *     whose target is the recipient
     * @return the key, or null when this type does not aggregate
     */
    public String aggregationKey(UUID targetId) {
        return switch (this) {
            case LIKE_POST, LIKE_COMMENT, STORY_VIEW -> toJson() + ":" + targetId;
            case FOLLOW -> toJson();
            default -> null;
        };
    }

    @JsonCreator
    public static NotificationType fromJson(String value) {
        return value == null || value.isBlank()
                ? null
                : NotificationType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }
}
