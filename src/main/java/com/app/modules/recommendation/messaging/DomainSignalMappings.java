package com.app.modules.recommendation.messaging;

import static java.util.Map.entry;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.app.common.messaging.exception.PermanentMessageException;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.modules.comment.enums.CommentHiddenReason;
import com.app.modules.comment.messaging.CommentEventTypes;
import com.app.modules.message.messaging.MessageEventTypes;
import com.app.modules.message.util.ReactionEmojiValidator;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.service.PostPublicationException;
import com.app.modules.recommendation.enums.UserEventType;
import com.app.modules.social.messaging.SocialEventTypes;

/**
 * The table that turns domain events into {@code user_events} rows, keyed by event type, which is
 * also the routing key.
 *
 * <p>This is the one place an interaction control signal is described. {@code
 * UserEventImportConsumer} reads every entry, and {@code RecommendationRabbitBindingConfig} binds
 * the import queue to the same keys, so a signal exists only where it is both mapped and bound.
 * Adding a signal means one entry here and one binding there; a test fails when the two disagree.
 *
 * <p>Metadata is built only from fields the event already carries and that the actor chose for
 * themselves: the scope of a mute, the toggles of an interaction limit, the reason a comment was
 * hidden, whether a deleted message request also blocked its sender. Nothing the actor typed (a
 * hidden word, a comment's text) and nothing the event leaves out (the end time of a limit) reaches
 * a row.
 *
 * <p>The not-interested events are not mapped here: they feed Gorse and are written by the feedback
 * consumer.
 */
public final class DomainSignalMappings {

    private static final String ENTITY_USER = "user";
    private static final String ENTITY_COMMENT = "comment";
    private static final String ENTITY_CONVERSATION = "conversation";
    private static final String ENTITY_MESSAGE = "message";
    private static final String ENTITY_POST = "post";

    private static final Set<String> PUBLISH_SOURCES = Set.of("owner", "scheduler");

    private static final Set<String> SCHEDULE_FAILURE_REASONS =
            Stream.of(PostPublicationException.Reason.values())
                    .map(reason -> reason.name().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());

    private static final Set<String> HIDDEN_REASONS =
            Stream.of(CommentHiddenReason.values())
                    .map(CommentHiddenReason::value)
                    .collect(Collectors.toUnmodifiableSet());

