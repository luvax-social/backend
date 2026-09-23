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

    /** A comment and its parent, with the tombstone state of each. */
    public record CommentPreviewRow(
            UUID id,
            UUID postId,
            UUID parentId,
            String content,
            boolean hidden,
            String parentContent,
            boolean parentHidden) {}

    public List<CommentPreviewRow> findPreviewRows(Collection<UUID> commentIds) {
        if (commentIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
                        "SELECT c.id, c.post_id, c.parent_id, c.content,"
                                + " (c.deleted_at IS NOT NULL OR c.admin_removed_at IS NOT NULL)"
                                + " AS hidden, p.content AS parent_content,"
                                + " (p.id IS NULL OR p.deleted_at IS NOT NULL"
                                + " OR p.admin_removed_at IS NOT NULL) AS parent_hidden"
                                + " FROM comments c LEFT JOIN comments p ON p.id = c.parent_id"
                                + " WHERE c.id IN (:ids)")
                .param("ids", commentIds)
                .query(
                        (rs, rowNum) ->
                                new CommentPreviewRow(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("post_id", UUID.class),
                                        rs.getObject("parent_id", UUID.class),
                                        rs.getString("content"),
                                        rs.getBoolean("hidden"),
                                        rs.getString("parent_content"),
                                        rs.getBoolean("parent_hidden")))
                .list();
    }
}
