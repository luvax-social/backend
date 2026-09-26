package com.app.modules.admin.service;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.app.common.response.CursorPageResponse;
import com.app.modules.recommendation.dto.response.UserEventResponse;
import com.app.modules.recommendation.enums.UserEventType;

/** Administrative read access to the behavioural event stream. */
public interface AdminUserEventService {

    /** Longest window a single query may span. */
    int MAX_WINDOW_DAYS = 30;

    /**
     * Returns one keyset page of behavioural events inside a mandatory bounded window.
     *
     * <p>The window is mandatory rather than defaulted because {@code user_events} is partitioned
     * by {@code created_at}: a query bounded only by {@code userId} prunes nothing and reads every
     * partition ever created, which is exactly the unbounded full-table read this endpoint exists
     * to make impossible. An investigator paginates a month at a time.
     *
     * @param userId restrict to one account, or null for every account in the window
     * @param from inclusive lower bound; required
     * @param to exclusive upper bound; required, strictly after {@code from}, and no more than
     *     {@link #MAX_WINDOW_DAYS} days later
     * @param eventType restrict to one event type, or null for all of them
     * @param cursor opaque cursor from a previous page of this endpoint; null for the first page
     * @param limit requested page size
     * @return events newest first
     * @throws com.app.common.exception.AppException {@code BAD_REQUEST} when either bound is
     *     absent, when {@code to} is not after {@code from}, or when the window exceeds {@link
     *     #MAX_WINDOW_DAYS} days; {@code INVALID_CURSOR} for a cursor this endpoint did not issue
     */
    CursorPageResponse<UserEventResponse> listUserEvents(
            UUID actorId,
            UUID userId,
            OffsetDateTime from,
            OffsetDateTime to,
            UserEventType eventType,
            String cursor,
            int limit);
}
