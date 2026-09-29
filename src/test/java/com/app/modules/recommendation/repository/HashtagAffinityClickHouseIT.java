package com.app.modules.recommendation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.modules.recommendation.config.HashtagAffinityProperties;
import com.app.modules.recommendation.enums.UserEventType;
import com.app.modules.recommendation.service.AffinityRecomputeResult;
import com.app.modules.recommendation.service.impl.HashtagAffinityServiceImpl;
import com.app.testsupport.ClickHouseTestSupport;
import com.app.testsupport.TestContainerImages;

/**
 * The affinity recompute moved from a PostgreSQL join over {@code user_events} to a ClickHouse read
 * staged into PostgreSQL. This test pins that the move changed no number: one fixture is replayed
 * through the statement the recompute used to run, kept below as the reference, and through the new
 * path, and every (user, hashtag) row must agree.
 *
 * <p>The reference runs against a temporary table holding the same events, because the table it
 * originally read no longer exists. The new path runs first and the reference is then given the
 * window end the new path used, so both measure decay from the same instant.
 */
@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
class HashtagAffinityClickHouseIT {

    private static final Duration WINDOW = Duration.ofDays(90);
    private static final Duration HALF_LIFE = Duration.ofDays(30);

    // The statement the recompute ran before the events moved to ClickHouse, with the table name
    // pointed at the temporary copy of the fixture. Kept verbatim as the reference.
    private static final String REFERENCE_SQL =
            String.join(
                    "\n",
                    "WITH signals AS (",
                    " SELECT ue.user_id, ph.hashtag_id,",
                    "  w.weight * exp(ln(0.5) * EXTRACT(EPOCH FROM (:windowEnd - ue.created_at))",
                    "   / :halfLifeSeconds) AS contribution",
                    " FROM reference_user_events ue",
                    " JOIN (VALUES ('post_save', 4.0), ('post_share', 3.0), ('post_comment', 3.0),",
                    "  ('post_like', 2.0), ('post_view', 0.25), ('post_unsave', -4.0),",
                    "  ('post_unlike', -2.0)) AS w(event_type, weight)",
                    "  ON w.event_type = ue.event_type::text",
                    " JOIN post_hashtags ph ON ph.post_id = ue.entity_id",
                    " WHERE ue.created_at >= :windowStart AND ue.created_at < :windowEnd",
                    " AND ue.entity_type = 'post' AND ue.entity_id IS NOT NULL",
                    " UNION ALL",
                    " SELECT ue.user_id, ue.entity_id AS hashtag_id,",
                    "  3.0 * exp(ln(0.5) * EXTRACT(EPOCH FROM (:windowEnd - ue.created_at))",
                    "   / :halfLifeSeconds) AS contribution",
                    " FROM reference_user_events ue",
                    " WHERE ue.created_at >= :windowStart AND ue.created_at < :windowEnd",
                    " AND ue.event_type = 'hashtag_click' AND ue.entity_type = 'hashtag'",
                    " AND ue.entity_id IS NOT NULL),",
                    "per_tag AS (",
                    " SELECT s.user_id, s.hashtag_id, SUM(s.contribution) AS weight,",
                    "  COUNT(*) AS event_count",
                    " FROM signals s JOIN hashtags h ON h.id = s.hashtag_id AND h.status = 'active'",
                    " GROUP BY s.user_id, s.hashtag_id HAVING SUM(s.contribution) > 0),",
                    "totals AS (SELECT user_id, SUM(weight) AS total FROM per_tag GROUP BY user_id)",
                    "INSERT INTO user_hashtag_affinity AS a",
                    " (user_id, hashtag_id, score, weight, event_count, window_start, window_end,",
                    "  computed_at)",
                    "SELECT p.user_id, p.hashtag_id, ROUND((p.weight / t.total)::numeric, 8),",
                    " ROUND(p.weight::numeric, 6), p.event_count, :windowStart, :windowEnd,",
                    " :windowEnd",
                    "FROM per_tag p JOIN totals t ON t.user_id = p.user_id WHERE t.total > 0");

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    static final ClickHouseContainer clickhouse = startClickHouse();

