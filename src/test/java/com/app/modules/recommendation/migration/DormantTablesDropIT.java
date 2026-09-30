package com.app.modules.recommendation.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
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
 * Migrates to the version before V132, then asserts that V132 refuses to drop either dormant table
 * while it holds a row and drops both once they are empty.
 *
 * <p>Runs Flyway directly and in a fixed order, because each step depends on the schema the
 * previous one left behind.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DormantTablesDropIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private static final UUID USER_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID USER_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID POST = UUID.randomUUID();

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateToTheVersionBeforeTheDrop() {
        dataSource =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway("131").migrate();
        jdbc.update(
                "INSERT INTO users (id, username, email) VALUES (?, 'user-a', 'a@example.com')",
                USER_A);
        jdbc.update(
                "INSERT INTO users (id, username, email) VALUES (?, 'user-b', 'b@example.com')",
                USER_B);
        jdbc.update("INSERT INTO posts (id, user_id) VALUES (?, ?)", POST, USER_A);
    }

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
    void migrate_similarityRowPresent_refusesAndKeepsBothTables() {
        jdbc.update(
                "INSERT INTO user_similarity (user_id_a, user_id_b, similarity_score)"
                        + " VALUES (?, ?, 0.5)",
                USER_A,
                USER_B);

        assertThatThrownBy(() -> flyway("132").migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("holds rows");

        assertThat(tableExists("user_similarity")).isTrue();
        assertThat(tableExists("post_interaction_scores")).isTrue();
        jdbc.update("DELETE FROM user_similarity");
    }

    @Test
    @Order(2)
    void migrate_scoreRowPresent_refusesAndKeepsBothTables() {
        jdbc.update("INSERT INTO post_interaction_scores (post_id) VALUES (?)", POST);

        assertThatThrownBy(() -> flyway("132").migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("holds rows");

        assertThat(tableExists("user_similarity")).isTrue();
        assertThat(tableExists("post_interaction_scores")).isTrue();
        jdbc.update("DELETE FROM post_interaction_scores");
    }

    @Test
    @Order(3)
    void migrate_bothTablesEmpty_dropsBoth() {
        flyway("132").migrate();

        assertThat(tableExists("user_similarity")).isFalse();
        assertThat(tableExists("post_interaction_scores")).isFalse();
    }

    private static boolean tableExists(String name) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT to_regclass(?) IS NOT NULL", Boolean.class, "public." + name));
    }
}
