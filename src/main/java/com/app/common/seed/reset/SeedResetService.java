package com.app.common.seed.reset;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.app.modules.hashtag.search.HashtagDocument;
import com.app.modules.post.search.PostDocument;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Wipes every seedable table, plus every piece of state a reseed leaves behind in the systems the
 * outbox drain talks to, before a fresh seed run.
 *
 * <p>A reset that only truncates PostgreSQL is incomplete: RabbitMQ queues can hold messages a
 * previous run enqueued but never finished delivering, Elasticsearch's {@code posts}/{@code
 * hashtags} indexes are never cleared by anything else, and Gorse's own Postgres-backed store
 * (database {@code gorse}, a sibling of the application database, per {@code docker-compose.yaml})
 * accumulates users, items and feedback across runs. Left alone, a second seed run against an
 * already-seeded stack replays stale queue messages against freshly truncated tables (a foreign key
 * violation on whichever table the stale message's id no longer resolves against), and leaves
 * Elasticsearch and Gorse holding orphaned rows from every user id a previous run minted and this
 * run's {@code TRUNCATE} just destroyed.
 *
 * <p>Excluded deliberately, never truncated: {@code flyway_schema_history} (migration bookkeeping,
 * not domain data), {@code notification_type_configs}/{@code moderation_action_configs}/ {@code
 * report_reason_configs} (enum display metadata owned by Flyway, V18), {@code system_settings}
 * (operational config, not seed content), {@code feature_flags} (operational toggles, not seed
 * content). A future contributor adding a new reference/config table should add it here.
 */
@Slf4j
@Service
@Profile("seed & (dev | prod)")
@RequiredArgsConstructor
public class SeedResetService {

    // Matches the last path segment of a PostgreSQL JDBC URL, e.g. ".../luvax" or
    // ".../luvax?stringtype=unspecified", so the Gorse sibling database's own URL can be derived
    // without a second configured datasource.
    private static final Pattern JDBC_URL_DATABASE_NAME = Pattern.compile("/([^/?]+)(\\?.*)?$");
    private static final String GORSE_DATABASE_NAME = "gorse";
    private static final String[] GORSE_TABLES = {
        "feedback", "items", "users", "documents", "values", "time_series_points", "message"
    };

    private final JdbcTemplate jdbc;
    // An ObjectProvider, not a direct ConnectionFactory, because plenty of dev-profile test
    // contexts legitimately exclude RabbitAutoConfiguration and have no such bean at all; this
    // class must still construct in those contexts; purgeBrokerQueues() below just skips with a
    // warning when the provider yields nothing. Built into a RabbitAdmin rather than autowired
    // directly, because this application declares its topology as plain Queue/Exchange/Binding
    // beans and never itself needs a RabbitAdmin, so none is exposed as an injectable bean;
    // RabbitAdmin's own constructor is the documented way to get one on demand from any
    // ConnectionFactory.
    private final ObjectProvider<ConnectionFactory> connectionFactoryProvider;
    private final List<Queue> declaredQueues;
    private final ElasticsearchOperations elasticsearchOperations;

    @Value("${spring.datasource.username}")
    private String datasourceUsername;

    @Value("${spring.datasource.password}")
    private String datasourcePassword;

    // Verified against a live `\dt` on 2026-08-25: every name below matches the running schema
    // exactly, no renames since database/schema.sql was last regenerated.
    private static final String[] TRUNCATE_ORDER = {
        // FK-leaf tables first; CASCADE covers the rest, but explicit order keeps intent readable.
        "comment_likes",
        "post_likes",
        "post_saves",
        "story_likes",
        "story_views",
        "comment_write_idempotency",
        "message_write_idempotency",
        "post_media",
        "post_hashtags",
        "post_categories",
        "post_user_tags",
        "post_edit_history",
        "post_interaction_scores",
        "comments",
        "messages",
        "conversation_participants",
        "conversations",
        "archived_group_messages",
        "archived_group_participants",
        "archived_group_conversations",
        "stories",
        "notifications",
        "reports",
        "admin_actions",
        "user_warnings",
        "user_strikes",
        "user_similarity",
        "user_interests",
        "push_tokens",
        "refresh_tokens",
        "oauth_accounts",
        "user_credentials",
        "user_settings",
        "follows",
        "blocks",
        "hashtag_trending",
        "hashtags",
        "categories",
        "posts",
        "media_assets",
        "processed_messages",
        "outbox_events",
        "users"
    };

    /**
     * Truncates every seedable table listed in {@link #TRUNCATE_ORDER}, so a fresh seed run starts
     * from an empty domain dataset.
     *
     * <p>Never truncates the config/reference tables documented in this class's Javadoc, and never
     * touches {@code flyway_schema_history}. FK checks are disabled only for the duration of the
     * wipe, since {@code TRUNCATE ... CASCADE} in dependency order would otherwise still fail on
     * tables with circular or forward references.
     *
     * <p>The entire sequence (disabling FK checks, every {@code TRUNCATE}, and restoring FK checks)
     * runs inside a single {@link org.springframework.jdbc.core.ConnectionCallback} so every
     * statement is provably issued on the same physical connection. A pooled HikariCP connection
     * only resets a small fixed set of session properties on return to the pool - {@code
     * session_replication_role} is not one of them - so splitting this sequence across separate
     * {@code JdbcTemplate} calls could let {@code 'origin'} land on a different connection than the
     * one that set {@code 'replica'}, leaving a pooled connection permanently in trigger-disabled
     * mode for whatever borrows it next.
     */
    public void reset() {
        // Purged first, and in this order, so a message already in flight when the purge starts
        // cannot be delivered against a database this call is about to truncate: the queue is gone
        // before Postgres changes at all. Elasticsearch and Gorse are cleared next, before the
        // domain truncate, for the same reason - neither one is a dependency of the other, so
        // their relative order does not matter, only that both happen before the tables their
        // outbox-driven consumers would otherwise write stale references against.
        purgeBrokerQueues();
        resetSearchIndexes();
        purgeGorse();

        jdbc.execute(
                (Connection connection) -> {
                    try (Statement statement = connection.createStatement()) {
                        // FK checks off for the wipe only, same connection start to finish.
                        statement.execute("SET session_replication_role = 'replica'");
                        try {
                            for (String table : TRUNCATE_ORDER) {
                                statement.execute("TRUNCATE TABLE " + table + " CASCADE");
                            }
                        } finally {
                            statement.execute("SET session_replication_role = 'origin'");
                        }
                    }
                    return null;
                });
        log.info("[seed] reset complete: {} tables truncated", TRUNCATE_ORDER.length);
    }

    // Purges every queue RabbitMqTopologyConfig (and any module-owned binding config) declares as
    // a Queue bean, working queues and dead-letter queues alike, by asking Spring for every Queue
    // bean in the context rather than hardcoding names - a queue added later is purged
    // automatically because it becomes another Queue bean, not because this list was updated by
    // hand. RabbitAdmin.purgeQueue is synchronous and does not require the queue to be empty or
    // even exist as a durable guarantee beyond "this call removed whatever was there".
    private void purgeBrokerQueues() {
        ConnectionFactory connectionFactory = connectionFactoryProvider.getIfAvailable();
        if (connectionFactory == null) {
            log.warn("[seed] reset: no ConnectionFactory bean available, skipping broker purge");
            return;
        }
        RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
        int purged = 0;
        for (Queue queue : declaredQueues) {
            try {
                rabbitAdmin.purgeQueue(queue.getName());
                purged++;
            } catch (RuntimeException e) {
                log.warn(
                        "[seed] reset: could not purge queue '{}': {}",
                        queue.getName(),
                        e.getMessage());
            }
        }
        log.info("[seed] reset: {} broker queues purged", purged);
    }

    // Deletes and recreates both search indexes with their real mapping (the same
    // IndexOperations#createWithMapping call PostIndexSeedRunner/HashtagIndexSeedRunner make on a
    // cold boot), so this reset - not those ApplicationRunners, which only ever run once per JVM
    // boot and cannot be re-invoked mid-run - is what guarantees a seed run always starts against
    // an empty, correctly-mapped index. createIndex is false on both documents (see struct.md), so
    // a consumer save() against a missing index would fail outright rather than auto-creating one.
    private void resetSearchIndexes() {
        recreateIndex(PostDocument.class);
        recreateIndex(HashtagDocument.class);
    }

    private void recreateIndex(Class<?> documentType) {
        IndexOperations indexOps = elasticsearchOperations.indexOps(documentType);
        try {
            if (indexOps.exists()) {
                indexOps.delete();
            }
            indexOps.createWithMapping();
            log.info(
                    "[seed] reset: recreated Elasticsearch index for {}",
                    documentType.getSimpleName());
        } catch (RuntimeException e) {
            log.warn(
                    "[seed] reset: could not reset the {} Elasticsearch index: {}",
                    documentType.getSimpleName(),
                    e.getMessage());
        }
    }

    // Gorse's GorseClient has no bulk-delete operation - upsertUsers/upsertItems/insertFeedback are
    // the only write paths the production client exposes, and Gorse's own REST API's /api/purge
    // endpoint (confirmed by direct request) does not accept this deployment's configured
    // credentials. Gorse's actual storage is Postgres, though: GORSE_DATA_STORE in
    // docker-compose.yaml points gorse-in-one at a sibling database named "gorse" on the same
    // Postgres server the application uses. Truncating that database's own tables directly is the
    // same operation Gorse's own purge would perform, reached the way the application's own reset
    // reaches its tables, on a plain one-shot JDBC connection since no DataSource bean for a
    // second database is configured.
    private void purgeGorse() {
        // Derived from the live connection's own URL, never from the spring.datasource.url
        // property. Testcontainers' @ServiceConnection contributes a ConnectionDetails bean and
        // does not override that property, so in an integration-test context the property still
        // names the developer's real local database while the actual connection points at the
        // container. Reading the property here truncated the developer's real Gorse store every
        // time the seed integration tests ran.
        String applicationUrl;
        try (Connection appConnection = jdbc.getDataSource().getConnection()) {
            applicationUrl = appConnection.getMetaData().getURL();
        } catch (SQLException | NullPointerException e) {
            log.warn(
                    "[seed] reset: could not resolve the live datasource URL, skipping Gorse purge");
            return;
        }
        String gorseUrl =
                JDBC_URL_DATABASE_NAME
                        .matcher(applicationUrl)
                        .replaceFirst("/" + GORSE_DATABASE_NAME);
        try (Connection connection =
                        DriverManager.getConnection(
                                gorseUrl, datasourceUsername, datasourcePassword);
                Statement statement = connection.createStatement()) {
            for (String table : GORSE_TABLES) {
                statement.execute("TRUNCATE TABLE " + table + " CASCADE");
            }
            log.info("[seed] reset: {} Gorse tables truncated", GORSE_TABLES.length);
        } catch (SQLException e) {
            log.warn("[seed] reset: could not purge Gorse's database: {}", e.getMessage());
        }
    }
}
