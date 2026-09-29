package com.app.modules.recommendation.service.impl;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.modules.recommendation.config.HashtagAffinityProperties;
import com.app.modules.recommendation.entity.UserHashtagAffinity;
import com.app.modules.recommendation.repository.UserEventAnalyticsRepository;
import com.app.modules.recommendation.repository.UserEventAnalyticsRepository.AffinitySignal;
import com.app.modules.recommendation.repository.UserHashtagAffinityRepository;
import com.app.modules.recommendation.service.AffinityRecomputeResult;
import com.app.modules.recommendation.service.HashtagAffinityService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class HashtagAffinityServiceImpl implements HashtagAffinityService {

    /** Rows sent to PostgreSQL per batch while the ClickHouse result is streamed in. */
    private static final int STAGE_BATCH_SIZE = 5_000;

    // Dropped first because a second run in one transaction would otherwise find the table the
    // first created; ON COMMIT DROP removes it with the transaction on the normal path.
    private static final String DROP_STAGE_SQL = "DROP TABLE IF EXISTS affinity_signal_stage";

    private static final String CREATE_STAGE_SQL =
            "CREATE TEMP TABLE affinity_signal_stage (user_id uuid NOT NULL, target_kind text NOT"
                    + " NULL, target_id uuid NOT NULL, contribution double precision NOT NULL,"
                    + " event_count bigint NOT NULL) ON COMMIT DROP";

    private static final String INSERT_STAGE_SQL =
            "INSERT INTO affinity_signal_stage"
                    + " (user_id, target_kind, target_id, contribution, event_count)"
                    + " VALUES (?, ?, ?, ?, ?)";

    private final UserHashtagAffinityRepository affinityRepository;
    private final UserEventAnalyticsRepository userEventAnalyticsRepository;
    private final JdbcTemplate jdbcTemplate;
    private final HashtagAffinityProperties properties;

    @Override
    @Transactional
    public AffinityRecomputeResult recompute() {
        OffsetDateTime windowEnd = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime windowStart = windowEnd.minus(properties.window());
        long halfLifeSeconds = properties.halfLife().toSeconds();

        // The signals are read from ClickHouse first and staged in a temporary table of this
        // transaction, so nothing in PostgreSQL has changed when a ClickHouse failure ends the run:
        // the previous rows stay as they were and the job waits for its next cycle.
        AtomicLong staged = new AtomicLong();
        jdbcTemplate.execute(DROP_STAGE_SQL);
        jdbcTemplate.execute(CREATE_STAGE_SQL);
        userEventAnalyticsRepository.streamAffinitySignals(
                windowStart,
                windowEnd,
                halfLifeSeconds,
                STAGE_BATCH_SIZE,
                batch -> {
                    stage(batch);
                    staged.addAndGet(batch.size());
                });

        int written = affinityRepository.recomputeFromStage(windowStart, windowEnd, windowEnd);
        // Same transaction as the upsert on purpose: separating them would expose a moment where a
        // reader sees the previous run's rows for one user and this run's for another.
        int removed = affinityRepository.deleteStale(windowEnd);

        log.info(
                "[affinity] recompute complete | window: {} to {} | signals staged: {} | rows"
                        + " written: {} | stale removed: {}",
                windowStart,
                windowEnd,
                staged.get(),
                written,
                removed);
        return new AffinityRecomputeResult(written, removed, windowStart, windowEnd);
    }

    private void stage(List<AffinitySignal> batch) {
        jdbcTemplate.batchUpdate(
                INSERT_STAGE_SQL,
                batch,
                batch.size(),
                (PreparedStatement ps, AffinitySignal signal) -> bind(ps, signal));
    }

    private static void bind(PreparedStatement ps, AffinitySignal signal) throws SQLException {
        ps.setObject(1, signal.userId());
        ps.setString(2, signal.targetKind());
        ps.setObject(3, signal.targetId());
        ps.setDouble(4, signal.contribution());
        ps.setLong(5, signal.eventCount());
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserHashtagAffinity> findTopForUser(UUID userId, int limit) {
        int effectiveLimit = limit > 0 ? limit : properties.topLimit();
        return affinityRepository.findTopForUser(userId, effectiveLimit);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasAffinity(UUID userId) {
        return affinityRepository.countForUser(userId) > 0;
    }
}
