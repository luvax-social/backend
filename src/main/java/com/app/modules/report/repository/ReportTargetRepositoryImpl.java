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
                    // Only a participant who still has the conversation may report a reaction, and
                    // never one by an account that blocks them or that they block: that reaction is
                    // hidden from them, so answering as if it did not exist keeps the block silent.
                    case MESSAGE_REACTION ->
                            "SELECT r.user_id FROM message_reactions r "
                                    + "JOIN messages m ON m.id = r.message_id "
                                    + "WHERE r.id = :entityId "
                                    + "AND r.admin_removed_at IS NULL "
                                    + "AND m.is_deleted = FALSE AND m.deleted_at IS NULL "
                                    + "AND m.admin_removed_at IS NULL "
                                    + "AND EXISTS (SELECT 1 FROM conversation_participants cp "
                                    + "WHERE cp.conversation_id = m.conversation_id "
                                    + "AND cp.user_id = :reporterId AND cp.left_at IS NULL) "
                                    + "AND NOT EXISTS (SELECT 1 FROM blocks b "
                                    + "WHERE (b.blocker_id = :reporterId "
                                    + "AND b.blocked_id = r.user_id) "
                                    + "OR (b.blocker_id = r.user_id "
                                    + "AND b.blocked_id = :reporterId))";
                };
        return jdbcClient
                .sql(query)
                .param("entityId", entityId)
                .param("reporterId", reporterId)
                .query(UUID.class)
                .optional();
    }
}
