package com.app.modules.users.migration;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.app.testsupport.TestContainerImages;

/**
 * Migrates a database holding both values of the old {@code allow_message_requests} boolean from
 * V134 to the latest schema and asserts the conversion to {@code message_permission} and the
 * archive of the original values.
 *
 * <p>Runs Flyway directly rather than through an application context, because rows have to be
 * written between two target versions, which a context that migrates on startup cannot do.
 */
@Testcontainers
class UserSettingsMessagePermissionMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private static final UUID OPEN_USER = UUID.randomUUID();
    private static final UUID CLOSED_USER = UUID.randomUUID();

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateAcrossTheReplacement() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway(dataSource, "134").migrate();
        settingsRow(OPEN_USER, "open", true);
        settingsRow(CLOSED_USER, "closed", false);
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
    void migrate_allowMessageRequestsTrue_becomesEveryone() {
        assertThat(permissionOf(OPEN_USER)).isEqualTo("everyone");
    }

    @Test
    void migrate_allowMessageRequestsFalse_becomesFollowing() {
        assertThat(permissionOf(CLOSED_USER)).isEqualTo("following");
    }

    @Test
    void migrate_originalBooleanValues_areArchivedBeforeTheColumnIsDropped() {
        assertThat(archivedValueOf(OPEN_USER)).isEqualTo(true);
        assertThat(archivedValueOf(CLOSED_USER)).isEqualTo(false);
    }

    @Test
    void migrate_oldBooleanColumn_isDropped() {
        Integer columns =
                jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.columns WHERE table_name ="
                                + " 'user_settings' AND column_name = 'allow_message_requests'",
                        Integer.class);
        assertThat(columns).isZero();
    }

    @Test
    void migrate_newSettingsRow_defaultsToEveryone() {
        UUID fresh = UUID.randomUUID();
        user(fresh, "fresh");
        jdbc.update("INSERT INTO user_settings (user_id) VALUES (?)", fresh);

        assertThat(permissionOf(fresh)).isEqualTo("everyone");
    }

    private static void settingsRow(UUID userId, String name, boolean allowMessageRequests) {
        user(userId, name);
        jdbc.update(
                "INSERT INTO user_settings (user_id, allow_message_requests) VALUES (?, ?)",
                userId,
                allowMessageRequests);
    }

    private static void user(UUID id, String name) {
        jdbc.update(
                "INSERT INTO users (id, username, email) VALUES (?, ?, ?)",
                id,
                name + "-" + id.toString().substring(0, 8),
                name + "-" + id + "@example.com");
    }

    private static String permissionOf(UUID userId) {
        return jdbc.queryForObject(
                "SELECT message_permission::text FROM user_settings WHERE user_id = ?",
                String.class,
                userId);
    }

    private static Boolean archivedValueOf(UUID userId) {
        return jdbc.queryForObject(
                "SELECT allow_message_requests FROM archived_user_settings_allow_message_requests"
                        + " WHERE user_id = ?",
                Boolean.class,
                userId);
    }
}
