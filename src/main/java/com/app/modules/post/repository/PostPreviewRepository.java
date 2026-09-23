package com.app.modules.post.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Batched reads behind {@code PostPreviewService}: each post's state and first media item. */
@Repository
public class PostPreviewRepository {

    private final JdbcClient jdbc;

    public PostPreviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A post's lifecycle state and first media item.
     *
     * @param status the {@code post_status} label
     * @param moderated whether an administrator removed it ({@code status_before_moderation} set)
     */
    public record PostPreviewRow(
            UUID id,
            UUID ownerId,
            String status,
            boolean deleted,
            boolean moderated,
            String cdnUrl,
            String blurhash,
            String mediaType,
            Integer width,
            Integer height) {}

    /** One row per existing post, in one statement; the first media item by carousel position. */
    public List<PostPreviewRow> findPreviewRows(Collection<UUID> postIds) {
        if (postIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
                        "SELECT p.id, p.user_id, p.status::text AS status,"
                                + " p.deleted_at IS NOT NULL AS deleted,"
                                + " p.status_before_moderation IS NOT NULL AS moderated,"
                                + " m.cdn_url, m.blurhash, m.media_type::text AS media_type,"
                                + " m.width, m.height FROM posts p LEFT JOIN LATERAL"
                                + " (SELECT ma.cdn_url, ma.blurhash, ma.media_type, ma.width,"
                                + " ma.height FROM post_media pm"
                                + " JOIN media_assets ma ON ma.id = pm.media_asset_id"
                                + " WHERE pm.post_id = p.id ORDER BY pm.position LIMIT 1) m ON TRUE"
                                + " WHERE p.id IN (:ids)")
                .param("ids", postIds)
                .query(
                        (rs, rowNum) ->
                                new PostPreviewRow(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("user_id", UUID.class),
                                        rs.getString("status"),
                                        rs.getBoolean("deleted"),
                                        rs.getBoolean("moderated"),
                                        rs.getString("cdn_url"),
                                        rs.getString("blurhash"),
                                        rs.getString("media_type"),
                                        rs.getObject("width", Integer.class),
                                        rs.getObject("height", Integer.class)))
                .list();
    }
}
