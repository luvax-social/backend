package com.app.modules.message.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.modules.message.entity.Conversation;
import com.app.testsupport.TestContainerImages;

/**
 * Guards against keyset row loss when conversations share a boundary {@code last_message_at},
 * including the transition from the non-null tie-group into the {@code last_message_at IS NULL}
 * group that sorts last. {@code last_message_at} is trigger-maintained and is never mutated by this
 * test after seeding: an in-flight change to the sort key mid-walk is a documented, accepted
 * instability (see {@code ConversationServiceImpl}'s note on most-recently-active ordering),
 * distinct from the row-loss defect this test targets, and mutating it here would conflate the two.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ConversationKeysetRowLossIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private static final OffsetDateTime SHARED_INSTANT =
            OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final int PAGE_SIZE = 2;

    private final ConversationRepository conversationRepository;
    private final JdbcClient jdbcClient;

    ConversationKeysetRowLossIT(
            ConversationRepository conversationRepository, JdbcClient jdbcClient) {
        this.conversationRepository = conversationRepository;
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void myConversations_tieGroupAndNullGroup_pagesEveryRowExactlyOnce() {
        UUID viewer = insertUser("conv_viewer");
        List<UUID> expected = new ArrayList<>();
        // Non-null tie-group: five conversations sharing one last_message_at.
        for (int i = 0; i < 5; i++) {
            UUID conversationId = insertConversation(SHARED_INSTANT);
            insertParticipant(conversationId, viewer);
            expected.add(conversationId);
        }
        // Null group, sorted last by NULLS LAST: five conversations with no activity yet.
        for (int i = 0; i < 5; i++) {
            UUID conversationId = insertConversation(null);
            insertParticipant(conversationId, viewer);
            expected.add(conversationId);
        }

        List<UUID> seen = new ArrayList<>();
        List<Conversation> page =
                conversationRepository.findFirstMyConversations(viewer, "primary", page());
        int guard = 0;
        while (!page.isEmpty() && guard++ < 100) {
            page.forEach(c -> seen.add(c.getId()));
            Conversation last = page.get(page.size() - 1);
            page =
                    conversationRepository.findMyConversationsBefore(
                            viewer, "primary", last.getLastMessageAt(), last.getId(), page());
        }

        assertThat(seen)
                .as(
                        "every conversation in both the tie-group and the null group must appear"
                                + " exactly once")
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void requestFolders_tieGroupPagesEveryRequestExactlyOnce() {
        UUID viewer = insertUser("conv_request_viewer");
        UUID sender = insertUser("conv_request_sender");
        List<UUID> visible = new ArrayList<>();
        List<UUID> hidden = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            visible.add(insertRequest(viewer, sender, false));
            hidden.add(insertRequest(viewer, sender, true));
        }

        assertThat(walk(viewer, "requests")).containsExactlyInAnyOrderElementsOf(visible);
        assertThat(walk(viewer, "hidden_requests")).containsExactlyInAnyOrderElementsOf(hidden);
        assertThat(walk(viewer, "primary")).isEmpty();
        List<UUID> bothFolders = new ArrayList<>(visible);
        bothFolders.addAll(hidden);
        assertThat(walk(sender, "primary")).containsExactlyInAnyOrderElementsOf(bothFolders);
    }

    private List<UUID> walk(UUID viewer, String folder) {
        List<UUID> seen = new ArrayList<>();
        List<Conversation> page =
                conversationRepository.findFirstMyConversations(viewer, folder, page());
        int guard = 0;
        while (!page.isEmpty() && guard++ < 100) {
            page.forEach(c -> seen.add(c.getId()));
            Conversation last = page.get(page.size() - 1);
            page =
                    conversationRepository.findMyConversationsBefore(
                            viewer, folder, last.getLastMessageAt(), last.getId(), page());
        }
        return seen;
    }

    private UUID insertRequest(UUID recipient, UUID requester, boolean hidden) {
        UUID conversationId =
                jdbcClient
                        .sql(
                                "INSERT INTO conversations(last_message_at, request_status,"
                                        + " request_recipient_id, request_hidden)"
                                        + " VALUES (:lastMessageAt, 'pending', :recipient, :hidden)"
                                        + " RETURNING id")
                        .param("lastMessageAt", SHARED_INSTANT)
                        .param("recipient", recipient)
                        .param("hidden", hidden)
                        .query(UUID.class)
                        .single();
        insertParticipant(conversationId, recipient);
        insertParticipant(conversationId, requester);
        return conversationId;
    }

    private static PageRequest page() {
        return PageRequest.of(0, PAGE_SIZE);
    }

    private UUID insertUser(String username) {
        return jdbcClient
                .sql(
                        """
						INSERT INTO users(username, email, display_name)
						VALUES (:username, :email, :displayName)
						RETURNING id
						""")
                .param("username", username)
                .param("email", username + "@example.com")
                .param("displayName", username)
                .query(UUID.class)
                .single();
    }

    private UUID insertConversation(OffsetDateTime lastMessageAt) {
        return jdbcClient
                .sql(
                        "INSERT INTO conversations(last_message_at)"
                                + " VALUES (:lastMessageAt) RETURNING id")
                .param("lastMessageAt", lastMessageAt)
                .query(UUID.class)
                .single();
    }

    private void insertParticipant(UUID conversationId, UUID userId) {
        jdbcClient
                .sql(
                        "INSERT INTO conversation_participants(conversation_id, user_id)"
                                + " VALUES (:conversationId, :userId)")
                .param("conversationId", conversationId)
                .param("userId", userId)
                .update();
    }
}
