package com.app.modules.recommendation.rebuild;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * Reads and writes {@code gorse_rebuild_runs} with plain JDBC.
 *
 * <p>Every write is one statement, so each commits on its own the moment it returns. That is what
 * makes a checkpoint durable before the next batch starts, without holding a transaction open
 * across calls to Gorse.
 */
@Repository
@RequiredArgsConstructor
public class GorseRebuildRunRepository {

    private static final String COLUMNS =
            "id, token, status, phase, checkpoint_id, users_sent, items_sent, feedback_sent,"
                    + " feedback_skipped, last_error, started_at, finished_at";

    private final JdbcTemplate jdbc;

    /** Finds the run an operator named with this token, if one exists. */
    public Optional<GorseRebuildRun> findByToken(String token) {
        return jdbc
                .query(
                        "SELECT " + COLUMNS + " FROM gorse_rebuild_runs WHERE token = ?",
                        (rs, row) -> map(rs),
                        token)
                .stream()
                .findFirst();
    }

    /** Starts a run at the first phase. */
    public GorseRebuildRun create(String token) {
        return jdbc.queryForObject(
                "INSERT INTO gorse_rebuild_runs (token, status, phase) VALUES (?, 'RUNNING',"
                        + " 'PREFLIGHT') RETURNING "
                        + COLUMNS,
                (rs, row) -> map(rs),
                token);
    }

    /** Writes the run's progress, and clears the last error when it is running again. */
    public void save(GorseRebuildRun run) {
        jdbc.update(
                "UPDATE gorse_rebuild_runs SET status = ?, phase = ?, checkpoint_id = ?,"
                        + " users_sent = ?, items_sent = ?, feedback_sent = ?,"
                        + " feedback_skipped = ?, last_error = ?, finished_at = ? WHERE id = ?",
                run.getStatus().name(),
                run.getPhase().name(),
                run.getCheckpointId(),
                run.getUsersSent(),
                run.getItemsSent(),
                run.getFeedbackSent(),
                run.getFeedbackSkipped(),
                run.getLastError(),
                run.getFinishedAt(),
                run.getId());
    }

    private static GorseRebuildRun map(ResultSet rs) throws SQLException {
        GorseRebuildRun run = new GorseRebuildRun();
        run.setId(rs.getObject("id", UUID.class));
        run.setToken(rs.getString("token"));
        run.setStatus(GorseRebuildStatus.valueOf(rs.getString("status")));
        run.setPhase(GorseRebuildPhase.valueOf(rs.getString("phase")));
        run.setCheckpointId(rs.getObject("checkpoint_id", UUID.class));
        run.setUsersSent(rs.getLong("users_sent"));
        run.setItemsSent(rs.getLong("items_sent"));
        run.setFeedbackSent(rs.getLong("feedback_sent"));
        run.setFeedbackSkipped(rs.getLong("feedback_skipped"));
        run.setLastError(rs.getString("last_error"));
        run.setStartedAt(rs.getObject("started_at", OffsetDateTime.class));
        run.setFinishedAt(rs.getObject("finished_at", OffsetDateTime.class));
        return run;
    }
}