    private static ClickHouseContainer startClickHouse() {
        ClickHouseContainer container = ClickHouseTestSupport.startProvisioned();
        ClickHouseTestSupport.applyMigrations(container);
        return container;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @AfterAll
    static void stopClickHouse() {
        clickhouse.stop();
    }

    @Autowired private UserHashtagAffinityRepository affinityRepository;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final UserEventAnalyticsRepository analyticsRepository =
            new UserEventAnalyticsRepositoryImpl(
                    ClickHouseTestSupport.directOperations(clickhouse));

    private record Row(BigDecimal score, BigDecimal weight, long eventCount) {}

    @Test
    void newPath_producesTheSameScoreWeightAndEventCountAsTheStatementItReplaced() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbcClient
                .sql(
                        "CREATE TEMP TABLE reference_user_events (user_id uuid, event_type text,"
                                + " entity_type text, entity_id uuid, created_at timestamptz)"
                                + " ON COMMIT DROP")
                .update();

        UUID heavy = insertUser("equiv_heavy");
        UUID light = insertUser("equiv_light");
        UUID clicker = insertUser("equiv_clicker");
        UUID silent = insertUser("equiv_silent");
        UUID travel = insertHashtag("equivtravel", "active");
        UUID food = insertHashtag("equivfood", "active");
        UUID music = insertHashtag("equivmusic", "active");
        UUID banned = insertHashtag("equivbanned", "banned");
        UUID sharedPost = insertPost(heavy, "shared", travel, food);
        UUID travelPost = insertPost(heavy, "travel", travel);
        UUID musicPost = insertPost(light, "music", music, banned);
        UUID reversedPost = insertPost(light, "reversed", food);

        // Every weight, including the two reversals, several events on the same post so the
        // per-post grouping is exercised, and ages spread across the window and past its edge.
        event(heavy, "post_save", sharedPost, now.minusDays(1));
        event(heavy, "post_like", sharedPost, now.minusDays(3));
        event(heavy, "post_view", sharedPost, now.minusDays(4));
        event(heavy, "post_view", sharedPost, now.minusDays(4).minusHours(5));
        event(heavy, "post_share", travelPost, now.minusDays(20));
        event(heavy, "post_comment", travelPost, now.minusDays(40));
        event(heavy, "post_save", travelPost, now.minusDays(120));
        event(light, "post_like", musicPost, now.minusDays(2));
        event(light, "post_like", reversedPost, now.minusDays(9));
        event(light, "post_unlike", reversedPost, now.minusDays(8));
        event(light, "post_save", reversedPost, now.minusDays(30));
        event(light, "post_unsave", reversedPost, now.minusDays(29));
        clickEvent(clicker, music, now.minusDays(6));
        clickEvent(clicker, food, now.minusDays(60));
        event(clicker, "post_like", sharedPost, now.minusDays(45));

        HashtagAffinityServiceImpl service =
                new HashtagAffinityServiceImpl(
                        affinityRepository,
                        analyticsRepository,
                        jdbcTemplate,
                        new HashtagAffinityProperties(WINDOW, HALF_LIFE, 50));

        AffinityRecomputeResult result = service.recompute();
        Map<String, Row> viaClickHouse = snapshot();
        jdbcClient.sql("DELETE FROM user_hashtag_affinity").update();

        jdbcClient
                .sql(REFERENCE_SQL)
                .param("windowStart", result.windowStart())
                .param("windowEnd", result.windowEnd())
                .param("halfLifeSeconds", HALF_LIFE.toSeconds())
                .update();
        Map<String, Row> reference = snapshot();

        assertThat(reference).isNotEmpty();
        assertThat(viaClickHouse.keySet()).containsExactlyInAnyOrderElementsOf(reference.keySet());
        for (Map.Entry<String, Row> entry : reference.entrySet()) {
            Row expected = entry.getValue();
            Row actual = viaClickHouse.get(entry.getKey());
            assertThat(actual.score().doubleValue())
                    .as("score of %s", entry.getKey())
                    .isCloseTo(expected.score().doubleValue(), within(1e-8));
            assertThat(actual.weight().doubleValue())
                    .as("weight of %s", entry.getKey())
                    .isCloseTo(expected.weight().doubleValue(), within(1e-6));
            assertThat(actual.eventCount())
                    .as("event count of %s", entry.getKey())
                    .isEqualTo(expected.eventCount());
        }
        // The fixture reaches the interesting cases: the silent user and a banned tag get no rows.
        assertThat(viaClickHouse.keySet())
                .noneMatch(
                        key ->
                                key.startsWith(silent.toString())
                                        || key.endsWith(banned.toString()));
    }

