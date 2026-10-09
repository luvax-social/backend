package com.app.modules.comment.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Batched reads behind {@code CommentPreviewService}.
 *
 * <p>Native SQL on purpose: the {@code Comment} entity's {@code @SQLRestriction} hides tombstoned
 * rows, and a preview must see them to report them unavailable rather than missing.
 */
@Repository
public class CommentPreviewRepository {

    private final JdbcClient jdbc;

    public CommentPreviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A comment and its parent, with each author's visibility state for the viewer. */
    public record CommentPreviewRow(
            UUID id,
            UUID postId,
            UUID parentId,
            String content,
            boolean hidden,
            UUID authorId,
            String moderationStatus,
            boolean blocked,
            String parentContent,
            boolean parentHidden,
            UUID parentAuthorId,
            String parentModerationStatus,
            boolean parentBlocked) {}

    /** Reads comment and parent previews with viewer-specific block state in one batch. */
    public List<CommentPreviewRow> findPreviewRows(UUID viewerId, Collection<UUID> commentIds) {
        if (commentIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
                        "SELECT c.id, c.post_id, c.parent_id, c.content, c.user_id AS author_id,"
                                + " c.moderation_status,"
                                + " (c.deleted_at IS NOT NULL OR c.admin_removed_at IS NOT NULL)"
                                + " AS hidden,"
                                + " EXISTS (SELECT 1 FROM blocks b WHERE"
                                + " (b.blocker_id = :viewer AND b.blocked_id = c.user_id)"
                                + " OR (b.blocker_id = c.user_id AND b.blocked_id = :viewer))"
                                + " AS blocked, p.content AS parent_content,"
                                + " p.user_id AS parent_author_id,"
                                + " p.moderation_status AS parent_moderation_status,"
                                + " (p.id IS NULL OR p.deleted_at IS NOT NULL"
                                + " OR p.admin_removed_at IS NOT NULL) AS parent_hidden,"
                                + " EXISTS (SELECT 1 FROM blocks b WHERE"
                                + " (b.blocker_id = :viewer AND b.blocked_id = p.user_id)"
                                + " OR (b.blocker_id = p.user_id AND b.blocked_id = :viewer))"
                                + " AS parent_blocked"
                                + " FROM comments c LEFT JOIN comments p ON p.id = c.parent_id"
                                + " WHERE c.id IN (:ids)")
                .param("viewer", viewerId)
                .param("ids", commentIds)
                .query(
                        (rs, rowNum) ->
                                new CommentPreviewRow(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("post_id", UUID.class),
                                        rs.getObject("parent_id", UUID.class),
                                        rs.getString("content"),
                                        rs.getBoolean("hidden"),
                                        rs.getObject("author_id", UUID.class),
                                        rs.getString("moderation_status"),
                                        rs.getBoolean("blocked"),
                                        rs.getString("parent_content"),
                                        rs.getBoolean("parent_hidden"),
                                        rs.getObject("parent_author_id", UUID.class),
                                        rs.getString("parent_moderation_status"),
                                        rs.getBoolean("parent_blocked")))
                .list();
    }
}
