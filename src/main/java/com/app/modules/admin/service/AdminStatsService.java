package com.app.modules.admin.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.app.modules.admin.dto.response.AdminStatsCurrentResponse;
import com.app.modules.admin.dto.response.AdminStatsTimeseriesResponse;
import com.app.modules.admin.enums.PlatformMetric;
import com.app.modules.admin.enums.StatGranularity;

/** Administrative read access to platform statistics. */
public interface AdminStatsService {

    /** Window used when a caller supplies neither bound. */
    Duration DEFAULT_TIMESERIES_WINDOW = Duration.ofHours(24);

    /** Longest window a single series read may span. */
    Duration MAX_TIMESERIES_WINDOW = Duration.ofDays(365);

    /**
     * Returns the newest stored snapshot without computing any of it.
     *
     * <p>Reads the most recent collected bucket from the analytics store. It deliberately issues no
     * aggregate over {@code users}, {@code posts} or {@code comments}: at production size each of
     * those is a scan of millions of index entries and takes seconds, which the collection job
     * absorbs where seconds do not matter. The only figure computed at request time is the
     * most-used hashtag list, which an index serves as a scan with a limit and which is flagged as
     * live in the response.
     *
     * @return the newest snapshot, with null timestamps and empty breakdowns when nothing has been
     *     collected yet
     * @throws com.app.common.exception.AppException {@code ANALYTICS_UNAVAILABLE} when the
     *     analytics store cannot answer, for the whole response including the live hashtag list, so
     *     a caller never receives half a snapshot
     */
    AdminStatsCurrentResponse getCurrent(UUID actorId);

    /**
     * Returns one metric's stored series over a window.
     *
     * <p>Granularity may be requested or left to the server. Left to the server, a window whose
     * lower bound is inside the half-hour horizon is served as half-hour points and one reaching
     * further back as daily points. There is no stored daily grain: a daily point of a flow is the
     * sum of that UTC day's buckets, and a daily point of a gauge is the state in the day's last
     * bucket. The day still in progress is never returned, because it is not complete. Whichever
     * way the width was decided, the choice is stated in the response.
     *
     * <p>Requesting half-hour points for a window that starts before the horizon is refused rather
     * than answered at day width, because the response would then contradict the request.
     *
     * @param metric metric to read
     * @param granularity bucket width to read at, or null to let the server choose from the window
     * @param from inclusive lower bound; defaults with {@code to} to the last {@link
     *     #DEFAULT_TIMESERIES_WINDOW} when both are absent
     * @param to exclusive upper bound
     * @return the series, with the granularity that was used
     * @throws com.app.common.exception.AppException {@code BAD_REQUEST} when exactly one bound is
     *     supplied, when {@code to} is not after {@code from}, when the window exceeds {@link
     *     #MAX_TIMESERIES_WINDOW}, or when half-hour points are requested for a window that starts
     *     before the horizon; {@code ANALYTICS_UNAVAILABLE} when the analytics store cannot answer.
     *     An unknown metric or granularity never reaches here: both parameters are typed, so Spring
     *     MVC refuses the conversion and answers 400 before the request is dispatched.
     */
    AdminStatsTimeseriesResponse getTimeseries(
            UUID actorId,
            PlatformMetric metric,
            StatGranularity granularity,
            OffsetDateTime from,
            OffsetDateTime to);
}
