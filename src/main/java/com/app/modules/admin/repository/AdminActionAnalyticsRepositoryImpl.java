package com.app.modules.admin.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.app.common.analytics.ClickHouseOperations;
import com.app.modules.admin.dto.response.AdminActionSummaryResponse;
import com.app.modules.admin.enums.AdminActionType;

@Repository
public class AdminActionAnalyticsRepositoryImpl implements AdminActionAnalyticsRepository {

    // wait_for_async_insert = 1 makes the call return only once the part is written, so the message
    // acknowledgement that follows can never precede a durable row. The busy timeout comes from the
    // writer's settings profile, so it can be tuned in ClickHouse without a deploy.
    private static final String INSERT_SQL =
            String.join(
                    " ",
                    "INSERT INTO admin_actions",
                    "(id, admin_id, action_type, target_user_id, target_entity_type,",
                    "target_entity_id, report_id, reason, metadata, created_at, row_version)",
                    "SETTINGS async_insert = 1, wait_for_async_insert = 1",
                    "VALUES (:id, :adminId, :actionType, :targetUserId, :targetEntityType,",
                    ":targetEntityId, :reportId, :reason, :metadata, :createdAt, :rowVersion)");

    private static final String SELECT_SQL =
            String.join(
                    " ",
                    "SELECT id, admin_id, action_type, target_user_id, target_entity_type,",
                    "target_entity_id, report_id, reason, created_at",
                    "FROM admin_actions FINAL WHERE 1 = 1");

    private final ClickHouseOperations clickHouse;

    public AdminActionAnalyticsRepositoryImpl(ClickHouseOperations clickHouse) {
        this.clickHouse = clickHouse;
    }

    @Override
    public void insert(AdminActionReplicaRow row) {
        String metadata = row.getMetadata() == null ? "" : row.getMetadata();
        clickHouse.write(
                "admin_actions.insert",
                client ->
                        client.sql(INSERT_SQL)
                                .param("id", row.getId())
                                .param("adminId", row.getAdminId())
                                .param("actionType", row.getActionType())
                                .param("targetUserId", row.getTargetUserId())
                                .param("targetEntityType", row.getTargetEntityType())
                                .param("targetEntityId", row.getTargetEntityId())
                                .param("reportId", row.getReportId())
                                .param("reason", row.getReason())
                                .param("metadata", metadata)
                                .param("createdAt", row.getCreatedAt().atOffset(ZoneOffset.UTC))
                                .param("rowVersion", row.getRowVersion())
                                .update());
    }

    @Override
    public List<AdminActionSummaryResponse> findActions(
            UUID adminId,
            UUID targetUserId,
            AdminActionType actionType,
            OffsetDateTime from,
            OffsetDateTime to,
            OffsetDateTime cursorCreatedAt,
            UUID cursorId,
            int limit) {
        StringBuilder sql = new StringBuilder(SELECT_SQL);
        Map<String, Object> params = new LinkedHashMap<>();
        if (adminId != null) {
            sql.append(" AND admin_id = :adminId");
            params.put("adminId", adminId);
        }
        if (targetUserId != null) {
            sql.append(" AND target_user_id = :targetUserId");
            params.put("targetUserId", targetUserId);
        }
        if (actionType != null) {
            sql.append(" AND action_type = :actionType");
            params.put("actionType", actionType.toJson());
        }
        // Half-open on purpose: [from, to). Two adjacent windows then partition the log with no row
        // counted twice and none skipped.
        if (from != null) {
            sql.append(" AND created_at >= :from");
            params.put("from", from);
        }
        if (to != null) {
            sql.append(" AND created_at < :to");
            params.put("to", to);
        }
        if (cursorCreatedAt != null && cursorId != null) {
            // Same shape as the PostgreSQL listing, with the identifier compared as text: the
            // native UUID order of ClickHouse differs from the one PostgreSQL uses, while the text
            // order is identical, which is what lets a cursor continue on either store. The
            // leading bound is what lets the sorting key skip everything newer than the cursor.
            sql.append(
                    " AND created_at <= :cursorCreatedAt AND (created_at < :cursorCreatedAt OR"
                            + " (created_at = :cursorCreatedAt AND toString(id) < :cursorId))");
            params.put("cursorCreatedAt", cursorCreatedAt);
            params.put("cursorId", cursorId.toString());
        }
        sql.append(" ORDER BY created_at DESC, toString(id) DESC LIMIT :limit");
        params.put("limit", limit);
        return clickHouse.read(
                "admin_actions.list",
                client ->
                        client.sql(sql.toString())
                                .params(params)
                                .query(AdminActionAnalyticsRepositoryImpl::toSummary)
                                .list());
    }

    private static AdminActionSummaryResponse toSummary(ResultSet rows, int rowNumber)
            throws SQLException {
        return new AdminActionSummaryResponse(
                rows.getObject("id", UUID.class),
                rows.getObject("admin_id", UUID.class),
                AdminActionType.fromJson(rows.getString("action_type")),
                rows.getObject("target_user_id", UUID.class),
                rows.getString("target_entity_type"),
                rows.getObject("target_entity_id", UUID.class),
                rows.getObject("report_id", UUID.class),
                rows.getString("reason"),
                rows.getObject("created_at", OffsetDateTime.class)
                        .withOffsetSameInstant(ZoneOffset.UTC));
    }
}
