package com.app.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.testsupport.TestContainerImages;

/**
 * Proves {@code scripts/rollback/phase2_postgres_rollback.sql}: a database migrated to V133 and
 * rolled back has the catalog a V126 database has, a second run changes nothing, the V128 archive
 * survives, and the released migrations then apply again from the rolled-back state.
 *
 * <p>Runs Flyway directly and in a fixed order, because each step starts from the schema the
 * previous one left behind.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnalyticsRollbackScriptIT {

    private static final Path SCRIPT = Path.of("scripts/rollback/phase2_postgres_rollback.sql");

    private static final String ARCHIVE_PREFIX = "archived_notification_admin_action_orphans";

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TreeSet<String> catalogAtV126;

    private static Flyway flyway(String target) {
        // Mirrors spring.flyway.postgresql.transactional-lock=false in application.yaml, without
        // which the first CREATE INDEX CONCURRENTLY migration waits on the schema-history lock.
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .target(target)
                .load();
    }

    @Test
    @Order(1)
    void migrate_toV126_recordsTheReferenceCatalog() {
        dataSource =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway("126").migrate();

        catalogAtV126 = catalog();

        assertThat(catalogAtV126).contains("TABLE|user_events");
        assertThat(catalogAtV126).contains("TABLE|platform_stats");
    }

    @Test
    @Order(2)
    void migrate_toV133_holdsTheAnalyticsSchemaAndAnOrphanArchive() {
        flyway("127").migrate();
        UUID user = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, email) VALUES (?, 'rollback-user', 'r@example.com')",
                user);
        UUID action = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO admin_actions (id, action_type, target_user_id)"
                        + " VALUES (?, 'warn_user', ?)",
                action,
                user);
        jdbc.update(
                "INSERT INTO notifications (id, recipient_id, type, category, admin_action_id)"
                        + " VALUES (?, ?, 'warning'::notification_type,"
                        + " 'system'::notification_category, ?)",
                UUID.randomUUID(),
                user,
                UUID.randomUUID());
        flyway("latest").migrate();

        assertThat(exists("gorse_rebuild_runs")).isTrue();
        assertThat(exists("user_events")).isFalse();
        assertThat(exists("platform_stats")).isFalse();
        assertThat(count("SELECT count(*) FROM " + ARCHIVE_PREFIX)).isEqualTo(1);
        assertThat(count("SELECT max(version::int) FROM flyway_schema_history")).isEqualTo(133);
    }

    @Test
    @Order(3)
    void rollback_afterV133_restoresTheV126CatalogAndSetsTheArchiveAside() throws IOException {
        runRollback();

        assertThat(catalog()).isEqualTo(catalogAtV126);
        assertThat(count("SELECT max(version::int) FROM flyway_schema_history")).isEqualTo(126);
        assertThat(exists(ARCHIVE_PREFIX)).isFalse();
        assertThat(count("SELECT count(*) FROM " + ARCHIVE_PREFIX + "_before_rollback"))
                .isEqualTo(1);
    }

    @Test
    @Order(4)
    void rollback_runTwice_changesNothing() throws IOException {
        runRollback();

        assertThat(catalog()).isEqualTo(catalogAtV126);
        assertThat(count("SELECT max(version::int) FROM flyway_schema_history")).isEqualTo(126);
        assertThat(count("SELECT count(*) FROM " + ARCHIVE_PREFIX + "_before_rollback"))
                .isEqualTo(1);
    }

    @Test
    @Order(5)
    void migrate_afterRollback_appliesTheReleasedMigrationsAgain() {
        flyway("latest").migrate();

        assertThat(count("SELECT max(version::int) FROM flyway_schema_history")).isEqualTo(133);
        assertThat(exists("gorse_rebuild_runs")).isTrue();
        assertThat(exists("user_events")).isFalse();
        assertThat(exists(ARCHIVE_PREFIX)).isTrue();
    }

    private static void runRollback() throws IOException {
        String sql = Files.readString(SCRIPT, StandardCharsets.UTF_8);
        jdbc.execute(sql);
    }

    private static boolean exists(String table) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT to_regclass(?) IS NOT NULL", Boolean.class, "public." + table));
    }

    private static int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    /**
     * The catalog the schema reference is built from: columns, constraints, indexes, enums,
     * triggers and function bodies. Dated user_events partitions, whose names follow the calendar,
     * flyway_schema_history and the V128 archive tables are left out.
     */
    private static TreeSet<String> catalog() {
        String notPartition = "'^user_events_[0-9]{4}_[0-9]{2}$'";
        String notBookkeeping = "'^(flyway_schema_history|" + ARCHIVE_PREFIX + ".*)$'";
        List<String> queries =
                List.of(
                        "SELECT 'TABLE|' || c.relname FROM pg_class c"
                                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')"
                                + " AND c.relname !~ "
                                + notPartition
                                + " AND c.relname !~ "
                                + notBookkeeping,
                        "SELECT 'COLUMN|' || c.relname || '|' || a.attname || '|'"
                                + " || format_type(a.atttypid, a.atttypmod) || '|' || a.attnotnull"
                                + " || '|' || coalesce(pg_get_expr(d.adbin, d.adrelid), '')"
                                + " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0"
                                + " AND NOT a.attisdropped"
                                + " LEFT JOIN pg_attrdef d ON d.adrelid = c.oid"
                                + " AND d.adnum = a.attnum"
                                + " WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')"
                                + " AND c.relname !~ "
                                + notPartition
                                + " AND c.relname !~ "
                                + notBookkeeping,
                        "SELECT 'CONSTRAINT|' || rel.relname || '|' || con.conname || '|'"
                                + " || pg_get_constraintdef(con.oid) FROM pg_constraint con"
                                + " JOIN pg_class rel ON rel.oid = con.conrelid"
                                + " JOIN pg_namespace n ON n.oid = rel.relnamespace"
                                + " WHERE n.nspname = 'public' AND rel.relname !~ "
                                + notPartition
                                + " AND rel.relname !~ "
                                + notBookkeeping,
                        "SELECT 'INDEX|' || tablename || '|' || indexdef FROM pg_indexes"
                                + " WHERE schemaname = 'public' AND tablename !~ "
                                + notPartition
                                + " AND tablename !~ "
                                + notBookkeeping,
                        "SELECT 'ENUM|' || t.typname || '|' || e.enumlabel || '|'"
                                + " || e.enumsortorder FROM pg_type t"
                                + " JOIN pg_enum e ON e.enumtypid = t.oid"
                                + " JOIN pg_namespace n ON n.oid = t.typnamespace"
                                + " WHERE n.nspname = 'public'",
                        "SELECT 'TRIGGER|' || c.relname || '|' || pg_get_triggerdef(tg.oid)"
                                + " FROM pg_trigger tg JOIN pg_class c ON c.oid = tg.tgrelid"
                                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " WHERE n.nspname = 'public' AND NOT tg.tgisinternal"
                                + " AND c.relname !~ "
                                + notPartition
                                + " AND c.relname !~ "
                                + notBookkeeping,
                        "SELECT 'FUNCTION|' || p.proname || '|' || md5(pg_get_functiondef(p.oid))"
                                + " FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                                + " WHERE n.nspname = 'public' AND p.prokind = 'f'"
                                + " AND p.proname LIKE 'fn\\_%'");
        TreeSet<String> lines = new TreeSet<>();
        for (String query : queries) {
            lines.addAll(jdbc.queryForList(query, String.class));
        }
        return lines;
    }
}
