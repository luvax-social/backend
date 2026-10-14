package com.app.common.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.testsupport.TestContainerImages;

/**
 * Confirms the concurrent index migration for interaction control left every index built and valid.
 * A {@code CREATE INDEX CONCURRENTLY} that fails part way leaves an invalid index behind instead of
 * rolling back, and an invalid index is ignored by the planner while still costing every write, so
 * existence alone is not enough.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class InteractionControlIndexesIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private final JdbcClient jdbcClient;

    InteractionControlIndexesIT(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @ParameterizedTest(name = "{0} on {1}")
    @CsvSource({
        "idx_follows_following_accepted, follows",
        "idx_user_mutes_user_updated, user_mutes",
        "idx_user_hidden_words_user, user_hidden_words",
        "idx_comments_post_hidden, comments",
        "idx_conversations_request_inbox, conversations",
        "idx_conversations_request_recipient, conversations",
        "idx_messages_conversation_created_id, messages",
        "idx_message_reactions_message, message_reactions",
        "idx_message_reactions_reacted_at, message_reactions"
    })
    void index_afterMigration_existsOnItsTableAndIsValid(String indexName, String tableName) {
        Map<String, Object> row =
                jdbcClient
                        .sql(
                                """
								SELECT t.relname AS table_name, i.indisvalid AS valid
								FROM pg_index i
								JOIN pg_class c ON c.oid = i.indexrelid
								JOIN pg_class t ON t.oid = i.indrelid
								WHERE c.relname = :name
								""")
                        .param("name", indexName)
                        .query()
                        .singleRow();

        assertThat(row.get("table_name")).isEqualTo(tableName);
        assertThat(row.get("valid")).isEqualTo(true);
    }

    @Test
    void schema_afterMigration_hasNoInvalidIndex() {
        List<String> invalid =
                jdbcClient
                        .sql(
                                """
								SELECT c.relname FROM pg_index i
								JOIN pg_class c ON c.oid = i.indexrelid
								WHERE NOT i.indisvalid
								""")
                        .query(String.class)
                        .list();

        assertThat(invalid).isEmpty();
    }
}
