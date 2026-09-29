package com.app.modules.admin.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.app.modules.admin.dto.response.AdminActionSummaryResponse;
import com.app.modules.admin.enums.AdminActionType;

/**
 * Reads pages of the moderation audit log from the store best placed to serve them.
 *
 * <p>ClickHouse answers first, because listing and filtering are analytical reads and the replica
 * carries them without touching the transactional database. When ClickHouse is unavailable the same
 * page is read from PostgreSQL, the system of record, so the audit log never disappears from the
 * admin panel. Both stores order by time and then by the identifier's text form, and the cursor
 * carries exactly those two values, so a cursor minted by one store continues on the other without
 * skipping or repeating a row.
 */
public interface AdminActionListingService {

    /**
     * Reads one keyset page of audit summaries, newest first.
     *
     * <p>The caller has already narrowed the actor filter for a moderator; this method applies
     * whatever filters it is given.
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
