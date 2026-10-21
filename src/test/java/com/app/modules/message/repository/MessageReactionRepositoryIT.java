package com.app.modules.message.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.modules.message.entity.MessageReaction;
import com.app.testsupport.TestContainerImages;

/**
 * Pins the visibility rules of the conversation-list reaction read and the single-statement removal
 * that the removal event is decided from.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class MessageReactionRepositoryIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 3, 1, 12, 0, 0, 0, ZoneOffset.UTC);

    private final MessageReactionRepository reactionRepository;
    private final JdbcClient jdbcClient;

    MessageReactionRepositoryIT(
            MessageReactionRepository reactionRepository, JdbcClient jdbcClient) {
        this.reactionRepository = reactionRepository;
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void latestVisible_reactionOlderThanTheLastMessage_isNotReturned() {
        UUID viewer = insertUser("latest_old_viewer");
        UUID other = insertUser("latest_old_other");
        UUID conversation = insertConversation();
        UUID message = insertMessage(conversation, viewer, NOW.minusHours(2), false, false);
        insertMessage(conversation, other, NOW, false, false);
        insertReaction(message, other, "a", NOW.minusHours(1), false);

        assertThat(latest(viewer, conversation)).isEmpty();
    }

    @Test
    void latestVisible_reactionNewerThanTheLastMessage_isReturnedAndTheNewestWins() {
        UUID viewer = insertUser("latest_new_viewer");
        UUID other = insertUser("latest_new_other");
        UUID conversation = insertConversation();
        UUID first = insertMessage(conversation, viewer, NOW.minusHours(2), false, false);
        UUID second = insertMessage(conversation, other, NOW.minusHours(1), false, false);
        insertReaction(first, other, "older", NOW.plusMinutes(1), false);
        insertReaction(second, viewer, "newest", NOW.plusMinutes(2), false);

        List<MessageReaction> found = latest(viewer, conversation);

        assertThat(found).extracting(MessageReaction::getEmoji).containsExactly("newest");
    }

    @Test
    void latestVisible_tombstonedMessageBlockedUserAndRemovedReaction_areLeftOut() {
        UUID viewer = insertUser("latest_hide_viewer");
        UUID other = insertUser("latest_hide_other");
        UUID blocked = insertUser("latest_hide_blocked");
        UUID conversation = insertConversation();
        UUID deleted = insertMessage(conversation, viewer, NOW.minusHours(5), true, false);
        UUID removed = insertMessage(conversation, viewer, NOW.minusHours(4), false, true);
        UUID shown = insertMessage(conversation, viewer, NOW.minusHours(3), false, false);
        insertReaction(deleted, other, "deleted", NOW.plusMinutes(1), false);
        insertReaction(removed, other, "removed", NOW.plusMinutes(2), false);
        insertReaction(shown, other, "moderated", NOW.plusMinutes(3), true);
        insertReaction(shown, blocked, "blocked", NOW.plusMinutes(4), false);
        jdbcClient
                .sql("INSERT INTO blocks(blocker_id, blocked_id) VALUES (:a, :b)")
                .param("a", viewer)
                .param("b", blocked)
                .update();

        assertThat(latest(viewer, conversation)).isEmpty();
    }

    @Test
    void latestVisible_conversationWithoutMessages_isNotConsidered() {
        UUID viewer = insertUser("latest_empty_viewer");

        assertThat(latest(viewer, insertConversation())).isEmpty();
    }

    @Test
    void latestVisible_requesterReactionOnUnacceptedRequest_isHiddenFromTheRecipient() {
        UUID requester = insertUser("latest_req_requester");
        UUID recipient = insertUser("latest_req_recipient");
        for (String status : List.of("pending", "declined")) {
            UUID conversation = insertRequestConversation(status, recipient);
            UUID message = insertMessage(conversation, requester, NOW.minusHours(1), false, false);
            insertReaction(message, requester, "ping", NOW.plusMinutes(1), false);

            assertThat(latest(recipient, conversation))
                    .as("recipient of a %s request", status)
                    .isEmpty();
            assertThat(latest(requester, conversation))
                    .as("requester of a %s request", status)
                    .extracting(MessageReaction::getEmoji)
                    .containsExactly("ping");
        }
    }

    @Test
    void latestVisible_recipientReactionOnUnacceptedRequest_isShownToBoth() {
        UUID requester = insertUser("latest_rec_requester");
        UUID recipient = insertUser("latest_rec_recipient");
        UUID conversation = insertRequestConversation("pending", recipient);
        UUID message = insertMessage(conversation, requester, NOW.minusHours(1), false, false);
        insertReaction(message, requester, "ping", NOW.plusMinutes(1), false);
        insertReaction(message, recipient, "answer", NOW.plusMinutes(2), false);

        assertThat(latest(recipient, conversation))
                .extracting(MessageReaction::getEmoji)
                .containsExactly("answer");
        assertThat(latest(requester, conversation))
                .extracting(MessageReaction::getEmoji)
                .containsExactly("answer");
    }

    @Test
    void latestVisible_requesterReactionAfterAcceptance_isShownToTheRecipient() {
        UUID requester = insertUser("latest_acc_requester");
        UUID recipient = insertUser("latest_acc_recipient");
        UUID conversation = insertRequestConversation("accepted", recipient);
        UUID message = insertMessage(conversation, requester, NOW.minusHours(1), false, false);
        insertReaction(message, requester, "ping", NOW.plusMinutes(1), false);

        assertThat(latest(recipient, conversation))
                .extracting(MessageReaction::getEmoji)
                .containsExactly("ping");
    }

    @Test
    void latestVisible_olderReactionStillWins_whenTheNewestIsTheRequesters() {
        UUID requester = insertUser("latest_mix_requester");
        UUID recipient = insertUser("latest_mix_recipient");
        UUID conversation = insertRequestConversation("pending", recipient);
        UUID message = insertMessage(conversation, requester, NOW.minusHours(1), false, false);
        UUID second = insertMessage(conversation, recipient, NOW.minusMinutes(30), false, false);
        insertReaction(second, requester, "newest-but-requesters", NOW.plusMinutes(5), false);
        insertReaction(message, recipient, "own", NOW.plusMinutes(1), false);

        assertThat(latest(recipient, conversation))
                .extracting(MessageReaction::getEmoji)
                .containsExactly("own");
    }

    @Test
    void conversationColumn_followsTheMessageAndCascadesWithTheConversation() {
        UUID user = insertUser("column_user");
        UUID conversation = insertConversation();
        UUID message = insertMessage(conversation, user, NOW, false, false);
        insertReaction(message, user, "x", NOW.plusMinutes(1), false);

        assertThat(
                        jdbcClient
                                .sql(
                                        "SELECT conversation_id FROM message_reactions WHERE"
                                                + " message_id = :m")
                                .param("m", message)
                                .query(UUID.class)
                                .single())
                .isEqualTo(conversation);

        jdbcClient.sql("DELETE FROM conversations WHERE id = :c").param("c", conversation).update();

        assertThat(
                        jdbcClient
                                .sql("SELECT COUNT(*) FROM message_reactions WHERE user_id = :u")
                                .param("u", user)
                                .query(Integer.class)
                                .single())
                .isZero();
    }

    @Test
    void deleteReaction_returnsTheRemovedRowIncludingItsModerationState() {
        UUID user = insertUser("delete_user");
        UUID conversation = insertConversation();
        UUID visible = insertMessage(conversation, user, NOW, false, false);
        UUID moderated = insertMessage(conversation, user, NOW.plusMinutes(1), false, false);
        insertReaction(visible, user, "v", NOW, false);
        insertReaction(moderated, user, "m", NOW, true);

        Optional<MessageReaction> first = reactionRepository.deleteReaction(visible, user);
        Optional<MessageReaction> second = reactionRepository.deleteReaction(moderated, user);

        assertThat(first).isPresent();
        assertThat(first.get().getAdminRemovedAt()).isNull();
        assertThat(second).isPresent();
        assertThat(second.get().getAdminRemovedAt()).isNotNull();
        assertThat(reactionRepository.deleteReaction(visible, user)).isEmpty();
        assertThat(
                        jdbcClient
                                .sql("SELECT COUNT(*) FROM message_reactions WHERE user_id = :u")
                                .param("u", user)
                                .query(Integer.class)
                                .single())
                .isZero();
    }

    private List<MessageReaction> latest(UUID viewer, UUID conversation) {
        return reactionRepository.findLatestVisiblePerConversation(List.of(conversation), viewer);
    }

    // The statement is read from the repository's own @Query annotation. The read looks up the
    // newest visible reaction of each listed conversation, so the plan has to reach
    // idx_message_reactions_conversation_reacted (V149) rather than walk the reactions of other
    // conversations.
    @Test
    void latestVisible_plan_usesTheConversationReactedIndex() throws NoSuchMethodException {
        UUID viewer = insertUser("latest_plan_viewer");
        UUID other = insertUser("latest_plan_other");
        UUID conversation = insertConversation();
        UUID last = insertMessage(conversation, other, NOW, false, false);
        for (int i = 0; i < 300; i++) {
            UUID older = insertMessage(conversation, viewer, NOW.minusHours(2 + i), false, false);
            insertReaction(older, other, "a", NOW.minusHours(1 + i), false);
        }
        insertReaction(last, viewer, "newest", NOW.plusMinutes(1), false);
        UUID noise = insertConversation();
        for (int i = 0; i < 300; i++) {
            UUID noisy = insertMessage(noise, other, NOW.minusHours(2 + i), false, false);
            insertReaction(noisy, viewer, "n", NOW.plusMinutes(10 + i), false);
        }
        jdbcClient.sql("ANALYZE message_reactions").update();
        jdbcClient.sql("ANALYZE messages").update();
        jdbcClient.sql("ANALYZE conversations").update();
        jdbcClient.sql("SET LOCAL enable_seqscan = off").update();
        String statement =
                MessageReactionRepository.class
                        .getMethod(
                                "findLatestVisiblePerConversation",
                                java.util.Collection.class,
                                UUID.class)
                        .getAnnotation(org.springframework.data.jpa.repository.Query.class)
                        .value();

        String plan =
                String.join(
                        "\n",
                        jdbcClient
                                .sql("EXPLAIN " + statement)
                                .param("conversationIds", List.of(conversation))
                                .param("viewerId", viewer)
                                .query(String.class)
                                .list());

        assertThat(plan).contains("idx_message_reactions_conversation_reacted");
    }

    private UUID insertUser(String username) {
        return jdbcClient
                .sql(
                        """
						INSERT INTO users(username, email, display_name)
						VALUES (:username, :email, :username)
						RETURNING id
						""")
                .param("username", username)
                .param("email", username + "@example.com")
                .query(UUID.class)
                .single();
    }

    // A request names its recipient (chk_conversations_request_recipient); an accepted one keeps
    // the recipient as history and carries no pending state.
    private UUID insertRequestConversation(String status, UUID recipient) {
        return jdbcClient
                .sql(
                        """
						INSERT INTO conversations(request_status, request_recipient_id)
						VALUES (CAST(:status AS conversation_request_status), :recipient)
						RETURNING id
						""")
                .param("status", status)
                .param("recipient", recipient)
                .query(UUID.class)
                .single();
    }

    private UUID insertConversation() {
        return jdbcClient
                .sql("INSERT INTO conversations DEFAULT VALUES RETURNING id")
                .query(UUID.class)
                .single();
    }

    private UUID insertMessage(
            UUID conversation,
            UUID sender,
            OffsetDateTime createdAt,
            boolean deleted,
            boolean adminRemoved) {
        return jdbcClient
                .sql(
                        """
						INSERT INTO messages(conversation_id, sender_id, message_type, content,
							created_at, is_deleted, deleted_at, admin_removed_at)
						VALUES (:conversation, :sender, 'text', 'hello', :createdAt, :deleted,
							CASE WHEN :deleted THEN :createdAt END,
							CASE WHEN :adminRemoved THEN :createdAt END)
						RETURNING id
						""")
                .param("conversation", conversation)
                .param("sender", sender)
                .param("createdAt", createdAt)
                .param("deleted", deleted)
                .param("adminRemoved", adminRemoved)
                .query(UUID.class)
                .single();
    }

    private void insertReaction(
            UUID message, UUID user, String emoji, OffsetDateTime reactedAt, boolean adminRemoved) {
        jdbcClient
                .sql(
                        """
						INSERT INTO message_reactions(message_id, conversation_id, user_id, emoji,
							reacted_at, admin_removed_at)
						SELECT m.id, m.conversation_id, :user, :emoji, :reactedAt,
							CASE WHEN :adminRemoved THEN :reactedAt END
						FROM messages m WHERE m.id = :message
						""")
                .param("message", message)
                .param("user", user)
                .param("emoji", emoji)
                .param("reactedAt", reactedAt)
                .param("adminRemoved", adminRemoved)
                .update();
    }
}
