package com.app.modules.admin.service.impl;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.modules.admin.config.StatsProperties;
import com.app.modules.admin.dto.response.AdminStatsCurrentResponse;
import com.app.modules.admin.dto.response.AdminStatsTimeseriesResponse;
import com.app.modules.admin.dto.response.StatPointResponse;
import com.app.modules.admin.dto.response.TopHashtagResponse;
import com.app.modules.admin.enums.PlatformMetric;
import com.app.modules.admin.enums.StatGranularity;
import com.app.modules.admin.repository.PlatformStatsRepository;
import com.app.modules.admin.repository.PlatformStatsRepository.StatRow;
import com.app.modules.admin.service.AdminAuthorizationService;
import com.app.modules.admin.service.AdminStatsService;
import com.app.modules.hashtag.repository.HashtagRepository;

@Service
public class AdminStatsServiceImpl implements AdminStatsService {

    private static final int TOP_HASHTAG_LIMIT = 10;

    private final PlatformStatsRepository platformStatsRepository;
    private final HashtagRepository hashtagRepository;
    private final StatsProperties properties;
    private final AdminAuthorizationService adminAuthorizationService;

    public AdminStatsServiceImpl(
            PlatformStatsRepository platformStatsRepository,
            HashtagRepository hashtagRepository,
            StatsProperties properties,
            AdminAuthorizationService adminAuthorizationService) {
        this.platformStatsRepository = platformStatsRepository;
        this.hashtagRepository = hashtagRepository;
        this.properties = properties;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @Override
    @Transactional(readOnly = true)
    public AdminStatsCurrentResponse getCurrent(UUID actorId) {
        adminAuthorizationService.assertActorIsAdministrator(actorId);
        Optional<OffsetDateTime> newest =
                platformStatsRepository.findNewestBucket(StatGranularity.HALF_HOUR);
        List<StatRow> rows =
                newest.map(
                                bucket ->
                                        platformStatsRepository.findBucket(
                                                StatGranularity.HALF_HOUR, bucket))
                        .orElseGet(List::of);
        Map<String, Map<String, Long>> byMetric = new LinkedHashMap<>();
        OffsetDateTime computedAt = null;
        for (StatRow row : rows) {
            byMetric.computeIfAbsent(row.metricKey(), key -> new LinkedHashMap<>())
                    .put(row.dimension(), row.value());
            if (computedAt == null || row.computedAt().isAfter(computedAt)) {
                computedAt = row.computedAt();
            }
        }
        return new AdminStatsCurrentResponse(
                newest.orElse(null),
                computedAt,
                scalar(byMetric, PlatformMetric.USERS_TOTAL),
                breakdown(byMetric, PlatformMetric.USERS_BY_STATUS),
                breakdown(byMetric, PlatformMetric.USERS_BY_ROLE),
                scalar(byMetric, PlatformMetric.POSTS_TOTAL),
                scalar(byMetric, PlatformMetric.COMMENTS_TOTAL),
                scalar(byMetric, PlatformMetric.STORIES_TOTAL),
                breakdown(byMetric, PlatformMetric.REPORTS_BY_STATUS),
                breakdown(byMetric, PlatformMetric.REPORTS_BY_REASON),
                topHashtags(),
                true);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminStatsTimeseriesResponse getTimeseries(
            UUID actorId,
            PlatformMetric metric,
            StatGranularity granularity,
            OffsetDateTime from,
            OffsetDateTime to) {
        adminAuthorizationService.assertActorIsAdministrator(actorId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime effectiveFrom = from;
        OffsetDateTime effectiveTo = to;
        if (from == null && to == null) {
            effectiveTo = now;
            effectiveFrom = now.minus(DEFAULT_TIMESERIES_WINDOW);
        } else if (from == null || to == null) {
            // Defaulting only one bound would silently answer a different question from the one
            // asked, and the caller has no way to see which bound was invented.
            throw new AppException(
                    ApiErrorCode.BAD_REQUEST,
                    "Supply both 'from' and 'to', or neither for the last 24 hours");
        }
        validateWindow(effectiveFrom, effectiveTo);

        StatGranularity effectiveGranularity = resolveGranularity(granularity, effectiveFrom, now);
        List<StatPointResponse> points =
                platformStatsRepository
                        .findSeries(metric.key(), effectiveGranularity, effectiveFrom, effectiveTo)
                        .stream()
                        .map(
                                row ->
                                        new StatPointResponse(
                                                row.bucketStart(), row.dimension(), row.value()))
                        .toList();
        return new AdminStatsTimeseriesResponse(
                metric.key(), effectiveGranularity, effectiveFrom, effectiveTo, points);
    }

    // Fine buckets survive only inside the fine retention window, so a window whose lower bound is
    // older than that has nothing fine left to read and must be served from the daily rows.
    private StatGranularity granularityFor(OffsetDateTime from, OffsetDateTime now) {
        return from.isBefore(now.minus(properties.fineRetention()))
                ? StatGranularity.DAY
                : StatGranularity.HALF_HOUR;
    }

    // A requested granularity is honoured where the rows exist and refused where they do not.
    // Answering an unavailable request with an empty series would be worse than refusing it: the
    // caller cannot tell a stretch that was rolled up and deleted from one in which nothing
    // happened, and the response's own granularity field would contradict what was asked for.
    private StatGranularity resolveGranularity(
            StatGranularity requested, OffsetDateTime from, OffsetDateTime now) {
        StatGranularity available = granularityFor(from, now);
        if (requested == null) {
            return available;
        }
        if (requested == StatGranularity.HALF_HOUR && available == StatGranularity.DAY) {
            throw new AppException(
                    ApiErrorCode.BAD_REQUEST,
                    "Fine buckets are kept for "
                            + properties.fineRetention().toDays()
                            + " days; a window reaching further back can only be read at day"
                            + " granularity");
        }
        return requested;
    }

    private static void validateWindow(OffsetDateTime from, OffsetDateTime to) {
        if (!to.isAfter(from)) {
            throw new AppException(ApiErrorCode.BAD_REQUEST, "'to' must be later than 'from'");
        }
        if (Duration.between(from, to).compareTo(MAX_TIMESERIES_WINDOW) > 0) {
            throw new AppException(
                    ApiErrorCode.BAD_REQUEST, "The window may span at most one year");
        }
    }

    private List<TopHashtagResponse> topHashtags() {
        return hashtagRepository.findTopActiveByPostCount(TOP_HASHTAG_LIMIT).stream()
                .map(tag -> new TopHashtagResponse(tag.getName(), tag.getPostCount()))
                .toList();
    }

    private static long scalar(Map<String, Map<String, Long>> byMetric, PlatformMetric metric) {
        return byMetric.getOrDefault(metric.key(), Map.of()).getOrDefault("", 0L);
    }

    private static Map<String, Long> breakdown(
            Map<String, Map<String, Long>> byMetric, PlatformMetric metric) {
        return byMetric.getOrDefault(metric.key(), Map.of());
    }
}