    /** Mapped event types, which are the routing keys the import queue must be bound to. */
    public static final Map<String, SignalMapping> BY_EVENT_TYPE =
            Map.ofEntries(
                    entry(
                            SocialEventTypes.USER_SHOW_FEWER_V1,
                            new SignalMapping(
                                    UserEventType.ACCOUNT_SHOW_FEWER,
                                    ENTITY_USER,
                                    dataId("authorId"),
                                    noMetadata())),
                    entry(
                            SocialEventTypes.USER_SHOW_FEWER_UNDONE_V1,
                            new SignalMapping(
                                    UserEventType.ACCOUNT_SHOW_FEWER_UNDO,
                                    ENTITY_USER,
                                    dataId("authorId"),
                                    noMetadata())),
                    entry(
                            SocialEventTypes.USER_MUTED_V1,
                            new SignalMapping(
                                    UserEventType.ACCOUNT_MUTE,
                                    ENTITY_USER,
                                    dataId("targetUserId"),
                                    DomainSignalMappings::muteMetadata)),
                    entry(
                            SocialEventTypes.USER_UNMUTED_V1,
                            new SignalMapping(
                                    UserEventType.ACCOUNT_UNMUTE,
                                    ENTITY_USER,
                                    dataId("targetUserId"),
                                    noMetadata())),
                    entry(
                            SocialEventTypes.USER_INTERACTION_LIMIT_CHANGED_V1,
                            new SignalMapping(
                                    UserEventType.INTERACTION_LIMIT_CHANGE,
                                    ENTITY_USER,
                                    DomainEventEnvelope::actorId,
                                    DomainSignalMappings::interactionLimitMetadata)),
                    entry(
                            CommentEventTypes.COMMENT_UNHIDDEN_V1,
                            new SignalMapping(
                                    UserEventType.COMMENT_UNHIDE,
                                    ENTITY_COMMENT,
                                    dataId("commentId"),
                                    noMetadata())),
                    entry(
                            CommentEventTypes.COMMENT_HIDDEN_V1,
                            new SignalMapping(
                                    UserEventType.COMMENT_HIDDEN,
                                    ENTITY_COMMENT,
                                    dataId("commentId"),
                                    DomainSignalMappings::hiddenCommentMetadata)),
                    entry(
                            MessageEventTypes.MESSAGE_REACTION_CHANGED_V1,
                            new SignalMapping(
                                    DomainSignalMappings::reactionType,
                                    ENTITY_MESSAGE,
                                    dataId("messageId"),
                                    DomainSignalMappings::reactionMetadata)),
                    entry(
                            MessageEventTypes.MESSAGE_REQUEST_RESOLVED_V1,
                            new SignalMapping(
                                    DomainSignalMappings::requestResolutionType,
                                    ENTITY_CONVERSATION,
                                    dataId("conversationId"),
                                    DomainSignalMappings::requestResolutionMetadata)),
                    entry(
                            PostEventTypes.POST_PUBLISHED_V1,
                            new SignalMapping(
                                    UserEventType.POST_PUBLISH,
                                    ENTITY_POST,
                                    dataId("postId"),
                                    DomainSignalMappings::publishedMetadata)),
                    entry(
                            PostEventTypes.POST_SCHEDULED_V1,
                            new SignalMapping(
                                    UserEventType.POST_SCHEDULE,
                                    ENTITY_POST,
                                    dataId("postId"),
                                    DomainSignalMappings::scheduledMetadata)),
                    entry(
                            PostEventTypes.POST_SCHEDULE_FAILED_V1,
                            new SignalMapping(
                                    UserEventType.POST_SCHEDULE_FAILED,
                                    ENTITY_POST,
                                    dataId("postId"),
                                    DomainSignalMappings::scheduleFailedMetadata)),
                    entry(
                            PostEventTypes.POST_DRAFT_SAVED_V1,
                            new SignalMapping(
                                    UserEventType.POST_DRAFT_SAVE,
                                    ENTITY_POST,
                                    dataId("postId"),
                                    noMetadata())),
                    entry(
                            PostEventTypes.POST_ARCHIVED_V1,
                            new SignalMapping(
                                    UserEventType.POST_ARCHIVE,
                                    ENTITY_POST,
                                    dataId("postId"),
                                    noMetadata())),
                    entry(
                            PostEventTypes.POST_UNARCHIVED_V1,
                            new SignalMapping(
                                    UserEventType.POST_UNARCHIVE,
                                    ENTITY_POST,
                                    dataId("postId"),
                                    noMetadata())),
                    entry(
                            PostEventTypes.POST_TAG_CREATED_V1,
                            new SignalMapping(
                                    UserEventType.POST_TAG_ADD,
                                    ENTITY_POST,
                                    dataId("postId"),
                                    noMetadata())),
                    entry(
                            PostEventTypes.POST_TAG_APPROVED_V1,
                            new SignalMapping(
                                    UserEventType.POST_TAG_APPROVE,
                                    ENTITY_POST,
                                    dataId("postId"),
                                    noMetadata())),
                    entry(
                            PostEventTypes.POST_TAG_REMOVED_V1,
                            new SignalMapping(
                                    UserEventType.POST_TAG_REMOVE,
                                    ENTITY_POST,
                                    dataId("postId"),
                                    noMetadata())),
                    entry(
                            PostEventTypes.POST_COLLABORATION_INVITED_V1,
                            new SignalMapping(
                                    UserEventType.POST_COLLAB_INVITE,
                                    ENTITY_POST,
                                    dataId("postId"),
                                    noMetadata())),
                    entry(
                            PostEventTypes.POST_COLLABORATION_ACCEPTED_V1,
                            new SignalMapping(
                                    UserEventType.POST_COLLAB_ACCEPT,
                                    ENTITY_POST,
                                    dataId("postId"),
                                    noMetadata())),
                    entry(
                            PostEventTypes.POST_COLLABORATION_DECLINED_V1,
                            new SignalMapping(
                                    UserEventType.POST_COLLAB_DECLINE,
                                    ENTITY_POST,
                                    dataId("postId"),
                                    noMetadata())),
                    entry(
                            PostEventTypes.POST_COLLABORATION_LEFT_V1,
                            new SignalMapping(
                                    UserEventType.POST_COLLAB_LEAVE,
                                    ENTITY_POST,
                                    dataId("postId"),
                                    noMetadata())),
                    entry(
                            PostEventTypes.POST_MENTION_CREATED_V1,
                            new SignalMapping(
                                    UserEventType.POST_MENTION,
                                    ENTITY_USER,
                                    dataId("mentionedUserId"),
                                    noMetadata())));

    private DomainSignalMappings() {}

    private static Function<DomainEventEnvelope, UUID> dataId(String key) {
        return event -> {
            Object value = data(event).get(key);
            if (value == null) {
                throw new PermanentMessageException(
                        "Signal " + event.eventType() + " has no " + key);
            }
            try {
                return UUID.fromString(value.toString());
            } catch (IllegalArgumentException ex) {
                throw new PermanentMessageException(
                        "Signal " + event.eventType() + " has a malformed " + key, ex);
            }
        };
    }

    private static Function<DomainEventEnvelope, String> noMetadata() {
        return event -> "";
    }

    // Whether the owner pressed publish or the scheduler did is the owner's own choice, and it is
    // checked against the closed set before it is spliced into JSON.
    private static String publishedMetadata(DomainEventEnvelope event) {
        Object source = data(event).get("source");
        if (source == null || !PUBLISH_SOURCES.contains(source.toString())) {
            throw new PermanentMessageException(
                    "Signal " + event.eventType() + " has an unknown source");
        }
        return "{\"source\":\"" + source + "\"}";
    }

