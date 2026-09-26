package com.app.modules.hashtag.service.impl;

import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.response.PageResponse;
import com.app.modules.hashtag.config.HashtagProperties;
import com.app.modules.hashtag.dto.response.HashtagTrendingResponse;
import com.app.modules.hashtag.dto.response.TrendingPreviewResponse;
import com.app.modules.hashtag.entity.Hashtag;
import com.app.modules.hashtag.entity.HashtagTrending;
import com.app.modules.hashtag.entity.HashtagTrendingId;
import com.app.modules.hashtag.enums.HashtagStatus;
import com.app.modules.hashtag.enums.TrendingSource;
import com.app.modules.hashtag.mapper.HashtagMapper;
import com.app.modules.hashtag.repository.HashtagRepository;
import com.app.modules.hashtag.repository.HashtagTrendingRepository;
import com.app.modules.hashtag.service.HashtagTrendingService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class HashtagTrendingServiceImpl implements HashtagTrendingService {

    private static final int MAX_TRENDING = 100;

    private static final String UPSERT_SQL =
            "INSERT INTO hashtag_trending (hashtag_id, period_start, period_end, post_count, rank)"
                    + " VALUES (?, ?, ?, ?, ?)"
                    + " ON CONFLICT (hashtag_id, period_start) DO UPDATE SET"
                    + " period_end = EXCLUDED.period_end,"
                    + " post_count = EXCLUDED.post_count,"
                    + " rank = EXCLUDED.rank";

    private final HashtagTrendingRepository hashtagTrendingRepository;
    private final HashtagRepository hashtagRepository;
    private final HashtagMapper hashtagMapper;
    private final HashtagProperties properties;
    private final JdbcTemplate jdbcTemplate;

    public HashtagTrendingServiceImpl(
            HashtagTrendingRepository hashtagTrendingRepository,
            HashtagRepository hashtagRepository,
            HashtagMapper hashtagMapper,
            HashtagProperties properties,
            JdbcTemplate jdbcTemplate) {
        this.hashtagTrendingRepository = hashtagTrendingRepository;
        this.hashtagRepository = hashtagRepository;
        this.hashtagMapper = hashtagMapper;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(
            fixedDelayString = "${app.hashtag.trending.job-delay:PT1H}",
            initialDelayString = "${app.hashtag.trending.job-initial-delay:PT5M}")
    @Transactional
    public void runTrendingJob() {
        OffsetDateTime windowEnd = OffsetDateTime.now(ZoneOffset.UTC);
        // Truncated to the hour so repeated runs within the same hour share one period_start,
        // making the snapshot genuinely periodic instead of growing one row set per invocation.
        OffsetDateTime windowStart =
                windowEnd.minus(properties.getTrending().getWindow()).truncatedTo(ChronoUnit.HOURS);
        int written = snapshotTrending(windowStart, windowEnd).size();
        log.info(
                "Hashtag trending snapshot written: {} rows for window {} to {}",
                written,
                windowStart,
                windowEnd);
    }

    @Override
    @Transactional
    public List<HashtagTrending> snapshotTrending(
            OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        List<TrendingCount> counts =
                jdbcTemplate.query(
                        // The join to hashtags exists only for the status predicate: a banned or
                        // deleted tag belongs on no discovery surface, and trending is the most
                        // prominent one there is. The immediate purge on a status change keeps the
                        // current snapshot clean; this keeps the next one from putting the tag
                        // straight back.
                        "SELECT ph.hashtag_id, COUNT(ph.post_id) AS post_count"
                                + " FROM post_hashtags ph"
                                + " JOIN posts p ON p.id = ph.post_id"
                                + " JOIN hashtags h ON h.id = ph.hashtag_id"
                                + " WHERE p.created_at >= ? AND p.created_at < ? AND p.deleted_at IS NULL"
                                + " AND h.status = 'active'"
                                + " GROUP BY ph.hashtag_id"
                                + " ORDER BY post_count DESC"
                                + " LIMIT "
                                + MAX_TRENDING,
                        (rs, rowNum) ->
                                new TrendingCount(
                                        rs.getObject("hashtag_id", java.util.UUID.class),
                                        rs.getInt("post_count")),
                        windowStart,
                        windowEnd);

        List<HashtagTrending> rows = new ArrayList<>(counts.size());
        int rank = 1;
        for (TrendingCount c : counts) {
            rows.add(
                    HashtagTrending.builder()
                            .id(new HashtagTrendingId(c.hashtagId(), windowStart))
                            .periodEnd(windowEnd)
                            .postCount(c.postCount())
                            .rank(rank++)
                            .build());
        }
        // Clean-replace the snapshot for this period so a re-run drops hashtags that
        // fell out of the top ranks rather than leaving stale rows with colliding ranks.
        jdbcTemplate.update("DELETE FROM hashtag_trending WHERE period_start = ?", windowStart);
        upsertRows(rows);
        return rows;
    }

    // Plain INSERT would throw a duplicate-key error when two instances snapshot the same
    // truncated period_start concurrently. ON CONFLICT DO UPDATE makes concurrent writers converge
    // on a last-writer-wins result instead: the job is a pure recomputation from post_hashtags and
    // posts, so two instances computing the same window agree on the answer, and merging is
    // correct where locking would only be defensive.
    private void upsertRows(List<HashtagTrending> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                UPSERT_SQL,
                rows,
                rows.size(),
                (PreparedStatement ps, HashtagTrending row) -> {
                    ps.setObject(1, row.getId().getHashtagId());
                    ps.setObject(2, row.getId().getPeriodStart());
                    ps.setObject(3, row.getPeriodEnd());
                    ps.setInt(4, row.getPostCount());
                    ps.setObject(5, row.getRank());
                });
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<HashtagTrendingResponse> getTrending(Pageable pageable) {
        // Queries aggregate MAX; returns null when hashtag_trending is empty
        OffsetDateTime latest =
                jdbcTemplate.queryForObject(
                        "SELECT MAX(period_start) FROM hashtag_trending", OffsetDateTime.class);
        if (latest == null) {
            return PageResponse.from(Page.empty(pageable));
        }

        // Pinned first, then rank. Ordered in the database rather than after paging, because
        // sorting a fetched page would only float a pinned hashtag to the top of whichever page it
        // already landed on, which is not what a platform-wide pin means.
        List<HashtagTrending> rows =
                hashtagTrendingRepository.findByPeriodPinnedFirst(
                        latest, pageable.getPageSize(), (int) pageable.getOffset());
        List<UUID> ids = rows.stream().map(r -> r.getId().getHashtagId()).toList();
        Map<UUID, Hashtag> byId =
                hashtagRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(Hashtag::getId, h -> h));
        List<HashtagTrendingResponse> content =
                rows.stream()
                        .map(
                                r -> {
                                    Hashtag h = byId.get(r.getId().getHashtagId());
                                    return new HashtagTrendingResponse(
                                            r.getId().getHashtagId(),
                                            h == null ? null : h.getName(),
                                            r.getPostCount(),
                                            r.getRank() == null ? 0 : r.getRank(),
                                            r.getId().getPeriodStart(),
                                            r.getPeriodEnd(),
                                            h != null && h.getPinnedAt() != null,
                                            TrendingSource.PLATFORM);
                                })
                        .toList();

        long total = hashtagTrendingRepository.countByIdPeriodStart(latest);
        Page<HashtagTrendingResponse> page = new PageImpl<>(content, pageable, total);
        return PageResponse.from(page);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TrendingPreviewResponse> attachCovers(List<HashtagTrendingResponse> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = entries.stream().map(HashtagTrendingResponse::hashtagId).toList();
        Map<UUID, String> covers = new HashMap<>();
        for (Object[] row : hashtagTrendingRepository.findCoverUrls(ids)) {
            if (row[1] != null) {
                covers.put((UUID) row[0], (String) row[1]);
            }
        }
        return entries.stream()
                .map(
                        entry ->
                                new TrendingPreviewResponse(
                                        entry.hashtagId(),
                                        entry.name(),
                                        entry.postCount(),
                                        entry.pinned(),
                                        covers.get(entry.hashtagId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<HashtagTrendingResponse> describeHashtags(List<UUID> hashtagIds) {
        if (hashtagIds.isEmpty()) {
            return List.of();
        }
        OffsetDateTime latest =
                jdbcTemplate.queryForObject(
                        "SELECT MAX(period_start) FROM hashtag_trending", OffsetDateTime.class);
        OffsetDateTime periodEnd =
                latest == null
                        ? null
                        : jdbcTemplate.queryForObject(
                                "SELECT MAX(period_end) FROM hashtag_trending WHERE period_start"
                                        + " = ?",
                                OffsetDateTime.class,
                                latest);
        return hashtagRepository.findAllById(hashtagIds).stream()
                .filter(h -> h.getStatus() == HashtagStatus.ACTIVE)
                .map(
                        h ->
                                new HashtagTrendingResponse(
                                        h.getId(),
                                        h.getName(),
                                        // Null, not h.getPostCount(). This hashtag is not in the
                                        // snapshot, so it has no window count. Substituting the
                                        // lifetime association count put two different
                                        // measurements in one column: "#fnblife 1 posts" (one post
                                        // this window) read as smaller than "#goldprice 40 posts"
                                        // (forty associations since 2025), which is a comparison
                                        // the reader cannot make and is not told they are making.
                                        null,
                                        0,
                                        latest,
                                        periodEnd,
                                        h.getPinnedAt() != null,
                                        TrendingSource.PLATFORM))
                .toList();
    }

    private record TrendingCount(UUID hashtagId, int postCount) {}
}
