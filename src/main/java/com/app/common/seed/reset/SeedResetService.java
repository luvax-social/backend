package com.app.common.seed.reset;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.app.common.analytics.AnalyticsStoreTruncator;
import com.app.modules.hashtag.search.HashtagDocument;
import com.app.modules.post.search.PostDocument;
import com.app.modules.recommendation.client.GorsePurgeException;
import com.app.modules.recommendation.client.GorsePurger;

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
 * content), {@code gorse_rebuild_runs} (a record of operator actions, not seed content). A future
 * contributor adding a new reference/config table should add it here.
 */
@Slf4j
@Service
@Profile("seed & (dev | prod)")
@RequiredArgsConstructor
public class SeedResetService {

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
    // An ObjectProvider because the truncator exists only when app.analytics.enabled is true.
    private final ObjectProvider<AnalyticsStoreTruncator> analyticsTruncatorProvider;

    private final GorsePurger gorsePurger;

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
        // After the queue purge, so no analytics message still in flight can refill a table the
        // moment it is emptied, and before the PostgreSQL wipe, so a failure here leaves the
        // domain data intact instead of a reseed whose analytics quietly disagree with it.
        truncateAnalytics();

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

    private void truncateAnalytics() {
        AnalyticsStoreTruncator truncator = analyticsTruncatorProvider.getIfAvailable();
        if (truncator == null) {
            log.warn("[seed] reset: analytics are disabled, no ClickHouse tables to truncate");
            return;
        }
        truncator.truncateAll();
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

    // The truncation itself lives in GorsePurger, shared with the operator-triggered rebuild, which
    // treats a failure as fatal. A reset only warns, as it always has: Gorse is a derived store and
    // a seed run against a stack without one should still complete.
    private void purgeGorse() {
        try {
            gorsePurger.purge();
        } catch (GorsePurgeException e) {
            log.warn("[seed] reset: could not purge Gorse's database: {}", e.getMessage());
        }
    }
}
