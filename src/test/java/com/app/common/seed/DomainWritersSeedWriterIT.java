package com.app.common.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.seed.loader.SeedContent;
import com.app.common.seed.loader.SeedDataLoader;
import com.app.common.seed.reset.SeedResetService;
import com.app.common.seed.time.SeedTimeline;
import com.app.common.seed.writer.AnalyticsSeedWriter;
import com.app.common.seed.writer.CommentSeedWriter;
import com.app.common.seed.writer.EngagementSeedWriter;
import com.app.common.seed.writer.MediaSeedWriter;
import com.app.common.seed.writer.MessageSeedWriter;
import com.app.common.seed.writer.ModerationSeedWriter;
import com.app.common.seed.writer.NotificationSeedWriter;
import com.app.common.seed.writer.PostSeedWriter;
import com.app.common.seed.writer.SocialGraphSeedWriter;
import com.app.common.seed.writer.StorySeedWriter;
import com.app.common.seed.writer.UserSeedWriter;
import com.app.modules.mail.service.MailService;
import com.app.modules.notification.dto.response.UnseenCountResponse;
import com.app.modules.notification.service.NotificationService;

/**
 * Proves {@link StorySeedWriter}, {@link MessageSeedWriter}, {@link NotificationSeedWriter}, {@link
 * ModerationSeedWriter} and {@link AnalyticsSeedWriter} against a real Flyway-migrated schema,
 * chained after every earlier writer the same way Task 8's {@code SeedRunner} will chain them.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev,seed",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@Testcontainers
class DomainWritersSeedWriterIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("JWT_SECRET", () -> "domain-writers-seed-writer-it-secret-32-chars!!");
        registry.add("JWT_ISSUER", () -> "https://domainwriterseedwriter.it.local");
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
    @Autowired private NotificationService notificationService;
    @Autowired private SeedResetService seedResetService;
    @Autowired private UserSeedWriter userSeedWriter;
    @Autowired private MediaSeedWriter mediaSeedWriter;
    @Autowired private PostSeedWriter postSeedWriter;
    @Autowired private CommentSeedWriter commentSeedWriter;
    @Autowired private EngagementSeedWriter engagementSeedWriter;
    @Autowired private SocialGraphSeedWriter socialGraphSeedWriter;
    @Autowired private StorySeedWriter storySeedWriter;
    @Autowired private MessageSeedWriter messageSeedWriter;
    @Autowired private NotificationSeedWriter notificationSeedWriter;
    @Autowired private ModerationSeedWriter moderationSeedWriter;
    @Autowired private AnalyticsSeedWriter analyticsSeedWriter;

    private static final Instant REFERENCE_NOW = Instant.parse("2026-08-25T00:00:00Z");

    @Test
    void write_seedsDomainWritersAgainstRealSchema() {
        seedResetService.reset();
        SeedContent content = new SeedDataLoader().load();
        SeedTimeline timeline = new SeedTimeline(20260825L, REFERENCE_NOW);

        Map<String, UUID> usersByUsername = userSeedWriter.write(content, timeline);
        Map<String, UUID> mediaByCompositeKey = mediaSeedWriter.write(content, usersByUsername);
        Map<String, UUID> postIdBySeedId =
                postSeedWriter.write(content, usersByUsername, mediaByCompositeKey, timeline);
        List<UUID> commentIds =
                commentSeedWriter.write(content, usersByUsername, postIdBySeedId, timeline);
        engagementSeedWriter.write(content, usersByUsername, postIdBySeedId, commentIds, timeline);
        socialGraphSeedWriter.write(content, usersByUsername, timeline);
        storySeedWriter.write(content, usersByUsername, timeline);
        messageSeedWriter.write(
                content, usersByUsername, mediaByCompositeKey, postIdBySeedId, timeline);
        // Must run before NotificationSeedWriter: warning notifications are read back from
        // user_warnings, which this call is what populates. Matches SeedRunner.runWriterChain().
        List<UUID> reportIds =
                moderationSeedWriter.write(content, usersByUsername, postIdBySeedId, timeline);
        notificationSeedWriter.write(timeline);
        analyticsSeedWriter.write(timeline);

        assertStoryLiveExpiredSplit();
        assertStoryNoTriggerCounterOverwrite();
        assertMessageLastMessageAtCorrectness();
        assertMessageNoStaleColumns();
        assertNotificationCreatedAtWritten();
        assertNotificationFeedShaped();
        assertModerationReportIds(reportIds);
        assertAdminActionNeverPrecedesReport();
        assertAnalyticsBucketsClosedBeforeReferenceNow();
        assertUserEventsNeverInDefaultPartition();
        assertUserEventsCoverEveryEventTypeValue();
    }

    // Step 1's hardest invariant: every live story's created_at is within 24h of referenceNow,
    // every expired story's created_at is at least 24h older than that.
    private void assertStoryLiveExpiredSplit() {
        Integer liveCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stories WHERE expires_at > ?",
                        Integer.class,
                        java.sql.Timestamp.from(REFERENCE_NOW));
        Integer expiredCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stories WHERE expires_at <= ?",
                        Integer.class,
                        java.sql.Timestamp.from(REFERENCE_NOW));
        assertThat(liveCount).isCloseTo(60, org.assertj.core.data.Offset.offset(5));
        assertThat(expiredCount).isCloseTo(60, org.assertj.core.data.Offset.offset(5));

        Integer liveTooOld =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stories WHERE expires_at > ? AND created_at < ?",
                        Integer.class,
                        java.sql.Timestamp.from(REFERENCE_NOW),
                        java.sql.Timestamp.from(REFERENCE_NOW.minus(Duration.ofHours(24))));
        assertThat(liveTooOld).isZero();

        Integer expiredTooYoung =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stories WHERE expires_at <= ? AND created_at >= ?",
                        Integer.class,
                        java.sql.Timestamp.from(REFERENCE_NOW),
                        java.sql.Timestamp.from(REFERENCE_NOW.minus(Duration.ofHours(24))));
        assertThat(expiredTooYoung).isZero();
    }

    private void assertStoryNoTriggerCounterOverwrite() {
        // view_count/like_count are trigger-maintained; the writer's INSERT never names them, so
        // every row starts at the column DEFAULT (0) until the trigger fires from story_views /
        // story_likes rows this same writer produced.
        Integer mismatches =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stories s WHERE s.view_count <> (SELECT COUNT(*)"
                                + " FROM story_views sv WHERE sv.story_id = s.id) OR s.like_count"
                                + " <> (SELECT COUNT(*) FROM story_likes sl WHERE sl.story_id ="
                                + " s.id)",
                        Integer.class);
        assertThat(mismatches).isZero();
    }

    // Step 2's hardest invariant: conversations.last_message_at equals the newest non-deleted
    // message's created_at, or is null when every message is sender-deleted (or there are none).
    private void assertMessageLastMessageAtCorrectness() {
        Integer mismatches =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM conversations c WHERE c.last_message_at IS DISTINCT"
                                + " FROM (SELECT MAX(m.created_at) FROM messages m WHERE"
                                + " m.conversation_id = c.id AND m.is_deleted = FALSE)",
                        Integer.class);
        assertThat(mismatches).isZero();

        Integer conversationCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM conversations", Integer.class);
        assertThat(conversationCount).isEqualTo(85);

        Integer messageCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM messages", Integer.class);
        assertThat(messageCount).isGreaterThan(1_000);
    }

    private void assertMessageNoStaleColumns() {
        // is_group / group_name / group_avatar_url do not exist on the live conversations table
        // (V50); a query naming them would fail to compile, which is the assertion itself.
        Integer directPairKeyCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(DISTINCT direct_pair_key) FROM conversations", Integer.class);
        assertThat(directPairKeyCount).isEqualTo(85);
    }

    private void assertNotificationCreatedAtWritten() {
        Integer nullCreatedAt =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM notifications WHERE created_at IS NULL",
                        Integer.class);
        assertThat(nullCreatedAt).isZero();
        Integer notificationCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class);
        assertThat(notificationCount).isGreaterThan(1_000);

        // Reproduces SeedRunner.assertEnumCoverage()'s floor for notification_type='warning'.
        // NotificationSeedWriter reads warning notifications back from user_warnings, so this
        // count is zero whenever the writer chain runs notification before moderation.
        Integer warningNotificationCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM notifications WHERE type = 'warning'", Integer.class);
        assertThat(warningNotificationCount).isGreaterThanOrEqualTo(5);
    }

    // Rows are written through the production aggregation SQL, so the trigger-maintained count,
    // the group timestamps and the moderation audit link must all hold exactly as they do live.
    private void assertNotificationFeedShaped() {
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM notifications n WHERE n.actor_count <>"
                                        + " (SELECT COUNT(*) FROM notification_actors na"
                                        + " WHERE na.notification_id = n.id)"))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM notifications WHERE type = 'message'")).isZero();
        // SeedRunner's coverage floor, for the column this writer alone fills: every category
        // but the retired 'message' reaches five rows.
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM unnest(enum_range(NULL::notification_category))"
                                        + " c WHERE c <> 'message' AND (SELECT COUNT(*) FROM"
                                        + " notifications n WHERE n.category = c) < 5"))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM notifications WHERE category = 'system'"
                                        + " AND type <> 'support_ticket_update'"
                                        + " AND admin_action_id IS NULL"))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM notifications WHERE category = 'system'"
                                        + " AND (actor_id IS NOT NULL OR actor_count <> 0)"))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM notifications WHERE type = 'follow'"
                                        + " AND (entity_type IS NOT NULL OR entity_id IS NOT NULL)"))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM notifications n JOIN notification_actors na"
                                        + " ON na.notification_id = n.id"
                                        + " WHERE na.acted_at < n.created_at"
                                        + " OR na.acted_at > n.activity_at"))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM notifications WHERE aggregation_key IS NOT"
                                        + " NULL AND (group_started_at <> created_at"
                                        + " OR activity_at < group_started_at)"))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM notifications WHERE read_at IS NOT NULL"
                                        + " AND read_at < activity_at"))
                .isZero();

        // Every content moderation audit row names the content's owner, as AdminServiceImpl does,
        // which is what lets the seeded notice show its snippet and appeal to that account.
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM admin_actions WHERE target_user_id IS NULL"
                                        + " AND target_entity_type IN"
                                        + " ('post', 'comment', 'story', 'message')"))
                .isZero();

        // Nothing is dated after the seed clock: a future row would outrank every live one.
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM notifications WHERE activity_at > ?"
                                        + " OR created_at > ?",
                                Integer.class,
                                java.sql.Timestamp.from(REFERENCE_NOW),
                                java.sql.Timestamp.from(REFERENCE_NOW)))
                .isZero();

        UUID showcase = userId("sophieg.design");
        List<Integer> likeGroupSizes =
                jdbcTemplate.queryForList(
                        "SELECT actor_count FROM notifications WHERE recipient_id = ?"
                                + " AND type = 'like_post' AND deleted_at IS NULL"
                                + " AND activity_at > ?",
                        Integer.class,
                        showcase,
                        java.sql.Timestamp.from(REFERENCE_NOW.minus(Duration.ofDays(60))));
        assertThat(likeGroupSizes).contains(1, 2, 3, 14);
        assertThat(likeGroupSizes.stream().mapToInt(Integer::intValue).max().orElse(0))
                .isGreaterThan(50);
        assertThat(
                        jdbcTemplate.queryForList(
                                "SELECT actor_count FROM notifications WHERE recipient_id = ?"
                                        + " AND type = 'follow' AND deleted_at IS NULL"
                                        + " AND activity_at > ?",
                                Integer.class,
                                showcase,
                                java.sql.Timestamp.from(REFERENCE_NOW.minus(Duration.ofDays(7)))))
                .containsExactlyInAnyOrder(1, 5);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM notifications WHERE recipient_id = '"
                                        + showcase
                                        + "' AND deleted_at IS NOT NULL"))
                .isEqualTo(1);

        assertThat(count("SELECT COUNT(*) FROM notification_seen_states"))
                .isEqualTo(count("SELECT COUNT(*) FROM users"));
        UnseenCountResponse ordinary = notificationService.getState(showcase).unseen();
        assertThat(ordinary.count()).isBetween(1, 99);
        assertThat(ordinary.capped()).isFalse();
        assertThat(notificationService.getState(userId("tucker.trailrunner")).unseen().capped())
                .isTrue();
    }

    private int count(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private UUID userId(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", UUID.class, username);
    }

    private void assertModerationReportIds(List<UUID> reportIds) {
        assertThat(reportIds).isNotEmpty();
        assertThat(reportIds).doesNotHaveDuplicates();
        Integer reportCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reports", Integer.class);
        assertThat(reportCount).isEqualTo(reportIds.size());
        assertThat(reportCount).isGreaterThan(100);

        // moderation_cases.json only scripts 5 warn_user actions total (2 in the 6 narrative
        // cases, 3 in supplementary_actions) and 5 issue_strike actions — the brief's ~40/~15
        // targets are not achievable from this writer's literal, narrative-driven source content
        // without fabricating warnings/strikes with no admin_action or case behind them, which
        // this writer deliberately does not do (unlike reports, which get background noise
        // because real-world reports are routinely submitted independent of any admin narrative).
        Integer warningCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_warnings", Integer.class);
        assertThat(warningCount).isGreaterThanOrEqualTo(5);
        Integer strikeCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_strikes", Integer.class);
        assertThat(strikeCount).isGreaterThanOrEqualTo(5);
    }

    // Step 4's hardest invariant: an admin_actions row is never earlier than the report it is
    // linked to via report_id.
    private void assertAdminActionNeverPrecedesReport() {
        Integer violations =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM admin_actions a JOIN reports r ON r.id = a.report_id"
                                + " WHERE a.created_at < r.created_at",
                        Integer.class);
        assertThat(violations).isZero();

        // case_01's user_suspended remains suspended with an expiry strictly after referenceNow.
        java.sql.Timestamp suspendedUntil =
                jdbcTemplate.queryForObject(
                        "SELECT suspended_until FROM users WHERE username = 'user_suspended'",
                        java.sql.Timestamp.class);
        assertThat(suspendedUntil).isNotNull();
        assertThat(suspendedUntil.toInstant()).isAfter(REFERENCE_NOW);
    }

    private void assertAnalyticsBucketsClosedBeforeReferenceNow() {
        Integer dailyBuckets =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(DISTINCT bucket_start) FROM platform_stats WHERE"
                                + " granularity = 'day'",
                        Integer.class);
        assertThat(dailyBuckets).isEqualTo(90);
        Integer halfHourBuckets =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(DISTINCT bucket_start) FROM platform_stats WHERE"
                                + " granularity = 'half_hour'",
                        Integer.class);
        assertThat(halfHourBuckets).isEqualTo(30 * 48);

        Integer openBuckets =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM platform_stats WHERE granularity = 'day' AND"
                                + " bucket_start + INTERVAL '1 day' > ?",
                        Integer.class,
                        java.sql.Timestamp.from(REFERENCE_NOW));
        assertThat(openBuckets).isZero();
    }

    private void assertUserEventsNeverInDefaultPartition() {
        Integer defaultPartitionCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM user_events_default", Integer.class);
        assertThat(defaultPartitionCount).isZero();

        Integer totalEvents =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_events", Integer.class);
        assertThat(totalEvents).isGreaterThan(5_000);
    }

    // Reproduces SeedRunner.assertEnumCoverage()'s requirement for user_events.event_type: every
    // event_type enum value (V01) must have at least 5 rows once a full seed run completes.
    // AnalyticsSeedWriter is the only writer that populates user_events synchronously, so this is
    // a direct proof that WEIGHTED_EVENT_TYPES and resolveEntityRef cover every value it owns.
    // post_view is deliberately absent from that list and from this assertion: it is produced by
    // the recommendation consumer draining post.viewed.v1, which happens asynchronously and does
    // not run in this writers-only test. SeedRunner.assertEnumCoverage skips it for the same
    // reason, and the drained system is where its coverage is asserted instead.
    private void assertUserEventsCoverEveryEventTypeValue() {
        List<String> allEventTypeValues =
                List.of(
                        "post_like",
                        "post_unlike",
                        "post_save",
                        "post_unsave",
                        "post_share",
                        "post_comment",
                        "story_view",
                        "story_reply",
                        "profile_view",
                        "profile_follow",
                        "profile_unfollow",
                        "search",
                        "hashtag_click",
                        "comment_like",
                        "comment_reply",
                        "message_send",
                        "session_start",
                        "session_end",
                        "app_open");

        Map<String, Integer> countsByEventType =
                jdbcTemplate
                        .query(
                                "SELECT event_type::text AS event_type, COUNT(*) AS row_count FROM"
                                        + " user_events GROUP BY event_type",
                                (rs, rowNum) ->
                                        Map.entry(
                                                rs.getString("event_type"), rs.getInt("row_count")))
                        .stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        Map.Entry::getKey, Map.Entry::getValue));

        for (String eventType : allEventTypeValues) {
            assertThat(countsByEventType.getOrDefault(eventType, 0))
                    .as("row count for event_type '%s'", eventType)
                    .isGreaterThanOrEqualTo(5);
        }
    }
}
