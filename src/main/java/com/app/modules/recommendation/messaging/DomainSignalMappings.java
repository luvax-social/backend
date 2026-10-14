package com.app.modules.recommendation.messaging;

import static java.util.Map.entry;

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
 * hidden. Nothing the actor typed (a hidden word, a comment's text) and nothing the event leaves
 * out (the end time of a limit) reaches a row.
 *
 * <p>The not-interested events are not mapped here: they feed Gorse and are written by the feedback
 * consumer.
 */
public final class DomainSignalMappings {

    private static final String ENTITY_USER = "user";
    private static final String ENTITY_COMMENT = "comment";

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
                                    DomainSignalMappings::hiddenCommentMetadata)));

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
