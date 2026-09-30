package com.app.common.analytics.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Applies the versioned scripts under {@code clickhouse/migration} to {@code luvax_analytics}.
 *
 * <p>ClickHouse DDL is not transactional, so a failed script may have applied some statements. The
 * runner therefore writes a script's history row only after its last statement succeeds, and every
 * statement is written to be safe to run twice, so a failed script is simply run again from its
 * first statement and no repair step exists.
 *
 * <p>Versions apply in ascending order; a pending script below the highest applied version is
 * refused, and so is an applied script whose checksum changed. ClickHouse has no lock of its own,
 * so the runner holds a PostgreSQL advisory lock for its duration and two application instances
 * never migrate at once.
 */
@Component
@ConditionalOnProperty(name = "app.analytics.enabled", havingValue = "true")
public class ClickHouseMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseMigrationRunner.class);

    /** Advisory lock key held on the primary database while migrating. */
    static final long ADVISORY_LOCK_KEY = 0x4C56_4158_4348_0001L;

    static final String DEFAULT_LOCATION = "classpath:clickhouse/migration/";

    private static final Duration DEFAULT_LOCK_WAIT = Duration.ofMinutes(2);
    private static final long LOCK_POLL_MILLIS = 250;

    private static final String CREATE_HISTORY =
            """
			CREATE TABLE IF NOT EXISTS schema_migrations
			(
				version      UInt32,
				description  String,
				script       String,
				checksum     FixedString(64),
				execution_ms UInt32,
				applied_at   DateTime64(6, 'UTC') DEFAULT now64(6)
			)
			ENGINE = MergeTree
			ORDER BY version
			""";

    private static final String INSERT_HISTORY =
            "INSERT INTO schema_migrations (version, description, script, checksum, execution_ms)"
                    + " SETTINGS async_insert = 0 VALUES (?, ?, ?, ?, ?)";

    private final DataSource clickHouseMigrator;
    private final DataSource primary;
    private final String location;
    private final Duration lockWait;

    @Autowired
    public ClickHouseMigrationRunner(
            @Qualifier("clickHouseMigrator") DataSource clickHouseMigrator, DataSource primary) {
        this(clickHouseMigrator, primary, DEFAULT_LOCATION, DEFAULT_LOCK_WAIT);
    }

    /**
     * Builds a runner over another script location and lock wait.
     *
     * @param clickHouseMigrator pool of the DDL user, connected to {@code luvax_analytics}
     * @param primary the primary PostgreSQL pool, used only for the advisory lock
     * @param location classpath location of the scripts, ending in a slash
     * @param lockWait how long to wait for another runner to finish before giving up
     */
    public ClickHouseMigrationRunner(
            DataSource clickHouseMigrator, DataSource primary, String location, Duration lockWait) {
        this.clickHouseMigrator = clickHouseMigrator;
        this.primary = primary;
        this.location = location;
        this.lockWait = lockWait;
    }

    /**
     * Applies every pending script.
     *
     * @return the scripts applied by this call, in order; empty when the schema was current
     * @throws ClickHouseMigrationException when a checksum changed, a version is out of order, the
     *     lock cannot be taken in time or a statement fails
     */
    public List<ClickHouseMigrationScript> migrate() {
        List<ClickHouseMigrationScript> scripts = loadScripts();
        try (Connection lockConnection = primary.getConnection()) {
            acquireLock(lockConnection);
            try {
                return applyPending(scripts);
            } finally {
                releaseLock(lockConnection);
            }
        } catch (SQLException e) {
            throw new ClickHouseMigrationException(
                    "ClickHouse migration failed: " + e.getMessage(), e);
        }
    }

    private List<ClickHouseMigrationScript> applyPending(List<ClickHouseMigrationScript> scripts)
            throws SQLException {
        try (Connection connection = clickHouseMigrator.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(CREATE_HISTORY);
            }
            Map<Integer, String> applied = readApplied(connection);
            int highestApplied = applied.keySet().stream().max(Integer::compare).orElse(0);
            List<ClickHouseMigrationScript> pending = new ArrayList<>();
            for (ClickHouseMigrationScript script : scripts) {
                String recorded = applied.get(script.version());
                if (recorded == null) {
                    if (script.version() < highestApplied) {
                        throw new ClickHouseMigrationException(
                                "Out-of-order migration: "
                                        + script.fileName()
                                        + " is below the highest applied version "
                                        + highestApplied);
                    }
                    pending.add(script);
                } else if (!recorded.equals(script.checksum())) {
                    throw new ClickHouseMigrationException(
                            "Checksum mismatch for "
                                    + script.fileName()
                                    + ": an applied script must never be edited");
                }
            }
            for (ClickHouseMigrationScript script : pending) {
                apply(connection, script);
            }
            return List.copyOf(pending);
        }
    }

    private static Map<Integer, String> readApplied(Connection connection) throws SQLException {
        Map<Integer, String> applied = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT version, checksum FROM schema_migrations ORDER BY version")) {
            while (rows.next()) {
                applied.put(rows.getInt(1), rows.getString(2));
            }
        }
        return applied;
    }

    private static void apply(Connection connection, ClickHouseMigrationScript script)
            throws SQLException {
        long started = System.nanoTime();
        for (String sql : script.statements()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException e) {
                throw new ClickHouseMigrationException(
                        "ClickHouse migration "
                                + script.fileName()
                                + " failed on a statement, code="
                                + e.getErrorCode()
                                + ": "
                                + e.getMessage(),
                        e);
            }
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        try (PreparedStatement insert = connection.prepareStatement(INSERT_HISTORY)) {
            insert.setInt(1, script.version());
            insert.setString(2, script.description());
            insert.setString(3, script.fileName());
            insert.setString(4, script.checksum());
            insert.setInt(5, (int) elapsedMillis);
            insert.executeUpdate();
        }
        log.info(
                "[analytics] applied clickhouse migration {} in {} ms",
                script.fileName(),
                elapsedMillis);
    }

    private void acquireLock(Connection connection) throws SQLException {
        long deadline = System.nanoTime() + lockWait.toNanos();
        while (true) {
            try (PreparedStatement statement =
                    connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                statement.setLong(1, ADVISORY_LOCK_KEY);
                try (ResultSet row = statement.executeQuery()) {
                    if (row.next() && row.getBoolean(1)) {
                        return;
                    }
                }
            }
            if (System.nanoTime() >= deadline) {
                throw new ClickHouseMigrationException(
                        "Another ClickHouse migration run held the lock for over " + lockWait);
            }
            try {
                Thread.sleep(LOCK_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ClickHouseMigrationException("Interrupted while waiting for the lock", e);
            }
        }
    }

    private static void releaseLock(Connection connection) {
        // The lock is session-level and the connection goes back to the pool still open, so it
        // must be released explicitly.
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, ADVISORY_LOCK_KEY);
            statement.executeQuery().close();
        } catch (SQLException e) {
            log.warn("[analytics] could not release the clickhouse migration lock", e);
        }
    }

    List<ClickHouseMigrationScript> loadScripts() {
        try {
            Resource[] resources =
                    new PathMatchingResourcePatternResolver().getResources(location + "V*.sql");
            List<ClickHouseMigrationScript> scripts = new ArrayList<>();
            Set<Integer> versions = new HashSet<>();
            for (Resource resource : resources) {
                String content;
                try (InputStream in = resource.getInputStream()) {
                    content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                ClickHouseMigrationScript script =
                        ClickHouseMigrationScript.parse(resource.getFilename(), content);
                if (!versions.add(script.version())) {
                    throw new ClickHouseMigrationException(
                            "Two migrations share version " + script.version());
                }
                scripts.add(script);
            }
            scripts.sort(Comparator.comparingInt(ClickHouseMigrationScript::version));
            return scripts;
        } catch (IOException e) {
            throw new ClickHouseMigrationException("Cannot read ClickHouse migrations", e);
        }
    }
}
