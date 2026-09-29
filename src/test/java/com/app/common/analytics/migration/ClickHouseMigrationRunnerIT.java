package com.app.common.analytics.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.testsupport.ClickHouseTestSupport;
import com.app.testsupport.TestContainerImages;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The schema runner against a real ClickHouse provisioned like production, as the restricted
 * migrator user, with a real PostgreSQL for its advisory lock.
 */
@Testcontainers
class ClickHouseMigrationRunnerIT {

    private static final String CREATE_A =
            "CREATE TABLE IF NOT EXISTS runner_probe_a (id UInt32) ENGINE = MergeTree ORDER BY id;\n";
    private static final String CREATE_B =
            "CREATE TABLE IF NOT EXISTS runner_probe_b (id UInt32) ENGINE = MergeTree ORDER BY id;\n";

    @Container static ClickHouseContainer clickhouse = ClickHouseTestSupport.newContainer();

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    static HikariDataSource migrator;
    static HikariDataSource primary;

    @TempDir Path scripts;

    @BeforeAll
    static void provisionAndConnect() {
        ClickHouseTestSupport.provision(clickhouse);
        HikariConfig chConfig = new HikariConfig();
        chConfig.setDriverClassName("com.clickhouse.jdbc.Driver");
        chConfig.setJdbcUrl(ClickHouseTestSupport.analyticsJdbcUrl(clickhouse));
        chConfig.setUsername("luvax_analytics_migrator");
        chConfig.setPassword(ClickHouseTestSupport.MIGRATOR_PASSWORD);
        chConfig.setMaximumPoolSize(2);
        migrator = new HikariDataSource(chConfig);

        HikariConfig pgConfig = new HikariConfig();
        pgConfig.setJdbcUrl(postgres.getJdbcUrl());
        pgConfig.setUsername(postgres.getUsername());
        pgConfig.setPassword(postgres.getPassword());
        pgConfig.setMaximumPoolSize(3);
        primary = new HikariDataSource(pgConfig);
    }

    @AfterAll
    static void closePools() {
        migrator.close();
        primary.close();
    }

    @BeforeEach
    void resetSchema() throws SQLException {
        try (Connection admin = ClickHouseTestSupport.adminConnection(clickhouse);
                Statement statement = admin.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS luvax_analytics.schema_migrations");
            statement.execute("DROP TABLE IF EXISTS luvax_analytics.runner_probe_a");
            statement.execute("DROP TABLE IF EXISTS luvax_analytics.runner_probe_b");
        }
    }

    private ClickHouseMigrationRunner runner() {
        return new ClickHouseMigrationRunner(
                migrator, primary, "file:" + scripts + "/", Duration.ofSeconds(30));
    }

    private void script(String name, String content) throws IOException {
        Files.writeString(scripts.resolve(name), content);
    }

