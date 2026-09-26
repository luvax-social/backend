package com.app.modules.admin.repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.app.modules.admin.dto.response.AdminCommentSummaryResponse;
import com.app.modules.admin.dto.response.AdminPostSummaryResponse;

/**
 * Reads one account's content for an administrative investigation, spanning every state the
 * ordinary product hides.
 *
 * <p>Native SQL, and deliberately not a call into {@code PostService} or {@code CommentService}.
 * Both apply the ordinary visibility gate, which is exactly what has to be inverted here: an
 * investigation that can only see what the investigated account chose to make public is not an
 * investigation. The alternative considered and rejected was a moderator branch inside {@code
 * PostVisibilityServiceImpl}. The feed, the profile listing, search hydration and comment access
 * all call that, so a branch there would make moderators see private and blocked content throughout
 * their ordinary use of the product rather than only while investigating.
 *
 * <p>The bypass is contained by construction: this class is injected only from inside the
 * administrative module, and every endpoint that reaches it is behind the
 * moderator-or-administrator matcher. Nothing outside {@code modules/admin} may hold a reference to
 * it.
 *
 * <p>Every read here is keyset-paginated on {@code (created_at DESC, id DESC)}, the same ordering
 * and the same tiebreaker every other listing in the system uses, so a page boundary landing on a
 * timestamp tie neither drops nor repeats a row.
 */
@Component
public class AdminContentRepository {

    private final JdbcClient jdbcClient;

    public AdminContentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Loads one account's posts, newest first, whatever their status or soft-delete state.
     *
     * <p>Visibility inversion: no account-privacy check, no block check, and no {@code deleted_at
     * IS NULL} predicate. A draft, an archived post and a post moderation has already removed are
     * all returned, because what an account posted and what happened to it afterwards is the
     * subject of the investigation.
     *
     * @param userId the account under investigation
     * @param cursorCreatedAt creation time of the last row on the previous page, or null for the
     *     first page
     * @param cursorId identifier of the last row on the previous page, or null for the first page
     * @param limit maximum rows to return
     * @return the account's posts, newest first with the identifier as the tiebreaker
     */
    public List<AdminPostSummaryResponse> findPostsForUser(
            UUID userId, OffsetDateTime cursorCreatedAt, UUID cursorId, int limit) {
        List<AdminPostSummaryResponse> rows =
                jdbcClient
                        .sql(
                                """
						SELECT p.id, p.user_id, u.username, p.status::text AS status,
							p.caption, p.deleted_at, p.like_count, p.comment_count, p.created_at
						FROM posts p
						LEFT JOIN users u ON u.id = p.user_id
						WHERE p.user_id = :userId
							AND (:cursorCreatedAt::timestamptz IS NULL
								OR p.created_at < :cursorCreatedAt::timestamptz
								OR (p.created_at = :cursorCreatedAt::timestamptz
									AND p.id < :cursorId::uuid))
						ORDER BY p.created_at DESC, p.id DESC
						LIMIT :limit
						""")
                        .param("userId", userId)
                        .param("cursorCreatedAt", cursorCreatedAt)
                        .param("cursorId", cursorId)
                        .param("limit", limit)
                        .query(
                                (rs, row) ->
                                        new AdminPostSummaryResponse(
                                                rs.getObject("id", UUID.class),
                                                rs.getObject("user_id", UUID.class),
                                                rs.getString("username"),
                                                rs.getString("status"),
                                                rs.getString("caption"),
                                                rs.getObject("deleted_at") != null,
                                                rs.getInt("like_count"),
                                                rs.getInt("comment_count"),
                                                rs.getObject("created_at", OffsetDateTime.class),
                                                // Filled in below. Reading media here would issue
                                                // one
                                                // query per row.
                                                List.of()))
                        .list();
        return attachMedia(rows);
    }

    /**
     * Attaches each post's media to the page in one further query.
     *
     * <p>Two statements for the whole page, whatever its size. Reading media inside the row mapper
     * would issue one query per row, which is the shape that has produced an N+1 on this project
     * twice already: once when hashtags were added to the post response, and once on the feed
     * response.
     *
     * @param rows the page, carrying empty media lists
     * @return the same rows in the same order, with media attached
     */
    private List<AdminPostSummaryResponse> attachMedia(List<AdminPostSummaryResponse> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        List<UUID> postIds = rows.stream().map(AdminPostSummaryResponse::id).toList();
        Map<UUID, List<String>> byPost = new HashMap<>();
        jdbcClient
                .sql(
                        """
						SELECT pm.post_id, m.cdn_url
						FROM post_media pm
						JOIN media_assets m ON m.id = pm.media_asset_id
						WHERE pm.post_id IN (:postIds)
						ORDER BY pm.post_id, pm.position ASC
						""")
                .param("postIds", postIds)
                .query(
                        rs -> {
                            byPost.computeIfAbsent(
                                            rs.getObject("post_id", UUID.class),
                                            key -> new ArrayList<>())
                                    .add(rs.getString("cdn_url"));
                        });
        return rows.stream()
                .map(
                        row ->
                                new AdminPostSummaryResponse(
                                        row.id(),
                                        row.userId(),
                                        row.username(),
                                        row.status(),
                                        row.caption(),
                                        row.removed(),
                                        row.likeCount(),
                                        row.commentCount(),
                                        row.createdAt(),
                                        byPost.getOrDefault(row.id(), List.of())))
                .toList();
    }

    /**
     * Loads one account's comments, newest first, whatever their moderation or soft-delete state.
     *
     * <p>Visibility inversion: no check that the post the comment sits on is visible to the
     * reviewer, no block check, and no {@code deleted_at IS NULL} predicate. A comment a moderator
     * has already removed and a comment on a private account's post are both returned.
     *
     * @param userId the account under investigation
     * @param cursorCreatedAt creation time of the last row on the previous page, or null for the
     *     first page
     * @param cursorId identifier of the last row on the previous page, or null for the first page
     * @param limit maximum rows to return
     * @return the account's comments, newest first with the identifier as the tiebreaker
     */
    public List<AdminCommentSummaryResponse> findCommentsForUser(
            UUID userId, OffsetDateTime cursorCreatedAt, UUID cursorId, int limit) {
        return jdbcClient
                .sql(
                        """
						SELECT c.id, c.user_id, u.username, c.post_id, c.parent_id, c.content,
							c.moderation_status,
							c.deleted_at IS NOT NULL OR c.admin_removed_at IS NOT NULL AS deleted,
							c.like_count, c.created_at
						FROM comments c
						LEFT JOIN users u ON u.id = c.user_id
						WHERE c.user_id = :userId
							AND (:cursorCreatedAt::timestamptz IS NULL
								OR c.created_at < :cursorCreatedAt::timestamptz
								OR (c.created_at = :cursorCreatedAt::timestamptz
									AND c.id < :cursorId::uuid))
						ORDER BY c.created_at DESC, c.id DESC
						LIMIT :limit
						""")
                .param("userId", userId)
                .param("cursorCreatedAt", cursorCreatedAt)
                .param("cursorId", cursorId)
                .param("limit", limit)
                .query(
                        (rs, row) ->
                                new AdminCommentSummaryResponse(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("user_id", UUID.class),
                                        rs.getString("username"),
                                        rs.getObject("post_id", UUID.class),
                                        rs.getObject("parent_id", UUID.class),
                                        rs.getString("content"),
                                        rs.getString("moderation_status"),
                                        rs.getBoolean("deleted"),
                                        rs.getInt("like_count"),
                                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }
}
