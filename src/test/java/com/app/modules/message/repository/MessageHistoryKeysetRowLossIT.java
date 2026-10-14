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
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.modules.message.entity.Message;
import com.app.testsupport.TestContainerImages;

/**
 * Guards against keyset row loss when messages share a {@code created_at} microsecond across a page
 * boundary: a page keyed on the timestamp alone skips the rest of the tie group, and one keyed on
 * {@code <=} repeats it.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class MessageHistoryKeysetRowLossIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private static final OffsetDateTime SHARED_INSTANT =
            OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 123456000, ZoneOffset.UTC);
    private static final int PAGE_SIZE = 2;

    private final MessageRepository messageRepository;
    private final JdbcClient jdbcClient;

    MessageHistoryKeysetRowLossIT(MessageRepository messageRepository, JdbcClient jdbcClient) {
        this.messageRepository = messageRepository;
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void history_tieGroupStraddlingPageBoundary_pagesEveryMessageExactlyOnce() {
        UUID sender = insertUser("history_sender");
        UUID conversationId = insertConversation();
        List<UUID> expected = new ArrayList<>();
        expected.add(insertMessage(conversationId, sender, SHARED_INSTANT.plusMinutes(1)));
        for (int i = 0; i < 5; i++) {
            expected.add(insertMessage(conversationId, sender, SHARED_INSTANT));
        }
        expected.add(insertMessage(conversationId, sender, SHARED_INSTANT.minusMinutes(1)));

        List<UUID> seen = new ArrayList<>();
        List<Message> page =
                messageRepository.findFirstByConversation(conversationId, PageRequest.of(0, 2));
        int guard = 0;
        while (!page.isEmpty() && guard++ < 100) {
            page.forEach(m -> seen.add(m.getId()));
            Message last = page.get(page.size() - 1);
            page =
                    messageRepository.findByConversationBefore(
                            conversationId,
                            last.getCreatedAt(),
                            last.getId(),
                            PageRequest.of(0, PAGE_SIZE));
        }

        assertThat(seen)
                .as("every message in the tie group must appear exactly once")
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(seen).doesNotHaveDuplicates();
    }

    @Test
    void history_sameInstant_ordersByIdDescending() {
        UUID sender = insertUser("history_order_sender");
        UUID conversationId = insertConversation();
        for (int i = 0; i < 4; i++) {
            insertMessage(conversationId, sender, SHARED_INSTANT);
        }

        List<UUID> ids =
                messageRepository
                        .findFirstByConversation(conversationId, PageRequest.of(0, 10))
                        .stream()
                        .map(Message::getId)
                        .toList();

        // PostgreSQL orders uuid bytewise, which matches the lower-case text form and not the
        // signed
        // comparison UUID.compareTo applies.
        assertThat(ids.stream().map(UUID::toString).toList())
                .isSortedAccordingTo(java.util.Comparator.<String>naturalOrder().reversed());
    }

    // The statement is read from the repository's own @Query annotation, so a change to the page
    // query is checked here without anyone copying it across. idx_messages_conversation is partial
    // (is_deleted = FALSE, V15) and cannot serve a history that includes tombstones, so V147 adds
    // idx_messages_conversation_created_id, which this test expects the plan to use. What it pins
    // is
    // the predicate: a bounded range on created_at must reach the index condition, because a bare
    // OR group cannot seek and a deep page would walk every newer message of the conversation.
    @Test
    void historyPage_deepCursor_seeksTheConversationIndexInsteadOfScanning()
            throws NoSuchMethodException {
        UUID sender = insertUser("history_plan_sender");
        UUID conversationId = insertConversation();
        for (int i = 0; i < 60; i++) {
            insertMessage(conversationId, sender, SHARED_INSTANT.minusMinutes(i));
        }
        jdbcClient.sql("SET LOCAL enable_seqscan = off").update();
        String statement =
                MessageRepository.class
                        .getMethod(
                                "findByConversationBefore",
                                UUID.class,
                                OffsetDateTime.class,
                                UUID.class,
                                Pageable.class)
                        .getAnnotation(Query.class)
                        .value();

        String plan =
                String.join(
                        "\n",
                        jdbcClient
                                .sql("EXPLAIN " + statement + " LIMIT 21")
                                .param("conversationId", conversationId)
                                .param("cursorTime", SHARED_INSTANT.minusMinutes(40))
                                .param("cursorId", UUID.randomUUID())
                                .query(String.class)
                                .list());

        assertThat(plan)
                .contains("idx_messages_conversation_created_id")
                .doesNotContain("Seq Scan");
        assertThat(plan)
                .as("the timestamp bound is part of the index condition")
                .containsPattern("Index Cond:.*created_at <=");
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

    private UUID insertConversation() {
        return jdbcClient
                .sql("INSERT INTO conversations DEFAULT VALUES RETURNING id")
                .query(UUID.class)
                .single();
    }

    private UUID insertMessage(UUID conversationId, UUID senderId, OffsetDateTime createdAt) {
        return jdbcClient
                .sql(
                        """
						INSERT INTO messages(conversation_id, sender_id, message_type, content, created_at)
						VALUES (:conversationId, :senderId, 'text', 'hello', :createdAt)
						RETURNING id
						""")
                .param("conversationId", conversationId)
                .param("senderId", senderId)
                .param("createdAt", createdAt)
                .query(UUID.class)
                .single();
    }
}
