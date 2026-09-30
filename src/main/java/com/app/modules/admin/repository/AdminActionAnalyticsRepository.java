package com.app.modules.admin.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.app.modules.admin.dto.response.AdminActionSummaryResponse;
import com.app.modules.admin.enums.AdminActionType;

/**
 * The ClickHouse replica of the {@code admin_actions} audit log.
 *
 * <p>PostgreSQL stays the system of record. This store exists for listing, filtering and the
 * business dashboards, and it lags the source by a few seconds.
 */
public interface AdminActionAnalyticsRepository {

    /**
     * Writes one audit row into the replica.
     *
     * <p>Acknowledged only after ClickHouse has written the part, so a consumer that acknowledges
     * its message afterwards never loses the row. Writing the same row twice is harmless, and a
     * higher {@code rowVersion} replaces a lower one when ClickHouse merges.
     *
     * @param row the audit row exactly as PostgreSQL holds it
     * @throws com.app.common.analytics.ClickHouseUnavailableException when the store cannot take
     *     the write right now
     */
    void insert(AdminActionReplicaRow row);

    /**
     * Reads one keyset page of the audit log from the replica, newest first.
     *
     * <p>Reads with {@code FINAL} so a row that was replicated twice, or rewritten by a cascade,
     * appears once in its newest state. Ties on the recording time are ordered by the identifier's
     * text form, which is the byte-wise UUID order PostgreSQL uses, and the cursor predicate uses
     * the same text, so a cursor minted here continues on PostgreSQL and the reverse.
     *
     * @param adminId only rows recorded by this actor, or null for every actor
     * @param targetUserId only rows taken against this account, or null for every target
     * @param actionType only rows of this type, or null for every type
     * @param from inclusive lower bound on the recording time, or null for unbounded
     * @param to exclusive upper bound on the recording time, or null for unbounded
     * @param cursorCreatedAt recording time of the last row of the previous page, or null
     * @param cursorId identifier of the last row of the previous page, or null
     * @param limit maximum number of rows to return
     * @return the rows after the cursor, newest first
     * @throws com.app.common.analytics.ClickHouseUnavailableException when the replica cannot
     *     answer right now
     */
    List<AdminActionSummaryResponse> findActions(
            UUID adminId,
            UUID targetUserId,
            AdminActionType actionType,
            OffsetDateTime from,
            OffsetDateTime to,
            OffsetDateTime cursorCreatedAt,
            UUID cursorId,
            int limit);
}
