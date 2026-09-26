package com.app.common.seed;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.app.common.seed.writer.CommentSeedWriter;
import com.app.common.seed.writer.EngagementSeedWriter;
import com.app.common.seed.writer.MediaSeedWriter;
import com.app.common.seed.writer.MessageSeedWriter;
import com.app.common.seed.writer.ModerationSeedWriter;
import com.app.common.seed.writer.PostSeedWriter;
import com.app.common.seed.writer.SocialGraphSeedWriter;
import com.app.common.seed.writer.StorySeedWriter;
import com.app.common.seed.writer.SupportSeedWriter;
import com.app.common.seed.writer.UserSeedWriter;
import com.app.modules.mail.service.MailService;

/**
 * Proves {@link SupportSeedWriter} against a real Flyway-migrated schema, chained after every
 * writer it depends on (users and moderation history) the same way {@code SeedRunner} chains them.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev,seed",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@Testcontainers
class SupportSeedWriterIT {

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
        registry.add("JWT_SECRET", () -> "support-seed-writer-it-secret-at-least-32-chars!!");
        registry.add("JWT_ISSUER", () -> "https://supportseedwriter.it.local");
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
    @Autowired private PostSeedWriter postSeedWriter;
    @Autowired private CommentSeedWriter commentSeedWriter;
    @Autowired private EngagementSeedWriter engagementSeedWriter;
    @Autowired private SocialGraphSeedWriter socialGraphSeedWriter;
    @Autowired private StorySeedWriter storySeedWriter;
    @Autowired private MessageSeedWriter messageSeedWriter;
    @Autowired private ModerationSeedWriter moderationSeedWriter;
    @Autowired private SupportSeedWriter supportSeedWriter;

    private static final Instant REFERENCE_NOW = Instant.parse("2026-08-25T00:00:00Z");

    private void runChainThroughSupport() {
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
        moderationSeedWriter.write(content, usersByUsername, postIdBySeedId, timeline);
        supportSeedWriter.write(content, usersByUsername, timeline);
    }

    @Test
    void write_totalTicketCount_isWithinFiftyToHundred() {
        runChainThroughSupport();
        Integer count =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_tickets", Integer.class);
        assertThat(count).isBetween(50, 100);
    }

    @Test
    void write_everyCategoryValue_isRepresented() {
        runChainThroughSupport();
        List<String> categories =
                jdbcTemplate.query(
                        "SELECT DISTINCT category::text FROM support_tickets",
                        (rs, rowNum) -> rs.getString(1));
        assertThat(categories)
                .containsExactlyInAnyOrder(
                        "appeal_ban",
                        "appeal_suspension",
                        "appeal_warning_strike",
                        "appeal_content_removal",
                        "account_access",
                        "account_data",
                        "bug_report",
                        "safety_concern",
                        "other",
                        "verification_request");
    }

    @Test
    void write_nonVerificationOpenLane_hasAtMostOneTicketPerUser() {
        runChainThroughSupport();
        Integer violations =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM (SELECT user_id FROM support_tickets WHERE"
                                + " user_id IS NOT NULL AND category <> 'verification_request' AND"
                                + " status IN ('open','in_progress','escalated') GROUP BY user_id"
                                + " HAVING COUNT(*) > 1) v",
                        Integer.class);
        assertThat(violations).isZero();
    }

    @Test
    void write_verificationOpenLane_hasAtMostOneTicketPerUser() {
        runChainThroughSupport();
        Integer violations =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM (SELECT user_id FROM support_tickets WHERE"
                                + " user_id IS NOT NULL AND category = 'verification_request' AND"
                                + " status IN ('open','in_progress','escalated') GROUP BY user_id"
                                + " HAVING COUNT(*) > 1) v",
                        Integer.class);
        assertThat(violations).isZero();
    }

    @Test
    void write_everyDecidedTicket_hasAStaffResponse() {
        runChainThroughSupport();
        Integer undecidedWithoutResponse =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM support_tickets WHERE status IN"
                                + " ('answered','rejected') AND staff_response IS NULL",
                        Integer.class);
        assertThat(undecidedWithoutResponse).isZero();

        Integer decidedCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM support_tickets WHERE status IN"
                                + " ('answered','rejected')",
                        Integer.class);
        assertThat(decidedCount).isGreaterThan(0);
    }

    @Test
    void write_everyTicketReference_resolvesToASeededUser() {
        runChainThroughSupport();
        Integer orphanedOwner =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM support_tickets t WHERE t.user_id IS NOT NULL AND"
                                + " NOT EXISTS (SELECT 1 FROM users u WHERE u.id = t.user_id)",
                        Integer.class);
        assertThat(orphanedOwner).isZero();

        Integer orphanedStaff =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM support_tickets t WHERE t.assigned_to IS NOT NULL"
                                + " AND NOT EXISTS (SELECT 1 FROM users u WHERE u.id ="
                                + " t.assigned_to)",
                        Integer.class);
        assertThat(orphanedStaff).isZero();
    }

    @Test
    void write_adminActionTypesForSupport_reachMinimumRowCount() {
        runChainThroughSupport();
        for (String actionType :
                List.of(
                        "respond_support_ticket",
                        "reject_support_ticket",
                        "escalate_support_ticket")) {
            Integer count =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM admin_actions WHERE action_type ="
                                    + " ?::admin_action_type",
                            Integer.class,
                            actionType);
            assertThat(count).as(actionType).isGreaterThanOrEqualTo(5);
        }
    }

    @Test
    void write_verificationRequests_haveAtLeastThreeEvidenceFieldsEach() {
        runChainThroughSupport();
        Integer belowFloor =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM verification_requests WHERE evidence_field_count < 3",
                        Integer.class);
        assertThat(belowFloor).isZero();

        Integer verificationTicketCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM support_tickets WHERE category ="
                                + " 'verification_request'",
                        Integer.class);
        Integer verificationRequestRowCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM verification_requests", Integer.class);
        assertThat(verificationRequestRowCount).isEqualTo(verificationTicketCount);
    }

    @Test
    void write_appealTickets_linkBackToARealAdminAction() {
        runChainThroughSupport();
        Integer unlinkedAppeals =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM support_tickets WHERE category::text LIKE 'appeal_%'"
                                + " AND admin_action_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM"
                                + " admin_actions a WHERE a.id = support_tickets.admin_action_id)",
                        Integer.class);
        assertThat(unlinkedAppeals).isZero();
    }

    @Test
    void write_timestampOrdering_neverPrecedesCreation() {
        runChainThroughSupport();
        Integer violations =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM support_tickets WHERE"
                                + " (assigned_at IS NOT NULL AND assigned_at < created_at) OR"
                                + " (escalated_at IS NOT NULL AND escalated_at < created_at) OR"
                                + " (responded_at IS NOT NULL AND responded_at < created_at) OR"
                                + " (escalated_at IS NOT NULL AND assigned_at IS NOT NULL AND"
                                + " escalated_at < assigned_at) OR"
                                + " (responded_at IS NOT NULL AND escalated_at IS NOT NULL AND"
                                + " responded_at < escalated_at)",
                        Integer.class);
        assertThat(violations).isZero();
    }
}
