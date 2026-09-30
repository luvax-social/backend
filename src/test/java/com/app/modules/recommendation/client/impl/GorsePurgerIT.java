package com.app.modules.recommendation.client.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.modules.recommendation.client.GorsePurgeException;
import com.app.testsupport.TestContainerImages;

/**
 * The purge against a database laid out like Gorse's, reached as a role that is not a superuser and
 * that owns nothing, the way the application role reaches the {@code gorse} database in production:
 * a missing {@code TRUNCATE} privilege is reported by table and changes nothing, and once granted
 * the purge empties every table.
 */
@Testcontainers
class GorsePurgerIT {

    private static final String ROLE = "rebuild_app";
    private static final String ROLE_PASSWORD = "rebuildAppPassword1";
    private static final List<String> TABLES =
            List.of(
                    "feedback",
                    "items",
                    "users",
                    "documents",
                    "values",
                    "time_series_points",
                    "message");

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private GorsePurgerImpl purger;

    @BeforeAll
    static void createGorseDatabaseAndRole() throws SQLException {
        try (Connection admin = connect("test");
                Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE gorse");
            statement.execute(
                    "CREATE ROLE "
                            + ROLE
                            + " LOGIN NOSUPERUSER NOCREATEDB PASSWORD '"
                            + ROLE_PASSWORD
                            + "'");
            statement.execute("GRANT CONNECT ON DATABASE gorse TO " + ROLE);
        }
    }

    @BeforeEach
    void recreateTablesAndReachThemAsTheRestrictedRole() throws SQLException {
        try (Connection admin = connect("gorse");
                Statement statement = admin.createStatement()) {
            for (String table : TABLES) {
                statement.execute("DROP TABLE IF EXISTS \"" + table + "\" CASCADE");
                statement.execute("CREATE TABLE \"" + table + "\" (id text)");
                statement.execute("INSERT INTO \"" + table + "\" VALUES ('kept')");
            }
            statement.execute("GRANT USAGE ON SCHEMA public TO " + ROLE);
            statement.execute("GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA public TO " + ROLE);
        }
        DriverManagerDataSource applicationDatabase =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        purger = new GorsePurgerImpl(new JdbcTemplate(applicationDatabase));
        ReflectionTestUtils.setField(purger, "datasourceUsername", ROLE);
        ReflectionTestUtils.setField(purger, "datasourcePassword", ROLE_PASSWORD);
    }

    private static Connection connect(String database) throws SQLException {
        String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?.*)?$", "/" + database + "$1");
        return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
    }

    private long rowCount(String table) throws SQLException {
        try (Connection admin = connect("gorse");
                Statement statement = admin.createStatement();
                ResultSet rs = statement.executeQuery("SELECT count(*) FROM \"" + table + "\"")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void findPurgeObstacles_roleWithoutTruncate_namesTheRoleAndEveryTable() {
        List<String> obstacles = purger.findPurgeObstacles();

        assertThat(obstacles).hasSize(TABLES.size());
        for (String table : TABLES) {
            assertThat(obstacles)
                    .contains(
                            "role "
                                    + ROLE
                                    + " lacks the TRUNCATE privilege on gorse.public."
                                    + table);
        }
    }

    @Test
    void purge_roleWithoutTruncate_failsAndLeavesEveryRowInPlace() throws SQLException {
        assertThatThrownBy(() -> purger.purge()).isInstanceOf(GorsePurgeException.class);

        for (String table : TABLES) {
            assertThat(rowCount(table)).as(table).isEqualTo(1);
        }
    }

    @Test
    void findPurgeObstacles_onlyOneTableLacksTheGrant_namesOnlyThatTable() throws SQLException {
        try (Connection admin = connect("gorse");
                Statement statement = admin.createStatement()) {
            statement.execute("GRANT TRUNCATE ON ALL TABLES IN SCHEMA public TO " + ROLE);
            statement.execute("REVOKE TRUNCATE ON \"values\" FROM " + ROLE);
        }

        assertThat(purger.findPurgeObstacles())
                .containsExactly(
                        "role " + ROLE + " lacks the TRUNCATE privilege on gorse.public.values");
    }

    @Test
    void purge_afterTheGrant_emptiesEveryTableAndTheGrantIsIdempotent() throws SQLException {
        try (Connection admin = connect("gorse");
                Statement statement = admin.createStatement()) {
            statement.execute("GRANT TRUNCATE ON ALL TABLES IN SCHEMA public TO " + ROLE);
            statement.execute("GRANT TRUNCATE ON ALL TABLES IN SCHEMA public TO " + ROLE);
        }

        assertThat(purger.findPurgeObstacles()).isEmpty();
        purger.purge();

        for (String table : TABLES) {
            assertThat(rowCount(table)).as(table).isZero();
        }
    }

    @Test
    void findPurgeObstacles_aTableGorseHasNotCreated_isReportedAsMissing() throws SQLException {
        try (Connection admin = connect("gorse");
                Statement statement = admin.createStatement()) {
            statement.execute("GRANT TRUNCATE ON ALL TABLES IN SCHEMA public TO " + ROLE);
            statement.execute("DROP TABLE \"message\"");
        }

        assertThat(purger.findPurgeObstacles())
                .containsExactly("table gorse.public.message does not exist in Gorse's store");
    }
}
