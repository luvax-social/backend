package com.app.modules.admin.repository;

import java.time.ZoneOffset;

import org.springframework.stereotype.Repository;

import com.app.common.analytics.ClickHouseOperations;

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
}
