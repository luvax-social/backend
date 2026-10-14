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

    // The statement is read from the repository's own @Query annotation. The read starts from the
    // reactions newer than the conversation's last message, so the plan has to reach
    // idx_message_reactions_reacted_at (V147) rather than walk every reaction of every message.
    @Test
    void latestVisible_plan_usesTheReactedAtIndex() throws NoSuchMethodException {
        UUID viewer = insertUser("latest_plan_viewer");
        UUID other = insertUser("latest_plan_other");
        UUID conversation = insertConversation();
        UUID last = insertMessage(conversation, other, NOW, false, false);
        for (int i = 0; i < 300; i++) {
            UUID older = insertMessage(conversation, viewer, NOW.minusHours(2 + i), false, false);
            insertReaction(older, other, "a", NOW.minusHours(1 + i), false);
        }
        insertReaction(last, viewer, "newest", NOW.plusMinutes(1), false);
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

        assertThat(plan).contains("idx_message_reactions_reacted_at");
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
						INSERT INTO message_reactions(message_id, user_id, emoji, reacted_at,
							admin_removed_at)
						VALUES (:message, :user, :emoji, :reactedAt,
							CASE WHEN :adminRemoved THEN :reactedAt END)
						""")
                .param("message", message)
                .param("user", user)
                .param("emoji", emoji)
                .param("reactedAt", reactedAt)
                .param("adminRemoved", adminRemoved)
                .update();
    }
}
