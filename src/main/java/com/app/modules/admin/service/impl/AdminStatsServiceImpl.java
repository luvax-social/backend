package com.app.modules.admin.service.impl;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.analytics.ClickHouseUnavailableException;
import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.modules.admin.config.StatsProperties;
import com.app.modules.admin.dto.response.AdminStatsCurrentResponse;
import com.app.modules.admin.dto.response.AdminStatsTimeseriesResponse;
import com.app.modules.admin.dto.response.StatPointResponse;
import com.app.modules.admin.dto.response.TopHashtagResponse;
import com.app.modules.admin.enums.PlatformMetric;
import com.app.modules.admin.enums.StatGranularity;
import com.app.modules.admin.repository.PlatformStatsAnalyticsRepository;
import com.app.modules.admin.repository.PlatformStatsAnalyticsRepository.SeriesPoint;
import com.app.modules.admin.repository.PlatformStatsAnalyticsRepository.StatRow;
import com.app.modules.admin.service.AdminAuthorizationService;
import com.app.modules.admin.service.AdminStatsService;
import com.app.modules.hashtag.repository.HashtagRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AdminStatsServiceImpl implements AdminStatsService {

    private static final int TOP_HASHTAG_LIMIT = 10;

    private final PlatformStatsAnalyticsRepository platformStatsRepository;
    private final HashtagRepository hashtagRepository;
    private final StatsProperties properties;
    private final AdminAuthorizationService adminAuthorizationService;

    public AdminStatsServiceImpl(
            PlatformStatsAnalyticsRepository platformStatsRepository,
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
        // Every ClickHouse read happens before the live hashtag list, so an outage answers the
        // whole
        // request with the typed error instead of a snapshot with half its figures missing.
        Optional<OffsetDateTime> newest;
        List<StatRow> rows;
        try {
            newest = platformStatsRepository.findNewestBucket();
            rows = newest.map(platformStatsRepository::findBucket).orElseGet(List::of);
        } catch (ClickHouseUnavailableException ex) {
            throw unavailable(ex);
        }
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
        List<SeriesPoint> points;
        try {
            points = readSeries(metric, effectiveGranularity, effectiveFrom, effectiveTo, now);
        } catch (ClickHouseUnavailableException ex) {
            throw unavailable(ex);
        }
        return new AdminStatsTimeseriesResponse(
                metric.key(),
                effectiveGranularity,
                effectiveFrom,
                effectiveTo,
                points.stream()
                        .map(
                                point ->
                                        new StatPointResponse(
                                                point.bucketStart(),
                                                point.dimension(),
                                                point.value()))
                        .toList());
    }

    private List<SeriesPoint> readSeries(
            PlatformMetric metric,
            StatGranularity granularity,
            OffsetDateTime from,
            OffsetDateTime to,
            OffsetDateTime now) {
        if (granularity == StatGranularity.HALF_HOUR) {
            return platformStatsRepository.findHalfHourSeries(metric.key(), from, to);
        }
        // A day belongs to a daily series when its UTC midnight falls inside [from, to), and the
        // day still in progress is never served as though it were complete.
        OffsetDateTime firstDay = ceilToUtcDay(from);
        OffsetDateTime endDay = min(ceilToUtcDay(to), now.truncatedTo(ChronoUnit.DAYS));
        if (!firstDay.isBefore(endDay)) {
            return List.of();
        }
        return metric.kind() == PlatformMetric.Kind.FLOW
                ? platformStatsRepository.findDailyFlowSeries(metric.key(), firstDay, endDay)
                : platformStatsRepository.findDailyGaugeSeries(metric.key(), firstDay, endDay);
    }

    private static OffsetDateTime ceilToUtcDay(OffsetDateTime instant) {
        OffsetDateTime utc = instant.withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime floor = utc.truncatedTo(ChronoUnit.DAYS);
        return floor.equals(utc) ? floor : floor.plusDays(1);
    }

    private static OffsetDateTime min(OffsetDateTime a, OffsetDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    // Half-hour points are served for a window that starts inside the horizon; a window reaching
    // further back is read at day width, so a chart never asks for a year of half-hour points.
    private StatGranularity granularityFor(OffsetDateTime from, OffsetDateTime now) {
        return from.isBefore(now.minus(properties.halfHourHorizon()))
                ? StatGranularity.DAY
                : StatGranularity.HALF_HOUR;
    }

    // A requested granularity is honoured where the horizon allows it and refused where it does
    // not. Half-hour rows are kept, but a window reaching past the horizon at that width would be
    // thousands of points, and answering it at day width instead would contradict what was asked.
    private StatGranularity resolveGranularity(
            StatGranularity requested, OffsetDateTime from, OffsetDateTime now) {
        StatGranularity available = granularityFor(from, now);
        if (requested == null) {
            return available;
        }
        if (requested == StatGranularity.HALF_HOUR && available == StatGranularity.DAY) {
            throw new AppException(
                    ApiErrorCode.BAD_REQUEST,
                    "Half-hour points are served for windows starting within the last "
                            + properties.halfHourHorizon().toDays()
                            + " days");
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

    private static AppException unavailable(ClickHouseUnavailableException cause) {
        log.warn("Platform statistics unavailable: {}", cause.getMessage());
        return new AppException(ApiErrorCode.ANALYTICS_UNAVAILABLE);
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