    // The lead time is the owner's own choice of when the post goes out. Only a whole number of
    // minutes reaches the row.
    private static String scheduledMetadata(DomainEventEnvelope event) {
        Object lead = data(event).get("leadMinutes");
        if (!(lead instanceof Number number)) {
            throw new PermanentMessageException(
                    "Signal " + event.eventType() + " has no leadMinutes");
        }
        return "{\"leadMinutes\":" + number.longValue() + "}";
    }

    // Checked against the closed set before it is spliced into JSON, so a malformed event can never
    // inject structure into a stored row.
    private static String scheduleFailedMetadata(DomainEventEnvelope event) {
        Object reason = data(event).get("reason");
        if (reason == null || !SCHEDULE_FAILURE_REASONS.contains(reason.toString())) {
            throw new PermanentMessageException(
                    "Signal " + event.eventType() + " has an unknown reason");
        }
        return "{\"reason\":\"" + reason + "\"}";
    }

    private static String muteMetadata(DomainEventEnvelope event) {
        return "{\"posts\":"
                + flag(event, "mutePosts")
                + ",\"stories\":"
                + flag(event, "muteStories")
                + "}";
    }

    // The limit's end time is deliberately not copied: the row records that a limit was turned on,
    // changed or off, and nothing about when the account meant it to lapse.
    private static String interactionLimitMetadata(DomainEventEnvelope event) {
        boolean nonFollowers = flag(event, "limitNonFollowers");
        boolean recentFollowers = flag(event, "limitRecentFollowers");
        return "{\"nonFollowers\":"
                + nonFollowers
                + ",\"recentFollowers\":"
                + recentFollowers
                + ",\"active\":"
                + (nonFollowers || recentFollowers)
                + "}";
    }

    // Checked against the closed set before it is spliced into JSON, so a malformed event can never
    // inject structure into a stored row.
    private static String hiddenCommentMetadata(DomainEventEnvelope event) {
        Object reason = data(event).get("reason");
        if (reason == null || !HIDDEN_REASONS.contains(reason.toString())) {
            throw new PermanentMessageException(
                    "Signal " + event.eventType() + " has an unknown hidden reason");
        }
        return "{\"reason\":\"" + reason + "\"}";
    }

    // Accepting is one signal and deleting is another, whether or not the recipient also blocked
    // the sender; the closed set is checked so a malformed event is rejected, not guessed at.
    private static UserEventType requestResolutionType(DomainEventEnvelope event) {
        return switch (resolution(event)) {
            case "accepted" -> UserEventType.MESSAGE_REQUEST_ACCEPT;
            case "deleted", "blocked" -> UserEventType.MESSAGE_REQUEST_DELETE;
            default ->
                    throw new PermanentMessageException(
                            "Signal " + event.eventType() + " has an unknown resolution");
        };
    }

    private static String requestResolutionMetadata(DomainEventEnvelope event) {
        return switch (resolution(event)) {
            case "accepted" -> "";
            case "deleted" -> "{\"blocked\":false}";
            case "blocked" -> "{\"blocked\":true}";
            default ->
                    throw new PermanentMessageException(
                            "Signal " + event.eventType() + " has an unknown resolution");
        };
    }

    // A reaction that was set carries its emoji and one that was removed carries none, so the
    // absence of an emoji is what makes the row a removal.
    private static UserEventType reactionType(DomainEventEnvelope event) {
        return data(event).get("emoji") == null
                ? UserEventType.MESSAGE_REACTION_REMOVE
                : UserEventType.MESSAGE_REACTION;
    }

    // The emoji is checked against the same single-emoji rule the write path enforces before it is
    // spliced into JSON, so a malformed event can never inject structure into a stored row. A valid
    // emoji contains no quote, backslash or control character.
    private static String reactionMetadata(DomainEventEnvelope event) {
        Object emoji = data(event).get("emoji");
        if (emoji == null) {
            return "";
        }
        if (!ReactionEmojiValidator.isSingleEmoji(emoji.toString())) {
            throw new PermanentMessageException(
                    "Signal " + event.eventType() + " has an emoji that is not a single emoji");
        }
        return "{\"emoji\":\"" + emoji + "\"}";
    }

    private static String resolution(DomainEventEnvelope event) {
        Object value = data(event).get("resolution");
        if (value == null) {
            throw new PermanentMessageException(
                    "Signal " + event.eventType() + " has no resolution");
        }
        return value.toString();
    }

    private static boolean flag(DomainEventEnvelope event, String key) {
        if (data(event).get(key) instanceof Boolean value) {
            return value;
        }
        throw new PermanentMessageException(
                "Signal " + event.eventType() + " has no boolean " + key);
    }

    private static Map<String, Object> data(DomainEventEnvelope event) {
        return event.data() == null ? Map.of() : event.data();
    }
}
