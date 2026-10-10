package com.app.modules.report.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.app.modules.report.enums.ReportType;

public class ReportTargetRepositoryImpl implements ReportTargetRepository {

    private final JdbcClient jdbcClient;

    public ReportTargetRepositoryImpl(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<UUID> findOwnerId(ReportType reportType, UUID entityId, UUID reporterId) {
        // Report targets are polymorphic across modules. Native SQL keeps this lookup in one
        // round trip without repository coupling, at the cost of compile-time column safety.
        String query =
                switch (reportType) {
                    case POST ->
                            "SELECT user_id FROM posts WHERE id = :entityId AND deleted_at IS NULL";
                    case COMMENT ->
                            "SELECT user_id FROM comments WHERE id = :entityId "
                                    + "AND deleted_at IS NULL AND admin_removed_at IS NULL "
                                    + "AND (moderation_status = 'approved' "
                                    + "OR user_id = :reporterId "
                                    + "OR EXISTS (SELECT 1 FROM posts p"
                                    + " WHERE p.id = comments.post_id"
                                    + " AND p.user_id = :reporterId))";
                    case USER -> "SELECT id FROM users WHERE id = :entityId AND deleted_at IS NULL";
                    case STORY ->
                            "SELECT user_id FROM stories WHERE id = :entityId "
                                    + "AND deleted_at IS NULL AND admin_removed_at IS NULL";
                    case MESSAGE ->
                            "SELECT sender_id FROM messages WHERE id = :entityId "
                                    + "AND is_deleted = FALSE AND deleted_at IS NULL "
                                    + "AND admin_removed_at IS NULL";
                };
        return jdbcClient
                .sql(query)
                .param("entityId", entityId)
                .param("reporterId", reporterId)
                .query(UUID.class)
                .optional();
    }
}
