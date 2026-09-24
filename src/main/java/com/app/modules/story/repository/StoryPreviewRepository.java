package com.app.modules.story.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Batched reads behind {@code StoryPreviewService}.
 *
 * <p>Native SQL so a tombstoned or expired story is still read and reported unavailable, and so
 * expiry is judged by the database clock.
 */
@Repository
public class StoryPreviewRepository {

    private final JdbcClient jdbc;

    public StoryPreviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A story's owner, lifecycle state and media. */
    public record StoryPreviewRow(
            UUID id,
            UUID ownerId,
            boolean hidden,
            boolean expired,
            OffsetDateTime expiresAt,
            String cdnUrl,
            String blurhash,
            String mediaType,
            Integer width,
            Integer height) {}

    public List<StoryPreviewRow> findPreviewRows(Collection<UUID> storyIds) {
        if (storyIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
                        "SELECT s.id, s.user_id,"
                                + " (s.deleted_at IS NOT NULL OR s.admin_removed_at IS NOT NULL)"
                                + " AS hidden, s.expires_at <= now() AS expired, s.expires_at,"
                                + " m.cdn_url, m.blurhash, m.media_type::text AS media_type,"
                                + " m.width, m.height FROM stories s"
                                + " LEFT JOIN media_assets m ON m.id = s.media_asset_id"
                                + " WHERE s.id IN (:ids)")
                .param("ids", storyIds)
                .query(
                        (rs, rowNum) ->
                                new StoryPreviewRow(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("user_id", UUID.class),
                                        rs.getBoolean("hidden"),
                                        rs.getBoolean("expired"),
                                        rs.getObject("expires_at", OffsetDateTime.class),
                                        rs.getString("cdn_url"),
                                        rs.getString("blurhash"),
                                        rs.getString("media_type"),
                                        rs.getObject("width", Integer.class),
                                        rs.getObject("height", Integer.class)))
                .list();
    }
}
