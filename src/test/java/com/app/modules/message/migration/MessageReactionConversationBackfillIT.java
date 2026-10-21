package com.app.modules.message.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.testsupport.TestContainerImages;

/**
 * Migrates a database that already holds reactions from V147 to the latest schema and asserts that
 * {@code message_reactions.conversation_id} is filled from the reacted-to message for every
 * existing row.
 *
 * <p>Runs Flyway directly rather than through an application context, because rows have to be
 * written between two target versions, which a context that migrates on startup cannot do.
 */
@Testcontainers
class MessageReactionConversationBackfillIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private static final UUID FIRST_CONVERSATION = UUID.randomUUID();
    private static final UUID SECOND_CONVERSATION = UUID.randomUUID();
    private static final UUID FIRST_MESSAGE = UUID.randomUUID();
    private static final UUID SECOND_MESSAGE = UUID.randomUUID();

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateAcrossTheColumnAddition() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway(dataSource, "147").migrate();
        UUID user = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, email) VALUES (?, 'backfill_user',"
                        + " 'backfill_user@example.com')",
                user);
        conversation(FIRST_CONVERSATION);
        conversation(SECOND_CONVERSATION);
        message(FIRST_MESSAGE, FIRST_CONVERSATION, user);
        message(SECOND_MESSAGE, SECOND_CONVERSATION, user);
        jdbc.update(
                "INSERT INTO message_reactions (message_id, user_id, emoji) VALUES (?, ?, 'a'),"
                        + " (?, ?, 'b')",
                FIRST_MESSAGE,
                user,
                SECOND_MESSAGE,
                user);
        flyway(dataSource, "latest").migrate();
    }

    private static Flyway flyway(DriverManagerDataSource dataSource, String target) {
        // Mirrors spring.flyway.postgresql.transactional-lock=false in application.yaml. With the
        // default transactional advisory lock, every CREATE INDEX CONCURRENTLY migration waits
        // forever on the transaction holding that lock.
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .target(target)
                .load();
    }

    @Test
    void migrate_existingReactions_takeTheConversationOfTheirMessage() {
        assertThat(conversationOf(FIRST_MESSAGE)).isEqualTo(FIRST_CONVERSATION);
        assertThat(conversationOf(SECOND_MESSAGE)).isEqualTo(SECOND_CONVERSATION);
    }

    @Test
    void migrate_conversationColumn_rejectsAReactionWithoutOne() {
        UUID user = jdbc.queryForObject("SELECT id FROM users LIMIT 1", UUID.class);
        UUID message = UUID.randomUUID();
        message(message, FIRST_CONVERSATION, user);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO message_reactions (message_id, user_id,"
                                                + " emoji) VALUES (?, ?, 'c')",
                                        message,
                                        user))
                .hasMessageContaining("conversation_id");
    }

    private static void conversation(UUID id) {
        jdbc.update("INSERT INTO conversations (id) VALUES (?)", id);
    }

    private static void message(UUID id, UUID conversation, UUID sender) {
        jdbc.update(
                "INSERT INTO messages (id, conversation_id, sender_id, message_type, content)"
                        + " VALUES (?, ?, ?, 'text', 'hello')",
                id,
                conversation,
                sender);
    }

    private static UUID conversationOf(UUID message) {
        return jdbc.queryForObject(
                "SELECT conversation_id FROM message_reactions WHERE message_id = ?",
                UUID.class,
                message);
    }
}
