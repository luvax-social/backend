package com.app.modules.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.analytics.ClickHouseUnavailableException;
import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.modules.admin.config.StatsProperties;
import com.app.modules.admin.dto.response.AdminStatsTimeseriesResponse;
import com.app.modules.admin.enums.PlatformMetric;
import com.app.modules.admin.enums.StatGranularity;
import com.app.modules.admin.repository.PlatformStatsAnalyticsRepository;
import com.app.modules.admin.service.AdminAuthorizationService;
import com.app.modules.hashtag.repository.HashtagRepository;

@ExtendWith(MockitoExtension.class)
class AdminStatsServiceImplTest {

    private static final UUID ADMIN = UUID.randomUUID();

    @Mock private PlatformStatsAnalyticsRepository statsRepository;
    @Mock private HashtagRepository hashtagRepository;
    @Mock private AdminAuthorizationService authorizationService;

    private AdminStatsServiceImpl service;

    @BeforeEach
    void setUp() {
        service =
                new AdminStatsServiceImpl(
                        statsRepository,
                        hashtagRepository,
                        new StatsProperties(true, Duration.ofMinutes(30), Duration.ofDays(30)),
                        authorizationService);
    }

    @Test
    void getTimeseries_dailyFlow_readsWholeUtcDaysAndNeverTheDayInProgress() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime today = now.truncatedTo(ChronoUnit.DAYS);
        when(statsRepository.findDailyFlowSeries(anyString(), any(), any())).thenReturn(List.of());

        service.getTimeseries(
                ADMIN,
                PlatformMetric.REGISTRATIONS,
                StatGranularity.DAY,
                now.minusDays(3).plusHours(5),
                now.plusHours(1));

        ArgumentCaptor<OffsetDateTime> first = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> end = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(statsRepository)
                .findDailyFlowSeries(eq("registrations"), first.capture(), end.capture());
        // The first day is the first UTC midnight at or after the lower bound, and the end is
        // today's midnight even though the window extends into today.
        assertThat(first.getValue()).isEqualTo(today.minusDays(2));
        assertThat(end.getValue()).isEqualTo(today);
    }

    @Test
    void getTimeseries_dailyGauge_usesTheGaugeQuery() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(statsRepository.findDailyGaugeSeries(anyString(), any(), any())).thenReturn(List.of());

        service.getTimeseries(
                ADMIN, PlatformMetric.USERS_TOTAL, StatGranularity.DAY, now.minusDays(5), now);

        verify(statsRepository).findDailyGaugeSeries(eq("users_total"), any(), any());
        verify(statsRepository, never()).findDailyFlowSeries(anyString(), any(), any());
    }

    @Test
    void getTimeseries_aWindowInsideTodayOnly_asksForNoDaysAtAll() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime todayMidnight = now.truncatedTo(ChronoUnit.DAYS);

        AdminStatsTimeseriesResponse response =
                service.getTimeseries(
                        ADMIN,
                        PlatformMetric.REGISTRATIONS,
                        StatGranularity.DAY,
                        todayMidnight.plusSeconds(1),
                        todayMidnight.plusSeconds(2));

        assertThat(response.points()).isEmpty();
        verify(statsRepository, never()).findDailyFlowSeries(anyString(), any(), any());
    }

    @Test
    void getTimeseries_windowOlderThanTheHorizon_isReadAtDayWidth() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(statsRepository.findDailyFlowSeries(anyString(), any(), any())).thenReturn(List.of());

        AdminStatsTimeseriesResponse response =
                service.getTimeseries(
                        ADMIN, PlatformMetric.REGISTRATIONS, null, now.minusDays(31), now);

        assertThat(response.granularity()).isEqualTo(StatGranularity.DAY);
    }

    @Test
    void getTimeseries_halfHourBeyondTheHorizon_isRefusedNamingTheHorizon() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        assertThatThrownBy(
                        () ->
                                service.getTimeseries(
                                        ADMIN,
                                        PlatformMetric.REGISTRATIONS,
                                        StatGranularity.HALF_HOUR,
                                        now.minusDays(31),
                                        now))
                .isInstanceOf(AppException.class)
                .hasMessageContaining(
                        "Half-hour points are served for windows starting within the last 30 days");
    }

    @Test
    void getTimeseries_storeUnavailable_answersTheTypedError() {
        when(statsRepository.findHalfHourSeries(anyString(), any(), any()))
                .thenThrow(
                        new ClickHouseUnavailableException(
                                ClickHouseUnavailableException.Reason.SERVER, "down"));

        assertThatThrownBy(
                        () ->
                                service.getTimeseries(
                                        ADMIN, PlatformMetric.REGISTRATIONS, null, null, null))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.ANALYTICS_UNAVAILABLE));
    }

    @Test
    void getCurrent_storeUnavailable_answersTheTypedErrorWithoutTouchingTheLiveHashtagList() {
        when(statsRepository.findNewestBucket())
                .thenThrow(
                        new ClickHouseUnavailableException(
                                ClickHouseUnavailableException.Reason.CIRCUIT_OPEN, "down"));

        assertThatThrownBy(() -> service.getCurrent(ADMIN))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.ANALYTICS_UNAVAILABLE));
        verify(hashtagRepository, never()).findTopActiveByPostCount(anyInt());
    }

    @Test
    void getCurrent_nothingCollected_returnsEmptyTimestamps() {
        when(statsRepository.findNewestBucket()).thenReturn(Optional.empty());
        when(hashtagRepository.findTopActiveByPostCount(anyInt())).thenReturn(List.of());

        assertThat(service.getCurrent(ADMIN).bucketStart()).isNull();
    }
}
