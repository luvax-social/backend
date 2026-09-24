package com.app.modules.notification.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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

/**
 * Migrates a database holding every pre-overhaul notification shape from V114 through the
 * notification overhaul migrations and asserts the backfill, the aggregation, the archive and the
 * index swap.
 *
 * <p>Runs Flyway directly rather than through an application context, because the subject is the
 * SQL itself: a fixture has to be written between two target versions, which a context that
 * migrates on startup cannot do.
 */
@Testcontainers
class NotificationOverhaulMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final OffsetDateTime DAY_ONE =
            OffsetDateTime.of(2026, 3, 10, 9, 0, 0, 0, ZoneOffset.UTC);

    private static JdbcTemplate jdbc;

    private static final UUID RECIPIENT = UUID.randomUUID();
    private static final UUID ANNA = UUID.randomUUID();
    private static final UUID BEN = UUID.randomUUID();
    private static final UUID CARL = UUID.randomUUID();
    private static final UUID VERA = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();

    private static final UUID POST = UUID.randomUUID();
    private static final UUID REMOVED_POST = UUID.randomUUID();
    private static final UUID COMMENT = UUID.randomUUID();

    private static final UUID LIKE_ANNA = UUID.randomUUID();
    private static final UUID LIKE_BEN = UUID.randomUUID();
    private static final UUID LIKE_CARL_NEXT_DAY = UUID.randomUUID();
    private static final UUID FOLLOW_ANNA = UUID.randomUUID();
    private static final UUID FOLLOW_BEN = UUID.randomUUID();
    private static final UUID RECENT_LIKE_VERA = UUID.randomUUID();
    private static final UUID MESSAGE = UUID.randomUUID();
    private static final UUID SUPPORT = UUID.randomUUID();
    private static final UUID WARNING = UUID.randomUUID();
    private static final UUID POST_REMOVED = UUID.randomUUID();
    private static final UUID REPORT_DISMISSED = UUID.randomUUID();
    private static final UUID COMMENT_REMOVED = UUID.randomUUID();

    private static final UUID WARN_ACTION = UUID.randomUUID();
    private static final UUID REMOVE_POST_ACTION = UUID.randomUUID();
    private static final UUID DISMISS_ACTION = UUID.randomUUID();
    private static final UUID REMOVE_COMMENT_ACTION = UUID.randomUUID();
    private static final UUID USER_WARNING = UUID.randomUUID();
    private static final UUID REPORT = UUID.randomUUID();

    private static int rowsBefore;

    @BeforeAll
    static void migrateThroughTheOverhaul() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway(dataSource, "114").migrate();
        writePreOverhaulFixture();
        rowsBefore = count("SELECT count(*) FROM notifications");
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
    void migrate_everyOriginalRow_isArchived() {
        assertThat(count("SELECT count(*) FROM notifications_pre_overhaul_archive"))
                .isEqualTo(rowsBefore);
        assertThat(
                        count(
                                "SELECT count(*) FROM notifications_pre_overhaul_archive"
                                        + " WHERE is_read IS NOT NULL"))
                .isEqualTo(rowsBefore);
    }

    @Test
    void migrate_likesOnOneDay_collapseIntoOneGroupKeepingTheNewestRow() {
        assertThat(exists(LIKE_ANNA)).isFalse();
        Map<String, Object> group = row(LIKE_BEN);
        assertThat(group.get("actor_count")).isEqualTo(2);
        assertThat(group.get("actor_id")).isEqualTo(BEN);
        assertThat(group.get("aggregation_key")).isEqualTo("like_post:" + POST);
        assertThat(group.get("category")).isEqualTo("like");
        assertThat(group.get("post_id")).isEqualTo(POST);
        // Anna's like was unread, so the group is unread even though Ben's was read.
        assertThat(group.get("read_at")).isNull();
        assertThat(group.get("is_group_open")).isEqualTo(false);
        assertThat(members(LIKE_BEN)).containsExactlyInAnyOrder(ANNA, BEN);
    }

    @Test
    void migrate_likeOnALaterDay_staysItsOwnGroup() {
        Map<String, Object> single = row(LIKE_CARL_NEXT_DAY);
        assertThat(single.get("actor_count")).isEqualTo(1);
        assertThat(single.get("group_started_at")).isNotNull();
        assertThat(members(LIKE_CARL_NEXT_DAY)).containsExactly(CARL);
    }

    @Test
    void migrate_followsOnOneDay_collapseAndLoseTheSeedEntityShape() {
        assertThat(exists(FOLLOW_ANNA)).isFalse();
        Map<String, Object> group = row(FOLLOW_BEN);
        assertThat(group.get("actor_count")).isEqualTo(2);
        assertThat(group.get("entity_type")).isNull();
        assertThat(group.get("entity_id")).isNull();
        assertThat(group.get("aggregation_key")).isEqualTo("follow");
        assertThat(group.get("read_at")).isNotNull();
    }

    @Test
    void migrate_groupStartedWithinTheWindow_staysOpenAndCarriesTheVerifiedActor() {
        Map<String, Object> group = row(RECENT_LIKE_VERA);
        assertThat(group.get("is_group_open")).isEqualTo(true);
        assertThat(group.get("actor_verified")).isEqualTo(true);
        assertThat(row(LIKE_BEN).get("actor_verified")).isEqualTo(false);
    }

    @Test
    void migrate_directMessageRows_areSoftDeleted() {
        Map<String, Object> message = row(MESSAGE);
        assertThat(message.get("deleted_at")).isNotNull();
        assertThat(message.get("category")).isEqualTo("message");
    }

    @Test
    void migrate_supportNotice_losesTheStaffActorAndHasNoMembership() {
        Map<String, Object> support = row(SUPPORT);
        assertThat(support.get("actor_id")).isNull();
        assertThat(support.get("actor_count")).isEqualTo(0);
        assertThat(support.get("category")).isEqualTo("system");
        assertThat(members(SUPPORT)).isEmpty();
    }

    @Test
    void migrate_everyModerationNotice_isLinkedToItsAuditRow() {
        assertThat(row(WARNING).get("admin_action_id")).isEqualTo(WARN_ACTION);
        assertThat(row(POST_REMOVED).get("admin_action_id")).isEqualTo(REMOVE_POST_ACTION);
        assertThat(row(REPORT_DISMISSED).get("admin_action_id")).isEqualTo(DISMISS_ACTION);
        assertThat(row(COMMENT_REMOVED).get("admin_action_id")).isEqualTo(REMOVE_COMMENT_ACTION);
    }

    @Test
    void migrate_actorCount_matchesMembershipOnEveryRow() {
        assertThat(
                        count(
                                "SELECT count(*) FROM notifications n WHERE n.actor_count <>"
                                        + " (SELECT count(*) FROM notification_actors na"
                                        + " WHERE na.notification_id = n.id)"))
                .isZero();
    }

    @Test
    void migrate_counterTrigger_maintainsActorCountAfterwards() {
        jdbc.update(
                "INSERT INTO notification_actors (notification_id, actor_id) VALUES (?, ?)",
                LIKE_CARL_NEXT_DAY,
                VERA);
        assertThat(row(LIKE_CARL_NEXT_DAY).get("actor_count")).isEqualTo(2);
        jdbc.update(
                "DELETE FROM notification_actors WHERE notification_id = ? AND actor_id = ?",
                LIKE_CARL_NEXT_DAY,
                VERA);
        assertThat(row(LIKE_CARL_NEXT_DAY).get("actor_count")).isEqualTo(1);
    }

    @Test
    void migrate_seenWatermark_isTheNewestReadRow() {
        Map<String, Object> state =
                jdbc.queryForMap(
                        "SELECT * FROM notification_seen_states WHERE user_id = ?", RECIPIENT);
        assertThat(state.get("seen_id")).isEqualTo(FOLLOW_BEN);
        assertThat(state.get("previous_id")).isEqualTo(FOLLOW_BEN);
    }

    @Test
    void migrate_isReadAndSupersededIndexes_areGoneAndReplacementsAreValid() {
        assertThat(
                        count(
                                "SELECT count(*) FROM information_schema.columns WHERE"
                                        + " table_name = 'notifications' AND column_name ="
                                        + " 'is_read'"))
                .isZero();
        List<String> indexes =
                jdbc.queryForList(
                        "SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid ="
                                + " i.indexrelid JOIN pg_class t ON t.oid = i.indrelid WHERE"
                                + " t.relname IN ('notifications', 'notification_actors') AND"
                                + " i.indisvalid",
                        String.class);
        assertThat(indexes)
                .contains(
                        "idx_notifications_feed",
                        "idx_notifications_feed_unread",
                        "idx_notifications_feed_category",
                        "idx_notifications_feed_verified",
                        "uq_notifications_open_group",
                        "idx_notifications_aggregation_key",
                        "idx_notification_actors_recent",
                        "idx_notification_actors_actor")
                .doesNotContain(
                        "idx_notifications_recipient",
                        "idx_notifications_recipient_created_id",
                        "idx_notifications_unread");
    }

    private static void writePreOverhaulFixture() {
        user(RECIPIENT, "recipient", false);
        user(ANNA, "anna", false);
        user(BEN, "ben", false);
        user(CARL, "carl", false);
        user(VERA, "vera", true);
        user(STAFF, "staff", false);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        notification(LIKE_ANNA, ANNA, "like_post", "post", POST, null, DAY_ONE, null);
        notification(
                LIKE_BEN,
                BEN,
                "like_post",
                "post",
                POST,
                null,
                DAY_ONE.plusHours(2),
                DAY_ONE.plusHours(3));
        notification(
                LIKE_CARL_NEXT_DAY,
                CARL,
                "like_post",
                "post",
                POST,
                null,
                DAY_ONE.plusDays(1),
                null);
        notification(
                FOLLOW_ANNA,
                ANNA,
                "follow",
                "follow",
                ANNA,
                null,
                DAY_ONE.plusDays(2),
                DAY_ONE.plusDays(2).plusMinutes(5));
        notification(
                FOLLOW_BEN,
                BEN,
                "follow",
                "follow",
                BEN,
                null,
                DAY_ONE.plusDays(2).plusHours(1),
                DAY_ONE.plusDays(2).plusHours(2));
        notification(
                RECENT_LIKE_VERA,
                VERA,
                "like_comment",
                "comment",
                COMMENT,
                POST,
                now.minusHours(1),
                null);
        notification(MESSAGE, ANNA, "message", "message", UUID.randomUUID(), null, DAY_ONE, null);
        notification(
                SUPPORT,
                STAFF,
                "support_ticket_update",
                "support_ticket",
                UUID.randomUUID(),
                null,
                DAY_ONE,
                null);

        jdbc.update(
                "INSERT INTO admin_actions (id, admin_id, action_type, target_user_id, created_at)"
                        + " VALUES (?, ?, 'warn_user', ?, ?)",
                WARN_ACTION,
                STAFF,
                RECIPIENT,
                DAY_ONE);
        jdbc.update(
                "INSERT INTO user_warnings (id, user_id, issued_by, reason_key, note,"
                        + " admin_action_id, created_at) VALUES (?, ?, ?, 'spam', 'note', ?, ?)",
                USER_WARNING,
                RECIPIENT,
                STAFF,
                WARN_ACTION,
                DAY_ONE);
        notification(WARNING, null, "warning", "warning", USER_WARNING, null, DAY_ONE, null);

        jdbc.update(
                "INSERT INTO admin_actions (id, admin_id, action_type, target_user_id,"
                        + " target_entity_type, target_entity_id, created_at)"
                        + " VALUES (?, ?, 'remove_post', ?, 'post', ?, ?)",
                REMOVE_POST_ACTION,
                STAFF,
                RECIPIENT,
                REMOVED_POST,
                DAY_ONE);
        notification(POST_REMOVED, null, "post_removed", "post", REMOVED_POST, null, DAY_ONE, null);

        jdbc.update(
                "INSERT INTO reports (id, reporter_id, report_type, report_reason, entity_id)"
                        + " VALUES (?, ?, 'post', 'spam', ?)",
                REPORT,
                RECIPIENT,
                POST);
        jdbc.update(
                "INSERT INTO admin_actions (id, admin_id, action_type, report_id, created_at)"
                        + " VALUES (?, ?, 'dismiss_report', ?, ?)",
                DISMISS_ACTION,
                STAFF,
                REPORT,
                DAY_ONE);
        notification(
                REPORT_DISMISSED, null, "report_dismissed", "report", REPORT, null, DAY_ONE, null);

        jdbc.update(
                "INSERT INTO admin_actions (id, admin_id, action_type, target_user_id, created_at)"
                        + " VALUES (?, ?, 'remove_comment', ?, ?)",
                REMOVE_COMMENT_ACTION,
                STAFF,
                RECIPIENT,
                DAY_ONE);
        notification(
                COMMENT_REMOVED,
                null,
                "comment_removed",
                "admin_action",
                REMOVE_COMMENT_ACTION,
                null,
                DAY_ONE,
                null);
    }

    private static void user(UUID id, String username, boolean verified) {
        jdbc.update(
                "INSERT INTO users (id, username, email, is_verified) VALUES (?, ?, ?, ?)",
                id,
                username + "-" + id.toString().substring(0, 8),
                username + "-" + id + "@example.com",
                verified);
    }

    private static void notification(
            UUID id,
            UUID actorId,
            String type,
            String entityType,
            UUID entityId,
            UUID postId,
            OffsetDateTime createdAt,
            OffsetDateTime readAt) {
        jdbc.update(
                "INSERT INTO notifications (id, recipient_id, actor_id, type, entity_type,"
                        + " entity_id, post_id, is_read, read_at, created_at)"
                        + " VALUES (?, ?, ?, ?::notification_type, ?, ?, ?, ?, ?, ?)",
                id,
                RECIPIENT,
                actorId,
                type,
                entityType,
                entityId,
                postId,
                readAt != null,
                readAt,
                createdAt);
    }

    private static Map<String, Object> row(UUID id) {
        return jdbc.queryForMap(
                "SELECT id, actor_id, entity_type, entity_id, post_id, read_at,"
                        + " category::text AS category, aggregation_key, is_group_open,"
                        + " group_started_at, actor_count, actor_verified,"
                        + " admin_action_id, deleted_at FROM notifications WHERE id = ?",
                id);
    }

    private static boolean exists(UUID id) {
        return count("SELECT count(*) FROM notifications WHERE id = '" + id + "'") > 0;
    }

    private static List<UUID> members(UUID notificationId) {
        return jdbc.queryForList(
                "SELECT actor_id FROM notification_actors WHERE notification_id = ?",
                UUID.class,
                notificationId);
    }

    private static int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
