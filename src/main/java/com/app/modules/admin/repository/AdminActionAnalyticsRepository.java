package com.app.modules.admin.repository;

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
}