    private static void advisoryLock(Connection connection) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
            lock.setLong(1, ClickHouseMigrationRunner.ADVISORY_LOCK_KEY);
            lock.executeQuery().close();
        }
    }

    private static void advisoryUnlock(Connection connection) throws SQLException {
        try (PreparedStatement unlock =
                connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            unlock.setLong(1, ClickHouseMigrationRunner.ADVISORY_LOCK_KEY);
            unlock.executeQuery().close();
        }
    }

    private long count(String sql) throws SQLException {
        try (Connection admin = ClickHouseTestSupport.adminConnection(clickhouse);
                Statement statement = admin.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @Test
    void migrate_pendingScripts_appliesInOrderAndRecordsChecksums() throws Exception {
        script("V2__create_probe_b.sql", CREATE_B);
        script("V1__create_probe_a.sql", CREATE_A);

        List<ClickHouseMigrationScript> applied = runner().migrate();

        assertThat(applied).extracting(ClickHouseMigrationScript::version).containsExactly(1, 2);
        assertThat(count("SELECT count() FROM luvax_analytics.schema_migrations")).isEqualTo(2);
        try (Connection admin = ClickHouseTestSupport.adminConnection(clickhouse);
                Statement statement = admin.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT version, script, checksum FROM"
                                        + " luvax_analytics.schema_migrations ORDER BY version")) {
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(1);
            assertThat(rows.getString(2)).isEqualTo("V1__create_probe_a.sql");
            assertThat(rows.getString(3))
                    .isEqualTo(ClickHouseMigrationScript.parse("V1__a.sql", CREATE_A).checksum());
        }
        assertThat(
                        count(
                                "SELECT count() FROM system.tables WHERE database ="
                                        + " 'luvax_analytics' AND name LIKE 'runner_probe_%'"))
                .isEqualTo(2);
    }

    @Test
    void migrate_secondRun_appliesNothing() throws Exception {
        script("V1__create_probe_a.sql", CREATE_A);
        runner().migrate();

        assertThat(runner().migrate()).isEmpty();
        assertThat(count("SELECT count() FROM luvax_analytics.schema_migrations")).isEqualTo(1);
    }

    @Test
    void migrate_appliedScriptEdited_isRefusedWithChecksumMismatch() throws Exception {
        script("V1__create_probe_a.sql", CREATE_A);
        runner().migrate();
        script("V1__create_probe_a.sql", CREATE_A + "-- edited afterwards\nSELECT 1;\n");

        assertThatThrownBy(() -> runner().migrate())
                .isInstanceOf(ClickHouseMigrationException.class)
                .hasMessageContaining("Checksum mismatch");
    }

    @Test
    void migrate_scriptFailingOnItsSecondStatement_recordsNothingAndSucceedsOnceFixed()
            throws Exception {
        script("V1__create_probe_a.sql", CREATE_A + "THIS IS NOT SQL;\n");

        assertThatThrownBy(() -> runner().migrate())
                .isInstanceOf(ClickHouseMigrationException.class)
                .hasMessageContaining("V1__create_probe_a.sql");
        assertThat(count("SELECT count() FROM luvax_analytics.schema_migrations")).isZero();
        assertThat(
                        count(
                                "SELECT count() FROM system.tables WHERE database ="
                                        + " 'luvax_analytics' AND name = 'runner_probe_a'"))
                .isEqualTo(1);

        script("V1__create_probe_a.sql", CREATE_A + "SELECT 1;\n");

        assertThat(runner().migrate()).hasSize(1);
        assertThat(count("SELECT count() FROM luvax_analytics.schema_migrations")).isEqualTo(1);
    }

    @Test
    void migrate_versionBelowTheHighestApplied_isRefusedAsOutOfOrder() throws Exception {
        script("V2__create_probe_b.sql", CREATE_B);
        runner().migrate();
        script("V1__create_probe_a.sql", CREATE_A);

        assertThatThrownBy(() -> runner().migrate())
                .isInstanceOf(ClickHouseMigrationException.class)
                .hasMessageContaining("Out-of-order");
    }

    @Test
    void migrate_twoScriptsShareAVersion_isRefused() throws Exception {
        script("V1__create_probe_a.sql", CREATE_A);
        script("V1__create_probe_b.sql", CREATE_B);

        assertThatThrownBy(() -> runner().migrate())
                .isInstanceOf(ClickHouseMigrationException.class)
                .hasMessageContaining("share version 1");
    }

    @Test
    void migrate_anotherRunnerHoldsTheLock_waitsUntilItIsReleased() throws Exception {
        script("V1__create_probe_a.sql", CREATE_A);

        try (Connection holder = primary.getConnection()) {
            advisoryLock(holder);
            try {
                CompletableFuture<List<ClickHouseMigrationScript>> blocked =
                        CompletableFuture.supplyAsync(() -> runner().migrate());

                assertThatThrownBy(() -> blocked.get(2, TimeUnit.SECONDS))
                        .isInstanceOf(TimeoutException.class);
                assertThat(
                                count(
                                        "SELECT count() FROM system.tables WHERE database ="
                                                + " 'luvax_analytics' AND name ="
                                                + " 'schema_migrations'"))
                        .isZero();

                advisoryUnlock(holder);

                assertThat(blocked.get(30, TimeUnit.SECONDS)).hasSize(1);
            } finally {
                advisoryUnlock(holder);
            }
        }
    }

    @Test
    void migrate_lockNeverReleased_givesUpAfterTheWait() throws Exception {
        script("V1__create_probe_a.sql", CREATE_A);

        try (Connection holder = primary.getConnection()) {
            advisoryLock(holder);
            try {
                ClickHouseMigrationRunner impatient =
                        new ClickHouseMigrationRunner(
                                migrator, primary, "file:" + scripts + "/", Duration.ofMillis(600));

                assertThatThrownBy(impatient::migrate)
                        .isInstanceOf(ClickHouseMigrationException.class)
                        .hasMessageContaining("held the lock");
            } finally {
                advisoryUnlock(holder);
            }
        }
    }

    @Test
    void migrate_shippedScripts_createTheThreeAnalyticsTables() throws Exception {
        ClickHouseMigrationRunner shipped =
                new ClickHouseMigrationRunner(
                        migrator,
                        primary,
                        ClickHouseMigrationRunner.DEFAULT_LOCATION,
                        Duration.ofSeconds(30));

        shipped.migrate();

        assertThat(
                        count(
                                "SELECT count() FROM system.tables WHERE database ="
                                        + " 'luvax_analytics' AND name IN ('user_events',"
                                        + " 'admin_actions', 'platform_stats')"))
                .isEqualTo(3);
        assertThat(shipped.migrate()).isEmpty();
    }

    @Test
    void migrate_writerUser_cannotCreateTablesSoOnlyTheMigratorMayRunDdl() throws Exception {
        try (Connection writer =
                        ClickHouseTestSupport.connectAs(
                                clickhouse,
                                "luvax_analytics_writer",
                                ClickHouseTestSupport.WRITER_PASSWORD);
                Statement statement = writer.createStatement()) {
            assertThatThrownBy(
                            () ->
                                    statement.execute(
                                            "CREATE TABLE luvax_analytics.writer_probe (id UInt8)"
                                                    + " ENGINE = Memory"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getErrorCode())
                    .isEqualTo(497);
        }
    }
}
