package com.app.modules.recommendation.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.app.common.messaging.exception.PermanentMessageException;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.modules.comment.messaging.CommentEventTypes;
import com.app.modules.message.messaging.MessageEventTypes;
import com.app.modules.recommendation.enums.UserEventType;
import com.app.modules.social.messaging.SocialEventTypes;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Every entry of the domain signal table, read the way the import consumer reads it. */
class DomainSignalMappingsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final OffsetDateTime OCCURRED_AT =
            OffsetDateTime.of(2026, 8, 15, 9, 30, 0, 123_456_000, ZoneOffset.UTC);

    private final UUID actor = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private final UUID post = UUID.randomUUID();

    @Test
    void table_names_exactlyTheEightMappedEvents() {
        assertThat(DomainSignalMappings.BY_EVENT_TYPE.keySet())
                .containsExactlyInAnyOrder(
                        SocialEventTypes.USER_SHOW_FEWER_V1,
                        SocialEventTypes.USER_SHOW_FEWER_UNDONE_V1,
                        SocialEventTypes.USER_MUTED_V1,
                        SocialEventTypes.USER_UNMUTED_V1,
                        SocialEventTypes.USER_INTERACTION_LIMIT_CHANGED_V1,
                        CommentEventTypes.COMMENT_UNHIDDEN_V1,
                        CommentEventTypes.COMMENT_HIDDEN_V1,
                        MessageEventTypes.MESSAGE_REQUEST_RESOLVED_V1);
    }

    @Test
    void table_neverMapsTheImportedEventOrTheNotInterestedPair() {
        assertThat(DomainSignalMappings.BY_EVENT_TYPE)
                .doesNotContainKeys(
                        RecommendationEventTypes.USER_EVENT_IMPORTED_V1,
                        "post.not-interested.v1",
                        "post.not-interested-undone.v1");
    }

    @Test
    void showFewer_isAnAccountEventAboutTheAuthor() {
        ResolvedSignal signal =
                resolve(SocialEventTypes.USER_SHOW_FEWER_V1, Map.of("authorId", target.toString()));

        assertThat(signal.userId()).isEqualTo(actor);
        assertThat(signal.eventType()).isEqualTo(UserEventType.ACCOUNT_SHOW_FEWER);
        assertThat(signal.entityType()).isEqualTo("user");
        assertThat(signal.entityId()).isEqualTo(target);
        assertThat(signal.metadata()).isEmpty();
        assertThat(signal.createdAt()).isEqualTo(OCCURRED_AT);
    }

    @Test
    void showFewerUndone_isAnAccountEventAboutTheAuthor() {
        ResolvedSignal signal =
                resolve(
                        SocialEventTypes.USER_SHOW_FEWER_UNDONE_V1,
                        Map.of("authorId", target.toString()));

        assertThat(signal.eventType()).isEqualTo(UserEventType.ACCOUNT_SHOW_FEWER_UNDO);
        assertThat(signal.entityType()).isEqualTo("user");
        assertThat(signal.entityId()).isEqualTo(target);
        assertThat(signal.metadata()).isEmpty();
    }

    @Test
    void muted_carriesBothScopesAndNothingElse() {
        ResolvedSignal signal =
                resolve(
                        SocialEventTypes.USER_MUTED_V1,
                        Map.of(
                                "targetUserId",
                                target.toString(),
                                "mutePosts",
                                true,
                                "muteStories",
                                false));

        assertThat(signal.eventType()).isEqualTo(UserEventType.ACCOUNT_MUTE);
        assertThat(signal.entityType()).isEqualTo("user");
        assertThat(signal.entityId()).isEqualTo(target);
        assertThat(json(signal.metadata())).isEqualTo(json("{\"posts\":true,\"stories\":false}"));
    }

    @Test
    void unmuted_isAnAccountEventAboutTheFormerlyMutedAccount() {
        ResolvedSignal signal =
                resolve(
                        SocialEventTypes.USER_UNMUTED_V1,
                        Map.of("targetUserId", target.toString()));

        assertThat(signal.eventType()).isEqualTo(UserEventType.ACCOUNT_UNMUTE);
        assertThat(signal.entityType()).isEqualTo("user");
        assertThat(signal.entityId()).isEqualTo(target);
        assertThat(signal.metadata()).isEmpty();
    }

    @Test
    void interactionLimitChanged_isAboutTheAccountItselfAndOmitsTheEndTime() {
        ResolvedSignal signal =
                resolve(
                        SocialEventTypes.USER_INTERACTION_LIMIT_CHANGED_V1,
                        Map.of(
                                "limitNonFollowers",
                                true,
                                "limitRecentFollowers",
                                false,
                                "expiresAt",
                                "2026-08-16T09:30:00Z"));

        assertThat(signal.eventType()).isEqualTo(UserEventType.INTERACTION_LIMIT_CHANGE);
        assertThat(signal.entityType()).isEqualTo("user");
        assertThat(signal.entityId()).isEqualTo(actor);
        assertThat(json(signal.metadata()))
                .isEqualTo(
                        json(
                                "{\"nonFollowers\":true,\"recentFollowers\":false,"
                                        + "\"active\":true}"));
        assertThat(signal.metadata()).doesNotContain("expires").doesNotContain("2026-08-16");
    }

    @Test
    void interactionLimitChanged_withBothTogglesOff_isNotActive() {
        ResolvedSignal signal =
                resolve(
                        SocialEventTypes.USER_INTERACTION_LIMIT_CHANGED_V1,
                        Map.of("limitNonFollowers", false, "limitRecentFollowers", false));

        assertThat(json(signal.metadata()))
                .isEqualTo(
                        json(
                                "{\"nonFollowers\":false,\"recentFollowers\":false,"
                                        + "\"active\":false}"));
    }

    @Test
    void interactionLimitChanged_withOnlyTheRecentFollowerToggle_isActive() {
        ResolvedSignal signal =
                resolve(
                        SocialEventTypes.USER_INTERACTION_LIMIT_CHANGED_V1,
                        Map.of("limitNonFollowers", false, "limitRecentFollowers", true));

        assertThat(json(signal.metadata()).get("active").asBoolean()).isTrue();
    }

    @Test
    void commentUnhidden_isACommentEventWithNoMetadata() {
        UUID commentId = UUID.randomUUID();
        Map<String, Object> data = new HashMap<>();
        data.put("postId", post.toString());
        data.put("commentId", commentId.toString());
        data.put("commentOwnerId", target.toString());
        data.put("depth", 1);
        data.put("comment", Map.of("content", "released text"));

        ResolvedSignal signal = resolve(CommentEventTypes.COMMENT_UNHIDDEN_V1, data);

        assertThat(signal.userId()).isEqualTo(actor);
        assertThat(signal.eventType()).isEqualTo(UserEventType.COMMENT_UNHIDE);
        assertThat(signal.entityType()).isEqualTo("comment");
        assertThat(signal.entityId()).isEqualTo(commentId);
        assertThat(signal.metadata()).isEmpty();
    }

    @Test
    void commentHidden_carriesTheReasonOnly() {
        UUID commentId = UUID.randomUUID();

        for (String reason : Set.of("hidden_words", "limited_interaction", "hidden_parent")) {
            ResolvedSignal signal =
                    resolve(
                            CommentEventTypes.COMMENT_HIDDEN_V1,
                            Map.of(
                                    "postId",
                                    post.toString(),
                                    "commentId",
                                    commentId.toString(),
                                    "reason",
                                    reason));

            assertThat(signal.eventType()).isEqualTo(UserEventType.COMMENT_HIDDEN);
            assertThat(signal.entityType()).isEqualTo("comment");
            assertThat(signal.entityId()).isEqualTo(commentId);
            assertThat(json(signal.metadata())).isEqualTo(json("{\"reason\":\"" + reason + "\"}"));
        }
    }

    @Test
    void commentHidden_withAReasonOutsideTheClosedSet_isPermanentlyRejected() {
        Map<String, Object> data =
                Map.of(
                        "postId",
                        post.toString(),
                        "commentId",
                        UUID.randomUUID().toString(),
                        "reason",
                        "\"},{\"injected\":\"");

        assertThatThrownBy(() -> resolve(CommentEventTypes.COMMENT_HIDDEN_V1, data))
                .isInstanceOf(PermanentMessageException.class);
    }

    @Test
    void requestResolved_accepted_isAnAcceptRowAboutTheConversation() {
        UUID conversationId = UUID.randomUUID();

        ResolvedSignal signal =
                resolve(
                        MessageEventTypes.MESSAGE_REQUEST_RESOLVED_V1,
                        Map.of(
                                "conversationId",
                                conversationId.toString(),
                                "resolution",
                                "accepted"));

        assertThat(signal.userId()).isEqualTo(actor);
        assertThat(signal.eventType()).isEqualTo(UserEventType.MESSAGE_REQUEST_ACCEPT);
        assertThat(signal.entityType()).isEqualTo("conversation");
        assertThat(signal.entityId()).isEqualTo(conversationId);
        assertThat(signal.metadata()).isEmpty();
    }

    @Test
    void requestResolved_deletedAndBlocked_areDeleteRowsThatSayWhetherTheSenderWasBlocked() {
        UUID conversationId = UUID.randomUUID();

        for (String resolution : List.of("deleted", "blocked")) {
            ResolvedSignal signal =
                    resolve(
                            MessageEventTypes.MESSAGE_REQUEST_RESOLVED_V1,
                            Map.of(
                                    "conversationId",
                                    conversationId.toString(),
                                    "resolution",
                                    resolution));

            assertThat(signal.eventType()).isEqualTo(UserEventType.MESSAGE_REQUEST_DELETE);
            assertThat(signal.entityId()).isEqualTo(conversationId);
            assertThat(json(signal.metadata()).get("blocked").asBoolean())
                    .isEqualTo(resolution.equals("blocked"));
        }
    }

    @Test
    void requestResolved_withAnUnknownResolution_isPermanentlyRejected() {
        Map<String, Object> data =
                Map.of("conversationId", UUID.randomUUID().toString(), "resolution", "archived");

        assertThatThrownBy(() -> resolve(MessageEventTypes.MESSAGE_REQUEST_RESOLVED_V1, data))
                .isInstanceOf(PermanentMessageException.class);
    }

    @Test
    void anyMapping_withoutAnActor_isPermanentlyRejected() {
        DomainEventEnvelope event =
                new DomainEventEnvelope(
                        UUID.randomUUID(),
                        SocialEventTypes.USER_SHOW_FEWER_V1,
                        OCCURRED_AT,
                        null,
                        "user",
                        actor,
                        Map.of("authorId", target.toString()));

        assertThatThrownBy(
                        () ->
                                DomainSignalMappings.BY_EVENT_TYPE
                                        .get(SocialEventTypes.USER_SHOW_FEWER_V1)
                                        .resolve(event))
                .isInstanceOf(PermanentMessageException.class)
                .hasMessageContaining("actor");
    }

    @Test
    void anyMapping_withoutAnOccurredAt_isPermanentlyRejected() {
        DomainEventEnvelope event =
                new DomainEventEnvelope(
                        UUID.randomUUID(),
                        SocialEventTypes.USER_SHOW_FEWER_V1,
                        null,
                        actor,
                        "user",
                        actor,
                        Map.of("authorId", target.toString()));

        assertThatThrownBy(
                        () ->
                                DomainSignalMappings.BY_EVENT_TYPE
                                        .get(SocialEventTypes.USER_SHOW_FEWER_V1)
                                        .resolve(event))
                .isInstanceOf(PermanentMessageException.class);
    }

    @Test
    void entityKeyMissingOrNotAnId_isPermanentlyRejected() {
        assertThatThrownBy(() -> resolve(SocialEventTypes.USER_SHOW_FEWER_V1, Map.of()))
                .isInstanceOf(PermanentMessageException.class);
        assertThatThrownBy(
                        () ->
                                resolve(
                                        SocialEventTypes.USER_SHOW_FEWER_V1,
                                        Map.of("authorId", "not-a-uuid")))
                .isInstanceOf(PermanentMessageException.class);
    }

    @Test
    void scopeFlagMissingOrNotABoolean_isPermanentlyRejected() {
        assertThatThrownBy(
                        () ->
                                resolve(
                                        SocialEventTypes.USER_MUTED_V1,
                                        Map.of("targetUserId", target.toString())))
                .isInstanceOf(PermanentMessageException.class);
        assertThatThrownBy(
                        () ->
                                resolve(
                                        SocialEventTypes.USER_MUTED_V1,
                                        Map.of(
                                                "targetUserId",
                                                target.toString(),
                                                "mutePosts",
                                                "yes",
                                                "muteStories",
                                                true)))
                .isInstanceOf(PermanentMessageException.class);
    }

    private ResolvedSignal resolve(String eventType, Map<String, Object> data) {
        DomainEventEnvelope event =
                new DomainEventEnvelope(
                        UUID.randomUUID(), eventType, OCCURRED_AT, actor, "x", actor, data);
        return DomainSignalMappings.BY_EVENT_TYPE.get(eventType).resolve(event);
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }
}
