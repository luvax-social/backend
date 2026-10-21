package com.app.common.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
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
import com.app.common.seed.model.InteractionControlSeed;
import com.app.common.seed.reset.SeedResetService;
import com.app.common.seed.time.SeedTimeline;
import com.app.common.seed.writer.MediaSeedWriter;
import com.app.common.seed.writer.SocialGraphSeedWriter;
import com.app.common.seed.writer.UserSeedWriter;
import com.app.modules.mail.service.MailService;
import com.app.testsupport.TestContainerImages;

/**
 * Proves {@link MediaSeedWriter} and {@link SocialGraphSeedWriter} against a real Flyway-migrated
 * schema, chained after {@link UserSeedWriter} the same way {@code SeedRunner} chains them.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev,seed",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@Testcontainers
class MediaAndSocialGraphSeedWriterIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("JWT_SECRET", () -> "media-social-writer-it-secret-at-least-32-chars!!");
        registry.add("JWT_ISSUER", () -> "https://mediasocialwriter.it.local");
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
    @Autowired private UserSeedWriter userSeedWriter;
    @Autowired private MediaSeedWriter mediaSeedWriter;
    @Autowired private SocialGraphSeedWriter socialGraphSeedWriter;

    @Test
    void write_seedsMediaAssetsAndFollowGraphAgainstRealSchema() {
        seedResetService.reset();
        SeedContent content = new SeedDataLoader().load();
        SeedTimeline timeline = new SeedTimeline(20260825L, Instant.parse("2026-08-25T00:00:00Z"));

        Map<String, UUID> usersByUsername = userSeedWriter.write(content, timeline);
        Map<String, UUID> mediaByCompositeKey = mediaSeedWriter.write(content, usersByUsername);
        socialGraphSeedWriter.write(content, usersByUsername, timeline);

        // media_assets: one row per (manifest entry, owner) use, storage_key must stay unique.
        assertThat(countRows("media_assets")).isEqualTo(mediaByCompositeKey.size());
        assertThat(countRows("media_assets")).isPositive();
        Integer distinctStorageKeys =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(DISTINCT storage_key) FROM media_assets", Integer.class);
        assertThat(distinctStorageKeys).isEqualTo(countRows("media_assets"));
        // Every generated storage_key must match the production shape and never reuse the
        // manifest's own seed/library/{id}.{ext} key.
        Integer wrongShapeKeys =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM media_assets WHERE storage_key NOT LIKE 'users/%/media/%'",
                        Integer.class);
        assertThat(wrongShapeKeys).isZero();
        Integer distinctCdnUrls =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(DISTINCT cdn_url) FROM media_assets", Integer.class);
        assertThat(distinctCdnUrls).isLessThan(countRows("media_assets"));
        // media_assets.created_at must be stamped from the owning user's own historical
        // created_at, never left at the DB default (today's wall-clock moment the seed run
        // executes), so every seeded asset falls inside the same historical window every other
        // seeded table follows.
        Integer rowsMatchingOwnerCreatedAt =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM media_assets m JOIN users u ON u.id = m.user_id"
                                + " WHERE m.created_at = u.created_at",
                        Integer.class);
        assertThat(rowsMatchingOwnerCreatedAt).isEqualTo(countRows("media_assets"));
        Integer rowsAtTodaysWallClock =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM media_assets WHERE created_at::date = CURRENT_DATE",
                        Integer.class);
        assertThat(rowsAtTodaysWallClock).isZero();

        // follows / blocks: never touch the trigger-maintained counters.
        assertThat(countRows("follows")).isBetween(4500, 6000);
        assertThat(countRows("blocks")).isEqualTo(25);
        Integer pendingCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM follows f JOIN users u ON u.id = f.following_id"
                                + " WHERE f.status = 'pending' AND u.is_private = TRUE",
                        Integer.class);
        assertThat(pendingCount).isBetween(1, 46);
        Integer nonPrivatePending =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM follows f JOIN users u ON u.id = f.following_id"
                                + " WHERE f.status = 'pending' AND u.is_private = FALSE",
                        Integer.class);
        assertThat(nonPrivatePending).isZero();
        // The writer never touches follower_count/following_count directly, but trg_follow_counts
        // fires on every INSERT into follows regardless of caller, so accepted rows must already be
        // reflected. A mismatch here would mean a row was inserted outside a normal INSERT (e.g. a
        // bulk COPY that bypasses triggers), which this writer never does.
        Integer counterMismatches =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM users u WHERE u.follower_count <> ("
                                + "SELECT COUNT(*) FROM follows f WHERE f.following_id = u.id AND"
                                + " f.status = 'accepted') OR u.following_count <> (SELECT"
                                + " COUNT(*) FROM follows f WHERE f.follower_id = u.id AND"
                                + " f.status = 'accepted')",
                        Integer.class);
        assertThat(counterMismatches).isZero();

        // QA-guaranteed behaviours from users.json's qa_note fields.
        UUID neverFollowedId = usersByUsername.get("user_new_empty");
        Integer neverFollowedFollowerCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM follows WHERE following_id = ?",
                        Integer.class,
                        neverFollowedId);
        assertThat(neverFollowedFollowerCount).isZero();
        // user_new_empty is the fixture the recommendation cold-start case reads: a viewer whose
        // own following feed is empty must still see content from the personalized feed. That only
        // holds if the account follows nobody either, not merely that nobody follows it.
        Integer emptySocialGraphFollowingCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM follows WHERE follower_id = ?",
                        Integer.class,
                        neverFollowedId);
        assertThat(emptySocialGraphFollowingCount).isZero();

        UUID boostedUserId = usersByUsername.get("user_power");
        Integer boostedFollowerCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM follows WHERE following_id = ?",
                        Integer.class,
                        boostedUserId);
        assertThat(boostedFollowerCount).isGreaterThanOrEqualTo(55);
    }

    @Test
    void write_seedsMutesLimitsHiddenWordsAndRecentFollowsConsistentlyWithTheGraph() {
        seedResetService.reset();
        SeedContent content = new SeedDataLoader().load();
        Instant referenceNow = Instant.parse("2026-08-25T00:00:00Z");
        SeedTimeline timeline = new SeedTimeline(20260825L, referenceNow);

        Map<String, UUID> usersByUsername = userSeedWriter.write(content, timeline);
        socialGraphSeedWriter.write(content, usersByUsername, timeline);

        InteractionControlSeed seed = content.interactionControl();
        long liveMutes = seed.mutes().stream().filter(m -> !m.undone()).count();
        assertThat(countRows("user_mutes")).isEqualTo((int) liveMutes);
        assertThat(count("SELECT COUNT(*) FROM user_mutes WHERE mute_posts AND NOT mute_stories"))
                .isGreaterThanOrEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM user_mutes WHERE mute_stories AND NOT mute_posts"))
                .isGreaterThanOrEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM user_mutes WHERE mute_posts AND mute_stories"))
                .isGreaterThanOrEqualTo(5);
        assertThat(countRows("account_show_fewer"))
                .isEqualTo((int) seed.showFewer().stream().filter(s -> !s.undone()).count());
        // A preference is a choice about content the viewer sees, so the viewer follows the
        // account and neither side has blocked the other.
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM user_mutes m WHERE NOT EXISTS (SELECT 1 FROM"
                                        + " follows f WHERE f.follower_id = m.user_id AND"
                                        + " f.following_id = m.muted_user_id AND f.status ="
                                        + " 'accepted')"))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM account_show_fewer s WHERE NOT EXISTS (SELECT"
                                        + " 1 FROM follows f WHERE f.follower_id = s.user_id AND"
                                        + " f.following_id = s.author_id AND f.status ="
                                        + " 'accepted')"))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM user_mutes m JOIN blocks b ON (b.blocker_id ="
                                        + " m.user_id AND b.blocked_id = m.muted_user_id) OR"
                                        + " (b.blocker_id = m.muted_user_id AND b.blocked_id ="
                                        + " m.user_id)"))
                .isZero();
        assertThat(countRows("blocks")).isEqualTo(25);

        Timestamp now = Timestamp.from(referenceNow);
        assertThat(count("SELECT COUNT(*) FROM interaction_limits WHERE expires_at > ?", now))
                .isGreaterThanOrEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM interaction_limits WHERE expires_at <= ?", now))
                .isGreaterThanOrEqualTo(5);
        assertThat(countRows("interaction_limits")).isEqualTo(seed.interactionLimits().size());

        assertThat(count("SELECT COUNT(*) FROM user_hidden_words"))
                .isEqualTo(
                        (int) seed.hiddenWords().stream().filter(w -> w.owner() != null).count());
        assertThat(count("SELECT COUNT(*) FROM hidden_words WHERE created_by IS NOT NULL"))
                .isEqualTo(
                        (int)
                                seed.hiddenWords().stream()
                                        .filter(w -> w.owner() == null && !w.deleted())
                                        .count());
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT normalized_phrase FROM user_hidden_words uhw JOIN users u ON"
                                        + " u.id = uhw.user_id WHERE u.username = 'user_power' AND"
                                        + " uhw.phrase = 'follow for follow'",
                                String.class))
                .isEqualTo("follow for follow");

        // Recent followers were approved after an old request: the request keeps its original
        // date and only the approval is recent. Every other accepted follow keeps the approval
        // date it was created with, which is how follows read before the approval timestamp
        // existed.
        long recentPairs =
                seed.interactionLimits().stream().mapToLong(l -> l.recentFollowers().size()).sum();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM follows WHERE status = 'accepted' AND"
                                        + " accepted_at IS DISTINCT FROM created_at"))
                .isEqualTo((int) recentPairs);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM follows f JOIN users fu ON fu.id ="
                                        + " f.follower_id JOIN users tu ON tu.id = f.following_id"
                                        + " JOIN interaction_limits l ON l.user_id = tu.id WHERE"
                                        + " fu.username IN ('lauren.t02', 'yuna.study') AND"
                                        + " tu.username = 'quinn.familyonly' AND f.status ="
                                        + " 'accepted' AND f.created_at < ?"
                                        + " AND f.accepted_at > NOW() - INTERVAL '7 days'",
                                Timestamp.from(referenceNow)))
                .isEqualTo(2);

        // Accounts that must stay strangers because a seeded request or a filtered comment
        // depends on it never follow each other in the generated graph.
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM follows f JOIN users fu ON fu.id ="
                                        + " f.follower_id JOIN users tu ON tu.id = f.following_id"
                                        + " WHERE (fu.username = 'dario.onlycomments' AND"
                                        + " tu.username = 'closet.by.hana') OR (fu.username ="
                                        + " 'halima.quietreply' AND tu.username ="
                                        + " 'shop.mimidress')"))
                .isZero();
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private int countRows(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}
