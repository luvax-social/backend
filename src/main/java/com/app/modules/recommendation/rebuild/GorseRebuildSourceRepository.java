package com.app.modules.recommendation.rebuild;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * Reads the PostgreSQL rows a rebuild pushes into Gorse, in id order so a checkpoint is enough to
 * resume.
 *
 * <p>Native SQL throughout, because the {@code Post} entity's soft-delete restriction would hide
 * the deleted posts a rebuild must still hand to Gorse as hidden items.
 */
@Repository
@RequiredArgsConstructor
public class GorseRebuildSourceRepository {

    /** Sorts before every real identifier, so the first page needs no special query. */
    static final UUID BEFORE_FIRST = new UUID(0L, 0L);

    /**
     * One post as the recommender needs it.
     *
     * @param id the post
     * @param hidden true when the post is not published or is soft-deleted
     * @param createdAt creation time, the item's timestamp
     */
    public record PostRow(UUID id, boolean hidden, OffsetDateTime createdAt) {}

    private final JdbcTemplate jdbc;

    /** Returns up to {@code limit} live account ids after {@code after}, in id order. */
    public List<UUID> findUserIdsAfter(UUID after, int limit) {
        return jdbc.queryForList(
                "SELECT id FROM users WHERE deleted_at IS NULL AND id > ? ORDER BY id LIMIT ?",
                UUID.class,
                after == null ? BEFORE_FIRST : after,
                limit);
    }

    /** Returns up to {@code limit} posts after {@code after}, soft-deleted ones included. */
    public List<PostRow> findPostsAfter(UUID after, int limit) {
        return jdbc.query(
                "SELECT id, (status::text <> 'published' OR deleted_at IS NOT NULL) AS hidden,"
                        + " created_at FROM posts WHERE id > ? ORDER BY id LIMIT ?",
                (rs, row) ->
                        new PostRow(
                                rs.getObject("id", UUID.class),
                                rs.getBoolean("hidden"),
                                rs.getObject("created_at", OffsetDateTime.class)),
                after == null ? BEFORE_FIRST : after,
                limit);
    }

    /** Returns every post, for the final comparison against what Gorse holds. */
    public List<PostRow> findAllPosts() {
        List<PostRow> all = new ArrayList<>();
        UUID cursor = null;
        while (true) {
            List<PostRow> page = findPostsAfter(cursor, 5000);
            all.addAll(page);
            if (page.size() < 5000) {
                return all;
            }
            cursor = page.get(page.size() - 1).id();
        }
    }

    /** Returns which of the given posts exist, deleted ones included. */
    public Set<UUID> findExistingPostIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        UUID[] array = new HashSet<>(ids).toArray(UUID[]::new);
        // One array parameter rather than an IN list: a batch of feedback can name more posts than
        // PostgreSQL allows bind parameters in a single statement.
        List<UUID> found =
                jdbc.query(
                        connection -> {
                            var statement =
                                    connection.prepareStatement(
                                            "SELECT id FROM posts WHERE id = ANY (?)");
                            statement.setArray(1, connection.createArrayOf("uuid", array));
                            return statement;
                        },
                        (rs, row) -> rs.getObject("id", UUID.class));
        return new HashSet<>(found);
    }
}
