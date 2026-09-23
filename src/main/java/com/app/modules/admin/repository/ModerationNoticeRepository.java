package com.app.modules.admin.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Batched reads behind {@code ModerationNoticeService}: each audit row with the text of the content
 * it acted on.
 *
 * <p>Reads the content tables directly, like {@link AdminReportTargetRepository} does for the
 * moderation views, because the affected content is by definition tombstoned and invisible to its
 * owning module's ordinary read paths. Moderation removals preserve the text ({@code
 * admin_removed_at} and {@code status_before_moderation} never clear it); an owner deletion of a
 * message clears it, which reads as no snippet.
 */
@Repository
public class ModerationNoticeRepository {

    private final JdbcClient jdbc;

    public ModerationNoticeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** An audit row and the content it acted on. */
    public record NoticeRow(
            UUID id,
            String actionType,
            String reason,
            UUID targetUserId,
            String targetEntityType,
            String affectedText,
            OffsetDateTime affectedAt) {}

    public List<NoticeRow> findNoticeRows(Collection<UUID> adminActionIds) {
        if (adminActionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
                        "SELECT a.id, a.action_type::text AS action_type, a.reason,"
                                + " a.target_user_id, a.target_entity_type,"
                                + " CASE a.target_entity_type"
                                + " WHEN 'post' THEN (SELECT p.caption FROM posts p"
                                + " WHERE p.id = a.target_entity_id)"
                                + " WHEN 'comment' THEN (SELECT c.content FROM comments c"
                                + " WHERE c.id = a.target_entity_id)"
                                + " WHEN 'story' THEN (SELECT s.caption FROM stories s"
                                + " WHERE s.id = a.target_entity_id)"
                                + " WHEN 'message' THEN (SELECT m.content FROM messages m"
                                + " WHERE m.id = a.target_entity_id) END AS affected_text,"
                                + " CASE a.target_entity_type"
                                + " WHEN 'post' THEN (SELECT p.created_at FROM posts p"
                                + " WHERE p.id = a.target_entity_id)"
                                + " WHEN 'comment' THEN (SELECT c.created_at FROM comments c"
                                + " WHERE c.id = a.target_entity_id)"
                                + " WHEN 'story' THEN (SELECT s.created_at FROM stories s"
                                + " WHERE s.id = a.target_entity_id)"
                                + " WHEN 'message' THEN (SELECT m.created_at FROM messages m"
                                + " WHERE m.id = a.target_entity_id) END AS affected_at"
                                + " FROM admin_actions a WHERE a.id IN (:ids)")
                .param("ids", adminActionIds)
                .query(
                        (rs, rowNum) ->
                                new NoticeRow(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("action_type"),
                                        rs.getString("reason"),
                                        rs.getObject("target_user_id", UUID.class),
                                        rs.getString("target_entity_type"),
                                        rs.getString("affected_text"),
                                        rs.getObject("affected_at", OffsetDateTime.class)))
                .list();
    }
}
