package com.app.modules.recommendation.client.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.modules.recommendation.client.GorsePurger;
import com.app.testsupport.TestContainerImages;

/**
 * The purger reaches Gorse's database with the credentials of the connection the application
 * actually holds, not with the {@code spring.datasource.username} and {@code password} properties.
 * Under {@code @ServiceConnection} those properties keep {@code application.yaml}'s {@code
 * ${POSTGRES_USER}} and {@code ${POSTGRES_PASSWORD}} placeholders: on a machine with no {@code
 * .env} they cannot be resolved and every full context failed to start, and on a developer machine
 * they name the local database's role rather than the container's. Here they name a role the
 * container does not have, so only the live connection's credentials can succeed.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@Testcontainers
class GorsePurgerConnectionDetailsIT {

    private static final List<String> TABLES =
            List.of(
                    "feedback",
                    "items",
                    "users",
                    "documents",
                    "values",
                    "time_series_points",
                    "message");

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        r.add("POSTGRES_USER", () -> "role_absent_from_the_container");
        r.add("POSTGRES_PASSWORD", () -> "passwordOfNoRealRole1");
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> "gorse-purger-connection-it-secret-32-min!!");
        r.add("JWT_ISSUER", () -> "https://gorse-purger-connection.it.local");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
        r.add("app.outbox.publisher.enabled", () -> false);
    }

    @Autowired private GorsePurger purger;

    @BeforeAll
    static void createGorseDatabaseWithOneRowPerTable() throws SQLException {
        try (Connection admin = connect("test");
                Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE gorse");
        }
        try (Connection admin = connect("gorse");
                Statement statement = admin.createStatement()) {
            for (String table : TABLES) {
                statement.execute("CREATE TABLE \"" + table + "\" (id text)");
                statement.execute("INSERT INTO \"" + table + "\" VALUES ('stale')");
            }
        }
    }

    private static Connection connect(String database) throws SQLException {
        String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?.*)?$", "/" + database + "$1");
        return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
    }

    private static long rowCount(String table) throws SQLException {
        try (Connection admin = connect("gorse");
                Statement statement = admin.createStatement();
                ResultSet rs = statement.executeQuery("SELECT count(*) FROM \"" + table + "\"")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void purge_datasourcePropertiesNameAnotherRole_usesTheLiveConnectionCredentials()
            throws SQLException {
        assertThat(purger.findPurgeObstacles()).isEmpty();

        purger.purge();

        for (String table : TABLES) {
            assertThat(rowCount(table)).as(table).isZero();
        }
    }
}
