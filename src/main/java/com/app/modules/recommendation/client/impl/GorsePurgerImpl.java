package com.app.modules.recommendation.client.impl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.app.modules.recommendation.client.GorsePurgeException;
import com.app.modules.recommendation.client.GorsePurger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class GorsePurgerImpl implements GorsePurger {

    // Matches the last path segment of a PostgreSQL JDBC URL, e.g. ".../luvax" or
    // ".../luvax?stringtype=unspecified", so the Gorse sibling database's own URL can be derived
    // without a second configured datasource.
    private static final Pattern JDBC_URL_DATABASE_NAME = Pattern.compile("/([^/?]+)(\\?.*)?$");
    private static final String GORSE_DATABASE_NAME = "gorse";
    private static final List<String> GORSE_TABLES =
            List.of(
                    "feedback",
                    "items",
                    "users",
                    "documents",
                    "values",
                    "time_series_points",
                    "message");

    private final JdbcTemplate jdbc;

    // The credentials the application's own pool was built from. In production these are the
    // spring.datasource properties; under Testcontainers' @ServiceConnection they are the
    // container's, while the properties keep application.yaml's ${POSTGRES_USER} and
    // ${POSTGRES_PASSWORD} placeholders, which resolve against a developer's .env and cannot be
    // resolved at all where no .env exists, such as CI.
    private final JdbcConnectionDetails connectionDetails;

    @Override
    public List<String> findPurgeObstacles() {
        List<String> obstacles = new ArrayList<>();
        try (Connection connection = openGorseConnection();
                PreparedStatement probe =
                        connection.prepareStatement(
                                "SELECT to_regclass(?) IS NOT NULL,"
                                        + " COALESCE(has_table_privilege(current_user,"
                                        + " to_regclass(?), 'TRUNCATE'), false), current_user")) {
            for (String table : GORSE_TABLES) {
                String qualified = "public.\"" + table + "\"";
                probe.setString(1, qualified);
                probe.setString(2, qualified);
                try (ResultSet rs = probe.executeQuery()) {
                    rs.next();
                    if (!rs.getBoolean(1)) {
                        obstacles.add(
                                "table gorse.public." + table + " does not exist in Gorse's store");
                    } else if (!rs.getBoolean(2)) {
                        obstacles.add(
                                "role "
                                        + rs.getString(3)
                                        + " lacks the TRUNCATE privilege on gorse.public."
                                        + table);
                    }
                }
            }
        } catch (SQLException e) {
            throw new GorsePurgeException(
                    "Gorse's database could not be inspected: " + e.getMessage(), e);
        }
        return obstacles;
    }

    @Override
    public void purge() {
        try (Connection connection = openGorseConnection();
                Statement statement = connection.createStatement()) {
            for (String table : GORSE_TABLES) {
                statement.execute("TRUNCATE TABLE \"" + table + "\" CASCADE");
            }
            log.info("Gorse purge: {} tables truncated", GORSE_TABLES.size());
        } catch (SQLException e) {
            throw new GorsePurgeException(
                    "Gorse's database could not be purged: " + e.getMessage(), e);
        }
    }

    private Connection openGorseConnection() throws SQLException {
        // Derived from the live connection's own URL, never from the spring.datasource.url
        // property. Testcontainers' @ServiceConnection contributes a ConnectionDetails bean and
        // does not override that property, so in an integration-test context the property still
        // names the developer's real local database while the actual connection points at the
        // container. Reading the property here truncated the developer's real Gorse store every
        // time the seed integration tests ran.
        String applicationUrl;
        try (Connection appConnection = jdbc.getDataSource().getConnection()) {
            applicationUrl = appConnection.getMetaData().getURL();
        } catch (SQLException | NullPointerException e) {
            throw new GorsePurgeException("the live datasource URL could not be resolved", e);
        }
        String gorseUrl =
                JDBC_URL_DATABASE_NAME
                        .matcher(applicationUrl)
                        .replaceFirst("/" + GORSE_DATABASE_NAME);
        return DriverManager.getConnection(
                gorseUrl, connectionDetails.getUsername(), connectionDetails.getPassword());
    }
}
