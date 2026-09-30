package com.app.modules.notification.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.testsupport.TestContainerImages;

/**
 * Migrates a database that holds one notification pointing at a real audit row and one pointing at
 * nothing, then asserts that V128 archives and clears the orphan, that V129 validates the
 * constraint, and that the constraint refuses a dangling reference and nulls a deleted one.
 *
 * <p>Runs Flyway directly, because the fixture has to be written between two target versions.
 */
@Testcontainers
class NotificationAdminActionForeignKeyIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private static final UUID RECIPIENT = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID REAL_ACTION = UUID.randomUUID();
    private static final UUID MISSING_ACTION = UUID.randomUUID();
    private static final UUID LINKED = UUID.randomUUID();
    private static final UUID ORPHAN = UUID.randomUUID();

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateAroundTheConstraint() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway(dataSource, "127").migrate();
        writeFixture();
        flyway(dataSource, "129").migrate();
    }

    private static Flyway flyway(DriverManagerDataSource dataSource, String target) {
        // Mirrors spring.flyway.postgresql.transactional-lock=false in application.yaml, without
        // which the first CREATE INDEX CONCURRENTLY migration waits on the schema-history lock.
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .target(target)
                .load();
    }

    private static void writeFixture() {
        user(RECIPIENT, "recipient");
        user(STAFF, "staff");
        jdbc.update(
                "INSERT INTO admin_actions (id, admin_id, action_type, target_user_id)"
                        + " VALUES (?, ?, 'warn_user', ?)",
                REAL_ACTION,
                STAFF,
                RECIPIENT);
        notification(LINKED, REAL_ACTION);
        notification(ORPHAN, MISSING_ACTION);
    }

    @Test
    void migrate_orphanReference_isArchivedAndCleared() {
        List<Map<String, Object>> archived =
                jdbc.queryForList(
                        "SELECT notification_id, admin_action_id"
                                + " FROM archived_notification_admin_action_orphans");
        assertThat(archived).hasSize(1);
        assertThat(archived.get(0).get("notification_id")).isEqualTo(ORPHAN);
        assertThat(archived.get(0).get("admin_action_id")).isEqualTo(MISSING_ACTION);
        assertThat(adminActionOf(ORPHAN)).isNull();
    }

    @Test
    void migrate_referenceToARealAuditRow_isKept() {
        assertThat(adminActionOf(LINKED)).isEqualTo(REAL_ACTION);
    }

    @Test
    void migrate_constraint_isValidatedAndNullsOnDelete() {
        Map<String, Object> constraint =
                jdbc.queryForMap(
                        "SELECT convalidated, confdeltype::text AS on_delete FROM pg_constraint"
                                + " WHERE conname = 'fk_notifications_admin_action'");
        assertThat(constraint.get("convalidated")).isEqualTo(true);
        assertThat(constraint.get("on_delete")).isEqualTo("n");
    }

    @Test
    void insert_danglingReference_isRefused() {
        assertThatThrownBy(() -> notification(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_notifications_admin_action");
    }

    @Test
    void delete_auditRow_nullsTheReference() {
        UUID action = UUID.randomUUID();
        UUID notification = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO admin_actions (id, admin_id, action_type, target_user_id)"
                        + " VALUES (?, ?, 'warn_user', ?)",
                action,
                STAFF,
                RECIPIENT);
        notification(notification, action);
        assertThat(adminActionOf(notification)).isEqualTo(action);

        jdbc.update("DELETE FROM admin_actions WHERE id = ?", action);

        assertThat(adminActionOf(notification)).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM notifications WHERE id = ?",
                                Integer.class,
                                notification))
                .isEqualTo(1);
    }

    private static UUID adminActionOf(UUID notificationId) {
        return jdbc.queryForObject(
                "SELECT admin_action_id FROM notifications WHERE id = ?",
                UUID.class,
                notificationId);
    }

    private static void user(UUID id, String username) {
        jdbc.update(
                "INSERT INTO users (id, username, email) VALUES (?, ?, ?)",
                id,
                username + "-" + id.toString().substring(0, 8),
                username + "-" + id + "@example.com");
    }

    private static void notification(UUID id, UUID adminActionId) {
        jdbc.update(
                "INSERT INTO notifications (id, recipient_id, type, category, admin_action_id)"
                        + " VALUES (?, ?, 'warning'::notification_type,"
                        + " 'system'::notification_category, ?)",
                id,
                RECIPIENT,
                adminActionId);
    }
}
