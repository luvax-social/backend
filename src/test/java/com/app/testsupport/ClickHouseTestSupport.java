package com.app.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.utility.DockerImageName;

import com.app.common.analytics.ClickHouseOperations;
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

    /**
     * Applies every production schema script to a provisioned container, as the administrator.
     *
     * <p>For tests that exercise a repository directly and have no Spring context, and so no schema
     * gate to run the migrations. The scripts are the ones the application ships.
     */
    public static void applyMigrations(ClickHouseContainer container) {
        // Connected to luvax_analytics, because the scripts name their tables without a database.
        try (Connection connection =
                connectAs(container, container.getUsername(), container.getPassword())) {
            PathMatchingResourcePatternResolver resolver =
                    new PathMatchingResourcePatternResolver();
            List<ClickHouseMigrationScript> scripts = new ArrayList<>();
            for (Resource resource :
                    resolver.getResources("classpath:clickhouse/migration/V*.sql")) {
                String content =
                        new String(
                                resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                scripts.add(ClickHouseMigrationScript.parse(resource.getFilename(), content));
            }
            scripts.sort(Comparator.comparingInt(ClickHouseMigrationScript::version));
            for (ClickHouseMigrationScript script : scripts) {
                for (String statement : script.statements()) {
                    execute(connection, statement);
                }
            }
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Cannot apply the ClickHouse schema", e);
        }
    }

    /**
     * A {@link ClickHouseOperations} over the provisioned container that runs each call directly:
     * writes as the writer user, reads as the reader user, with no schema gate, breaker or error
     * translation. For repository tests that need only ClickHouse.
     */
    public static ClickHouseOperations directOperations(ClickHouseContainer container) {
        JdbcClient writer = clientAs(container, "luvax_analytics_writer", WRITER_PASSWORD);
        JdbcClient reader = clientAs(container, "luvax_analytics_reader", READER_PASSWORD);
        return new ClickHouseOperations() {
            @Override
            public <T> T read(String operation, Function<JdbcClient, T> query) {
                return query.apply(reader);
            }

            @Override
            public <T> T readBatch(String operation, Function<JdbcClient, T> query) {
                return query.apply(reader);
            }

            @Override
            public void write(String operation, Consumer<JdbcClient> insert) {
                insert.accept(writer);
            }

            @Override
            public boolean isReady() {
                return true;
            }
        };
    }

    /** A {@link JdbcClient} to {@code luvax_analytics} as one of the application users. */
    public static JdbcClient clientAs(
            ClickHouseContainer container, String username, String password) {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        SimpleDriverDataSource dataSource =
                new SimpleDriverDataSource(
                        new com.clickhouse.jdbc.Driver(), analyticsJdbcUrl(container));
        dataSource.setConnectionProperties(properties);
        return JdbcClient.create(dataSource);
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