    private Map<String, Row> snapshot() {
        List<Map<String, Object>> rows =
                jdbcClient
                        .sql(
                                "SELECT user_id, hashtag_id, score, weight, event_count FROM"
                                        + " user_hashtag_affinity")
                        .query()
                        .listOfRows();
        Map<String, Row> byKey = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            byKey.put(
                    row.get("user_id") + "/" + row.get("hashtag_id"),
                    new Row(
                            (BigDecimal) row.get("score"),
                            (BigDecimal) row.get("weight"),
                            ((Number) row.get("event_count")).longValue()));
        }
        return byKey;
    }

    // The same event, written to the ClickHouse store the new path reads and to the temporary
    // table the reference reads.
    private void event(UUID userId, String eventType, UUID postId, OffsetDateTime at) {
        analyticsRepository.insertImported(
                UUID.randomUUID(),
                userId,
                UserEventType.fromJson(eventType),
                "post",
                postId,
                "",
                at);
        insertReference(userId, eventType, "post", postId, at);
    }

    private void clickEvent(UUID userId, UUID hashtagId, OffsetDateTime at) {
        analyticsRepository.insertImported(
                UUID.randomUUID(),
                userId,
                UserEventType.HASHTAG_CLICK,
                "hashtag",
                hashtagId,
                "",
                at);
        insertReference(userId, "hashtag_click", "hashtag", hashtagId, at);
    }

    private void insertReference(
            UUID userId, String eventType, String entityType, UUID entityId, OffsetDateTime at) {
        jdbcClient
                .sql(
                        "INSERT INTO reference_user_events (user_id, event_type, entity_type,"
                                + " entity_id, created_at) VALUES (:u, :t, :et, :e, :c)")
                .param("u", userId)
                .param("t", eventType)
                .param("et", entityType)
                .param("e", entityId)
                .param("c", at)
                .update();
    }

    private UUID insertUser(String username) {
        return jdbcClient
                .sql(
                        "INSERT INTO users (username, email, display_name, role, status,"
                                + " is_private) VALUES (:u, :e, :d, 'user', 'active', FALSE)"
                                + " RETURNING id")
                .param("u", username)
                .param("e", username + "@affinity.test")
                .param("d", username)
                .query(UUID.class)
                .single();
    }

    private UUID insertHashtag(String name, String status) {
        return jdbcClient
                .sql(
                        "INSERT INTO hashtags (name, status) VALUES (:n, CAST(:s AS"
                                + " hashtag_status)) RETURNING id")
                .param("n", name)
                .param("s", status)
                .query(UUID.class)
                .single();
    }

    private UUID insertPost(UUID authorId, String caption, UUID... hashtagIds) {
        UUID postId =
                jdbcClient
                        .sql(
                                "INSERT INTO posts (user_id, caption, post_type, status) VALUES"
                                        + " (:u, :c, 'text', 'published') RETURNING id")
                        .param("u", authorId)
                        .param("c", caption)
                        .query(UUID.class)
                        .single();
        for (UUID hashtagId : hashtagIds) {
            jdbcClient
                    .sql("INSERT INTO post_hashtags (post_id, hashtag_id) VALUES (:p, :h)")
                    .param("p", postId)
                    .param("h", hashtagId)
                    .update();
        }
        return postId;
    }
}
