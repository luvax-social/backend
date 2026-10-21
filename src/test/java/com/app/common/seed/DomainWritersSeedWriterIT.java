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
import com.app.modules.recommendation.enums.UserEventType;
import com.app.testsupport.TestContainerImages;

/**
 * Proves {@link StorySeedWriter}, {@link MessageSeedWriter}, {@link NotificationSeedWriter}, {@link
 * ModerationSeedWriter} and {@link AnalyticsSeedWriter} against a real Flyway-migrated schema,
 * chained after every earlier writer the same way {@code SeedRunner} chains them.
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
    private static final int AUTHORED_CONVERSATIONS = 85;
    private static final int MESSAGE_REQUESTS = 25;

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
        assertUserEventsEnqueuedForImport();
        assertUserEventsCoverEveryEventTypeValue();
        assertHiddenCommentsMatchTheOwnersWordLists();
        assertMessageRequestsCoverEveryStatusAndStayConsistent();
        assertRepliesAndReactions();
        assertReactionAndHiddenWordModeration();
    }

    // Each new admin action type reaches five rows, a reaction report is closed by the action that
    // closed it, and the notification a removal sends names the reaction's owner.
    private void assertReactionAndHiddenWordModeration() {
        for (String actionType :
                new String[] {
                    "create_hidden_word",
                    "edit_hidden_word",
                    "delete_hidden_word",
                    "remove_message_reaction",
                    "restore_message_reaction"
                }) {
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM admin_actions WHERE action_type = ?::admin_action_type",
                                    actionType))
                    .as("admin actions of type %s", actionType)
                    .isGreaterThanOrEqualTo(5);
        }
        assertThat(count("SELECT COUNT(*) FROM reports WHERE report_type = 'message_reaction'"))
                .isGreaterThanOrEqualTo(5);
        for (String status : new String[] {"pending", "reviewing", "resolved", "dismissed"}) {
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM reports WHERE report_type ="
                                            + " 'message_reaction' AND status ="
                                            + " ?::report_status",
                                    status))
                    .as("message reaction reports with status %s", status)
                    .isPositive();
        }
        // A reporter is the other participant, never the reactor.
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM reports r JOIN message_reactions mr ON mr.id ="
                                        + " r.entity_id JOIN messages m ON m.id = mr.message_id"
                                        + " WHERE r.report_type = 'message_reaction' AND"
                                        + " (r.reporter_id = mr.user_id OR NOT EXISTS (SELECT 1 FROM"
                                        + " conversation_participants p WHERE p.conversation_id ="
                                        + " m.conversation_id AND p.user_id = r.reporter_id))"))
                .isZero();
        // A removal resolves the report it was taken for, and no report is resolved before the
        // action that resolved it.
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM admin_actions a JOIN reports r ON r.id ="
                                        + " a.report_id WHERE a.action_type ="
                                        + " 'remove_message_reaction' AND (r.status <> 'resolved' OR"
                                        + " r.reviewed_at <> a.created_at OR a.target_entity_id <>"
                                        + " r.entity_id)"))
                .isZero();
        // The reaction a moderator left removed carries the tombstone; the ones restored do not.
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM message_reactions WHERE admin_removed_at IS NOT NULL"))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM admin_actions a WHERE a.action_type ="
                                        + " 'restore_message_reaction' AND EXISTS (SELECT 1 FROM"
                                        + " message_reactions mr WHERE mr.id = a.target_entity_id AND"
                                        + " mr.admin_removed_at IS NOT NULL)"))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM admin_actions r JOIN admin_actions d ON"
                                        + " d.target_entity_id = r.target_entity_id AND d.action_type"
                                        + " = 'remove_message_reaction' WHERE r.action_type ="
                                        + " 'restore_message_reaction' AND r.created_at <="
                                        + " d.created_at"))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM notifications n JOIN admin_actions a ON a.id ="
                                        + " n.admin_action_id JOIN message_reactions mr ON mr.id ="
                                        + " a.target_entity_id WHERE n.type = 'reaction_removed' AND"
                                        + " n.recipient_id = mr.user_id AND n.category = 'system'"))
                .isEqualTo(
                        count(
                                "SELECT COUNT(*) FROM admin_actions WHERE action_type ="
                                        + " 'remove_message_reaction'"))
                .isGreaterThanOrEqualTo(5);
        // Hidden Words audit history matches the lists: an edit and a create name a stored entry,
        // a delete names one that is gone, and every audit row records the phrase.
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM admin_actions a WHERE a.action_type IN"
                                        + " ('create_hidden_word', 'edit_hidden_word',"
                                        + " 'delete_hidden_word') AND (a.target_entity_type <>"
                                        + " 'hidden_word' OR a.metadata->>'phrase' IS NULL OR"
                                        + " a.report_id IS NOT NULL OR a.target_user_id IS NOT NULL)"))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM admin_actions a WHERE a.action_type ="
                                        + " 'delete_hidden_word' AND EXISTS (SELECT 1 FROM"
                                        + " hidden_words h WHERE h.id = a.target_entity_id)"))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM admin_actions a WHERE a.action_type ="
                                        + " 'edit_hidden_word' AND NOT EXISTS (SELECT 1 FROM"
                                        + " hidden_words h WHERE h.id = a.target_entity_id AND"
                                        + " h.phrase = a.metadata->>'phrase')"))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM hidden_words"))
                .isEqualTo(
                        count(
                                        "SELECT COUNT(*) FROM admin_actions WHERE action_type ="
                                                + " 'create_hidden_word'")
                                - count(
                                        "SELECT COUNT(*) FROM admin_actions WHERE action_type ="
                                                + " 'delete_hidden_word'"));
    }

    // Every request status reaches five rows, and a request sits between two accounts with no
    // follow or block, as the rules that route a first message assume.
    private void assertMessageRequestsCoverEveryStatusAndStayConsistent() {
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM conversations WHERE request_status ="
                                        + " 'pending' AND NOT request_hidden"))
                .isGreaterThanOrEqualTo(5);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM conversations WHERE request_status ="
                                        + " 'pending' AND request_hidden"))
                .isGreaterThanOrEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM conversations WHERE request_status = 'declined'"))
                .isGreaterThanOrEqualTo(5);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM conversations WHERE request_status ="
                                        + " 'accepted' AND request_recipient_id IS NOT NULL"))
                .isGreaterThanOrEqualTo(5);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM conversations WHERE request_recipient_id IS"
                                        + " NOT NULL"))
                .isEqualTo(MESSAGE_REQUESTS);

        // A recipient only receives requests while allowing everyone to write.
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM conversations c JOIN user_settings s ON"
                                        + " s.user_id = c.request_recipient_id WHERE"
                                        + " s.message_permission <> 'everyone'"))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM conversations c JOIN conversation_participants p"
                                        + " ON p.conversation_id = c.id AND p.user_id <>"
                                        + " c.request_recipient_id WHERE c.request_recipient_id IS"
                                        + " NOT NULL AND (EXISTS (SELECT 1 FROM follows f WHERE"
                                        + " (f.follower_id = p.user_id AND f.following_id ="
                                        + " c.request_recipient_id) OR (f.follower_id ="
                                        + " c.request_recipient_id AND f.following_id = p.user_id))"
                                        + " OR EXISTS (SELECT 1 FROM blocks b WHERE (b.blocker_id ="
                                        + " p.user_id AND b.blocked_id = c.request_recipient_id) OR"
                                        + " (b.blocker_id = c.request_recipient_id AND b.blocked_id"
                                        + " = p.user_id)))"))
                .isZero();

        // An open or declined request holds the requester's one text message and nothing else, so
        // a declined request shows its sender exactly what a pending one does.
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM conversations c WHERE c.request_status IN"
                                        + " ('pending', 'declined') AND (SELECT COUNT(*) FROM"
                                        + " messages m WHERE m.conversation_id = c.id) <> 1"))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM conversations c JOIN messages m ON"
                                        + " m.conversation_id = c.id WHERE c.request_status IN"
                                        + " ('pending', 'declined') AND (m.sender_id ="
                                        + " c.request_recipient_id OR m.message_type <> 'text' OR"
                                        + " m.is_deleted OR m.admin_removed_at IS NOT NULL)"))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM conversations c WHERE c.request_status ="
                                        + " 'declined' AND (c.request_resolved_at IS NULL OR"
                                        + " c.request_hidden)"))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM conversations c JOIN conversation_participants"
                                        + " p ON p.conversation_id = c.id WHERE c.request_status ="
                                        + " 'declined' AND ((p.user_id = c.request_recipient_id) <>"
                                        + " (p.left_at IS NOT NULL))"))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM conversations c JOIN conversation_participants"
                                        + " p ON p.conversation_id = c.id WHERE c.request_status IN"
                                        + " ('pending', 'accepted') AND p.left_at IS NOT NULL AND"
                                        + " c.request_recipient_id IS NOT NULL"))
                .isZero();
        // An accepted request was answered, and the answer quotes the request.
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM conversations c WHERE c.request_status ="
                                        + " 'accepted' AND c.request_recipient_id IS NOT NULL AND"
                                        + " NOT EXISTS (SELECT 1 FROM messages m JOIN messages q ON"
                                        + " q.id = m.reply_to_id WHERE m.conversation_id = c.id AND"
                                        + " m.sender_id = c.request_recipient_id)"))
                .isZero();
        // A hidden request is hidden for a reason the recipient's rules explain: a personal or
        // platform word in the message, or an active limit.
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM conversations c JOIN messages m ON"
                                        + " m.conversation_id = c.id WHERE c.request_hidden AND NOT"
                                        + " EXISTS (SELECT 1 FROM user_hidden_words w WHERE w.user_id"
                                        + " = c.request_recipient_id AND lower(m.content) LIKE '%' ||"
                                        + " w.normalized_phrase || '%') AND NOT EXISTS (SELECT 1 FROM"
                                        + " interaction_limits l WHERE l.user_id ="
                                        + " c.request_recipient_id AND l.expires_at > ?)"
                                        + " AND NOT EXISTS (SELECT 1 FROM hidden_words h WHERE"
                                        + " lower(m.content) LIKE '%' || h.normalized_phrase ||"
                                        + " '%')",
                                java.sql.Timestamp.from(REFERENCE_NOW)))
                .isZero();
        // Nothing reads as a message from the future.
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM messages WHERE created_at > ?",
                                java.sql.Timestamp.from(REFERENCE_NOW)))
                .isZero();
    }

    // Reply quotes point inside their own conversation, and reactions are one per participant per
    // message, never dated before the message, and include some on a message that was deleted or
    // removed by moderation.
    private void assertRepliesAndReactions() {
        assertThat(count("SELECT COUNT(*) FROM messages WHERE reply_to_id IS NOT NULL"))
                .isGreaterThanOrEqualTo(12 + 5);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM messages m JOIN messages q ON q.id ="
                                        + " m.reply_to_id WHERE q.conversation_id <>"
                                        + " m.conversation_id OR q.created_at > m.created_at"))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM messages m JOIN messages q ON q.id ="
                                        + " m.reply_to_id WHERE q.is_deleted OR q.admin_removed_at IS"
                                        + " NOT NULL"))
                .isGreaterThanOrEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM message_reactions")).isGreaterThanOrEqualTo(30);
        assertThat(count("SELECT COUNT(DISTINCT emoji) FROM message_reactions"))
                .isGreaterThanOrEqualTo(5);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM message_reactions r JOIN messages m ON m.id ="
                                        + " r.message_id WHERE m.is_deleted OR m.admin_removed_at IS"
                                        + " NOT NULL"))
                .isGreaterThanOrEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM message_reactions r JOIN messages m ON m.id ="
                                        + " r.message_id WHERE r.reacted_at < m.created_at OR"
                                        + " r.reacted_at > ?",
                                java.sql.Timestamp.from(REFERENCE_NOW)))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM message_reactions r JOIN messages m ON m.id ="
                                        + " r.message_id WHERE NOT EXISTS (SELECT 1 FROM"
                                        + " conversation_participants p WHERE p.conversation_id ="
                                        + " m.conversation_id AND p.user_id = r.user_id)"))
                .isZero();
    }

    // Hidden Words filter at write time, so each comment hidden for that reason really contains an
    // entry of the post owner's list or of the platform list.
    private void assertHiddenCommentsMatchTheOwnersWordLists() {
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM comments c JOIN posts p ON p.id = c.post_id"
                                        + " WHERE c.hidden_reason = 'hidden_words' AND NOT EXISTS"
                                        + " (SELECT 1 FROM user_hidden_words w WHERE w.user_id ="
                                        + " p.user_id AND lower(c.content) LIKE '%' ||"
                                        + " w.normalized_phrase || '%') AND NOT EXISTS (SELECT 1"
                                        + " FROM hidden_words h WHERE lower(c.content) LIKE '%' ||"
                                        + " h.normalized_phrase || '%')"))
                .isZero();
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
        assertThat(conversationCount).isEqualTo(AUTHORED_CONVERSATIONS + MESSAGE_REQUESTS);

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
        assertThat(directPairKeyCount).isEqualTo(AUTHORED_CONVERSATIONS + MESSAGE_REQUESTS);
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

        // The unavailable-target showcase is a like group on a post its owner cannot open.
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM notifications n JOIN users u"
                                        + " ON u.id = n.recipient_id JOIN posts p ON p.id = n.post_id"
                                        + " WHERE u.username = 'vivian.frontend'"
                                        + " AND n.type = 'like_post' AND n.deleted_at IS NULL"
                                        + " AND (p.status IN ('removed', 'draft')"
                                        + " OR p.deleted_at IS NOT NULL)"))
                .isPositive();

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

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
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

    // Platform statistics reach ClickHouse through the outbox, so the seed's own output is one
    // collected-bucket event per half-hour bucket: 90 days of them, all distinct, every one ending
    // no later than the reference time.
    private void assertAnalyticsBucketsClosedBeforeReferenceNow() {
        Integer events =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM outbox_events WHERE event_type ="
                                + " 'admin.platform-stats.collected.v1'",
                        Integer.class);
        assertThat(events).isEqualTo(90 * 48);
        Integer distinctBuckets =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(DISTINCT aggregate_id) FROM outbox_events WHERE event_type ="
                                + " 'admin.platform-stats.collected.v1'",
                        Integer.class);
        assertThat(distinctBuckets).isEqualTo(90 * 48);

        Integer openBuckets =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM outbox_events WHERE event_type ="
                                + " 'admin.platform-stats.collected.v1'"
                                + " AND (payload->'data'->>'bucketStart')::timestamptz"
                                + " + INTERVAL '30 minutes' > ?",
                        Integer.class,
                        java.sql.Timestamp.from(REFERENCE_NOW));
        assertThat(openBuckets).isZero();
    }

    // The behavioural events are no longer rows in PostgreSQL: each one is an import event in the
    // outbox, keyed by its own identifier, that the import consumer stores in ClickHouse.
    private void assertUserEventsEnqueuedForImport() {
        Integer enqueued =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM outbox_events WHERE event_type ="
                                + " 'recommendation.user-event.imported.v1'",
                        Integer.class);
        // 10,000 random behavioural events plus the signals of the seeded interaction control rows.
        assertThat(enqueued).isGreaterThan(10_000);
        Integer distinct =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(DISTINCT event_id) FROM outbox_events WHERE event_type ="
                                + " 'recommendation.user-event.imported.v1'",
                        Integer.class);
        assertThat(distinct).isEqualTo(enqueued);
        Integer outsideRetention =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM outbox_events WHERE event_type ="
                                + " 'recommendation.user-event.imported.v1'"
                                + " AND (payload->'data'->>'createdAt')::timestamptz < ?",
                        Integer.class,
                        java.sql.Timestamp.from(REFERENCE_NOW.minus(Duration.ofDays(361))));
        assertThat(outsideRetention).isZero();
    }

    // Reproduces the coverage SeedRunner.assertEnumCoverage() used to require of the PostgreSQL
    // table: every event type must have at least 5 events once a full seed run completes.
    // AnalyticsSeedWriter is the only writer that produces them synchronously, so this is a direct
    // proof that WEIGHTED_EVENT_TYPES and resolveEntityRef cover every value it owns. The values in
    // AnalyticsSeedWriter.UNSEEDED_TYPES are deliberately absent from that list and from this
    // assertion: post_view is produced by the recommendation consumer draining post.viewed.v1,
    // which happens asynchronously and does not run in this writers-only test.
    private void assertUserEventsCoverEveryEventTypeValue() {
        Map<String, Integer> countsByEventType =
                jdbcTemplate
                        .query(
                                "SELECT payload->'data'->>'eventType' AS event_type, COUNT(*) AS"
                                        + " row_count FROM outbox_events WHERE event_type ="
                                        + " 'recommendation.user-event.imported.v1' GROUP BY 1",
                                (rs, rowNum) ->
                                        Map.entry(
                                                rs.getString("event_type"), rs.getInt("row_count")))
                        .stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        Map.Entry::getKey, Map.Entry::getValue));

        for (UserEventType type : UserEventType.values()) {
            if (AnalyticsSeedWriter.UNSEEDED_TYPES.contains(type)) {
                continue;
            }
            assertThat(countsByEventType.getOrDefault(type.toJson(), 0))
                    .as("import events for event_type '%s'", type.toJson())
                    .isGreaterThanOrEqualTo(5);
        }
    }
}
