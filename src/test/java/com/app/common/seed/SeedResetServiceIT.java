package com.app.common.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.analytics.migration.AnalyticsSchemaGate;
import com.app.common.config.rabbit.RabbitMqTopologyConfig;
import com.app.common.seed.reset.SeedResetService;
import com.app.modules.hashtag.search.HashtagDocument;
import com.app.modules.mail.service.MailService;
import com.app.modules.post.search.PostDocument;
import com.app.testsupport.ClickHouseTestSupport;
import com.app.testsupport.TestContainerImages;

/**
 * Proves {@link SeedResetService#reset()} truncates seedable domain tables while leaving the
 * excluded config/reference tables untouched, and also purges the broker queue and recreates the
 * Elasticsearch indexes it now owns as part of a full reset.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev,seed",
            "spring.docker.compose.enabled=false",
            "app.mail.consumer.enabled=false",
            "app.notification.consumer.enabled=false",
            "app.comment.consumer.enabled=false",
            "app.story.consumer.enabled=false",
            "app.admin.consumer.enabled=false",
            "app.hashtag.consumer.enabled=false",
            "app.post.consumer.enabled=false",
            "app.recommendation.consumer.enabled=false",
            "spring.rabbitmq.publisher-confirm-type=correlated",
            "spring.rabbitmq.publisher-returns=true",
            "spring.rabbitmq.template.mandatory=true"
        })
@Testcontainers
class SeedResetServiceIT {

    static final ClickHouseContainer clickhouse = ClickHouseTestSupport.startProvisioned();

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static GenericContainer<?> rabbit =
            new GenericContainer<>(DockerImageName.parse(TestContainerImages.RABBITMQ))
                    .withExposedPorts(5672);

    @Container
    static ElasticsearchContainer elasticsearch =
            new ElasticsearchContainer(DockerImageName.parse(TestContainerImages.ELASTICSEARCH))
                    .withEnv("xpack.security.enabled", "false");

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        ClickHouseTestSupport.register(registry, clickhouse);
        // SeedResetService injects these two directly with @Value, which resolves eagerly and
        // fails outright on an unset placeholder; @ServiceConnection wires the real DataSource
        // through a different mechanism and never sets these two property names itself.
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
        registry.add(
                "app.elasticsearch.uris", () -> "http://" + elasticsearch.getHttpHostAddress());
        registry.add("JWT_SECRET", () -> "seed-reset-it-secret-at-least-32-chars!!");
        registry.add("JWT_ISSUER", () -> "https://seedreset.it.local");
        registry.add("JWT_AUDIENCE", () -> "App");
        registry.add("ACCESS_TOKEN_TTL", () -> 900L);
        registry.add("REFRESH_TOKEN_TTL", () -> 3600L);
        registry.add("APP_BASE_URL", () -> "http://localhost:8080");
        registry.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        registry.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        registry.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        registry.add("MAIL_FROM_NAME", () -> "App IT");
        registry.add("MAIL_APP_NAME", () -> "App");
        registry.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        registry.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        registry.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        registry.add(
                "spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
        registry.add("app.outbox.publisher.enabled", () -> false);
        registry.add("app.hashtag.seed.enabled", () -> false);
        registry.add("app.post.seed.enabled", () -> false);
        registry.add("app.stats.enabled", () -> false);
    }

    @MockitoBean private MailService mailService;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SeedResetService seedResetService;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private ElasticsearchOperations elasticsearchOperations;
    @Autowired private AnalyticsSchemaGate analyticsSchemaGate;

    @Test
    void reset_truncatesDomainTablesButPreservesExcludedConfigTables() {
        jdbcTemplate.update(
                "INSERT INTO users (username, email) VALUES ('seed_reset_it_user', 'seed_reset_it_user@test.local')");
        jdbcTemplate.update(
                "INSERT INTO notification_type_configs (type_key, display_name, template_key, is_user_toggleable, is_enabled) "
                        + "VALUES ('seed_reset_it_probe', 'Probe', 'probe', TRUE, TRUE)");

        seedResetService.reset();

        assertThat(countRows("users")).isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM notification_type_configs WHERE type_key = 'seed_reset_it_probe'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(countRows("moderation_action_configs")).isPositive();
        assertThat(countRows("report_reason_configs")).isPositive();
        assertThat(countRows("system_settings")).isPositive();
        assertThat(countRows("feature_flags")).isPositive();
        assertThat(countRows("flyway_schema_history")).isPositive();
    }

    @Test
    void reset_purgesQueuedMessagesLeftOverFromAPriorRun() {
        rabbitTemplate.send(
                RabbitMqTopologyConfig.MAIL_QUEUE,
                MessageBuilder.withBody("stale".getBytes()).build());

        seedResetService.reset();

        Message leftover = rabbitTemplate.receive(RabbitMqTopologyConfig.MAIL_QUEUE, 1000);
        assertThat(leftover).isNull();
    }

    @Test
    void reset_recreatesSearchIndexesEmptyingAnyStrayDocument() {
        IndexOperations postIndex = elasticsearchOperations.indexOps(PostDocument.class);
        IndexOperations hashtagIndex = elasticsearchOperations.indexOps(HashtagDocument.class);
        if (postIndex.exists()) {
            postIndex.delete();
        }
        postIndex.create();
        if (hashtagIndex.exists()) {
            hashtagIndex.delete();
        }
        hashtagIndex.create();
        PostDocument strayDocument =
                PostDocument.builder()
                        .id("seed-reset-it-stray-post")
                        .userId("seed-reset-it-user")
                        .caption("stray document left over from a prior run")
                        .status("published")
                        .build();
        elasticsearchOperations.save(strayDocument);

        seedResetService.reset();

        assertThat(postIndex.exists()).isTrue();
        assertThat(hashtagIndex.exists()).isTrue();
        assertThat(elasticsearchOperations.exists("seed-reset-it-stray-post", PostDocument.class))
                .isFalse();
    }

    @Test
    void reset_truncatesTheAnalyticsTablesInClickHouse() throws Exception {
        try (Connection connection = ClickHouseTestSupport.adminConnection(clickhouse);
                Statement statement = connection.createStatement()) {
            // The reset runs the schema migrations itself when they have not applied yet, so the
            // tables need not exist before it; create them the same way it would.
            analyticsSchemaGate.attempt();
            statement.execute(
                    "INSERT INTO luvax_analytics.platform_stats VALUES (now(), 'users_total', '',"
                            + " 1, now64(6))");
            statement.execute(
                    "INSERT INTO luvax_analytics.user_events (user_id, event_type, created_at)"
                            + " VALUES (generateUUIDv4(), 'search', now64(6))");
            statement.execute(
                    "INSERT INTO luvax_analytics.admin_actions (id, action_type, created_at,"
                            + " row_version) VALUES (generateUUIDv4(), 'ban_user', now64(6), 1)");
            assertThat(analyticsRows(statement)).containsExactly(1L, 1L, 1L);

            seedResetService.reset();

            assertThat(analyticsRows(statement)).containsExactly(0L, 0L, 0L);
        }
    }

    private static long[] analyticsRows(Statement statement) throws SQLException {
        long[] counts = new long[3];
        String[] tables = {"platform_stats", "user_events", "admin_actions"};
        for (int i = 0; i < tables.length; i++) {
            try (ResultSet rs =
                    statement.executeQuery("SELECT count() FROM luvax_analytics." + tables[i])) {
                rs.next();
                counts[i] = rs.getLong(1);
            }
        }
        return counts;
    }

    private int countRows(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}
