package com.app.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.utility.DockerImageName;

import com.app.common.analytics.migration.ClickHouseMigrationScript;

/**
 * Starts and provisions a ClickHouse Testcontainer the way production is provisioned.
 *
 * <p>The provisioning SQL is the block of the observability stack's {@code 02-create-analytics.sh},
 * kept as a test resource and compared with the script by {@code ProvisioningScriptParityTest}, so
 * a test exercises the same users, profiles and grants production has. The passwords below are
 * throwaway values for a container that is destroyed with the test class.
 */
public final class ClickHouseTestSupport {

    public static final String WRITER_PASSWORD = "writerTestPassword1";
    public static final String READER_PASSWORD = "readerTestPassword1";
    public static final String MIGRATOR_PASSWORD = "migratorTestPassword1";

    private static final String PROVISIONING_RESOURCE = "/clickhouse/provisioning.sql";

    private ClickHouseTestSupport() {}

    /**
     * Builds an unstarted container with access management on for the default test user, which is
     * what the provisioning SQL needs to create users, profiles and grants.
     */
    public static ClickHouseContainer newContainer() {
        return new ClickHouseContainer(DockerImageName.parse(TestContainerImages.CLICKHOUSE))
                .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1");
    }

    /**
     * Starts a container and provisions it. For a Spring test class, where the container must be
     * running and provisioned before the context loads and the {@code @Container} lifecycle would
     * start it too late to provision.
     */
    public static ClickHouseContainer startProvisioned() {
        ClickHouseContainer container = newContainer();
        container.start();
        provision(container);
        return container;
    }

    /** Runs the production provisioning SQL as the container's administrator user. */
    public static void provision(ClickHouseContainer container) {
        try (Connection connection = adminConnection(container)) {
            // The script grants to grafana_reader, which the Phase 1 initdb script creates.
            execute(
                    connection,
                    "CREATE USER IF NOT EXISTS grafana_reader IDENTIFIED WITH sha256_password BY"
                            + " 'grafanaTestPassword1'");
            for (String statement : provisioningStatements()) {
                execute(connection, statement);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot provision the ClickHouse container", e);
        }
    }

    /** The provisioning statements with the three passwords substituted. */
    public static Iterable<String> provisioningStatements() {
        String sql =
                readProvisioningSql()
                        .replace("${CLICKHOUSE_ANALYTICS_WRITER_PASSWORD}", WRITER_PASSWORD)
                        .replace("${CLICKHOUSE_ANALYTICS_READER_PASSWORD}", READER_PASSWORD)
                        .replace("${CLICKHOUSE_ANALYTICS_MIGRATOR_PASSWORD}", MIGRATOR_PASSWORD);
        return ClickHouseMigrationScript.parse("V0__provisioning.sql", sql).statements();
    }

    /** The provisioning SQL exactly as kept in the test resource, placeholders included. */
    public static String readProvisioningSql() {
        try (InputStream in =
                ClickHouseTestSupport.class.getResourceAsStream(PROVISIONING_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource " + PROVISIONING_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** JDBC URL of the provisioned {@code luvax_analytics} database. */
    public static String analyticsJdbcUrl(ClickHouseContainer container) {
        return "jdbc:clickhouse://"
                + container.getHost()
                + ":"
                + container.getMappedPort(8123)
                + "/luvax_analytics";
    }

    /** Opens a connection as the container's administrator user, to the default database. */
    public static Connection adminConnection(ClickHouseContainer container) throws SQLException {
        return connect(
                "jdbc:clickhouse://" + container.getHost() + ":" + container.getMappedPort(8123),
                container.getUsername(),
                container.getPassword());
    }

    /** Opens a connection to {@code luvax_analytics} as one of the application users. */
    public static Connection connectAs(
            ClickHouseContainer container, String username, String password) throws SQLException {
        return connect(analyticsJdbcUrl(container), username, password);
    }

    /**
     * Registers the {@code app.analytics.*} properties that turn ClickHouse on and point every pool
     * at the container.
     */
    public static void register(DynamicPropertyRegistry registry, ClickHouseContainer container) {
        registry.add("app.analytics.enabled", () -> true);
        registry.add("app.analytics.clickhouse.url", () -> analyticsJdbcUrl(container));
        registry.add("app.analytics.clickhouse.writer.password", () -> WRITER_PASSWORD);
        registry.add("app.analytics.clickhouse.reader.password", () -> READER_PASSWORD);
        registry.add("app.analytics.clickhouse.migrator.password", () -> MIGRATOR_PASSWORD);
    }

    private static Connection connect(String url, String username, String password)
            throws SQLException {
        // The standalone V2 driver does not register itself with DriverManager.
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        return new com.clickhouse.jdbc.Driver().connect(url, properties);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
