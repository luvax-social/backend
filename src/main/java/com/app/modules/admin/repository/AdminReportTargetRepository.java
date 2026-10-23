package com.app.modules.admin.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.app.common.text.Snippets;
import com.app.modules.admin.dto.response.AdminReportTargetResponse;
import com.app.modules.report.enums.ReportType;

/**
 * Reads a reported entity for moderation review, spanning soft-deleted rows.
 *
 * <p>Native SQL, and deliberately not a call into {@code PostService} or {@code CommentService}.
 * Those apply the ordinary visibility gate, which is the whole reason this exists: a moderator must
 * be able to review a private account's post, or a post by someone who has blocked them, when
 * somebody has reported it. Going through them would either return nothing or require weakening
 * {@code PostVisibilityServiceImpl} for every caller, and the feed, the profile listing, search
 * hydration and comment access all call that.
 *
 * <p>The security property is not in this class. It is that the only way to reach it is with a
 * report identifier, so a moderator can see exactly what somebody has reported and nothing else.
 * Nothing here may ever take a bare entity identifier.
 */
@Component
public class AdminReportTargetRepository {

    private final JdbcClient jdbcClient;

    public AdminReportTargetRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Loads the reported entity, whatever its visibility or soft-delete state.
     *
     * @param reportType the report's declared target type
     * @param entityId the report's target identifier
     * @return the entity rendered for review, or empty when nothing holds that id any more
     */
    public Optional<AdminReportTargetResponse> find(ReportType reportType, UUID entityId) {
        return switch (reportType) {
            case POST -> findPost(entityId);
            case COMMENT -> findComment(entityId);
            case USER -> findUser(entityId);
            case STORY -> findStory(entityId);
            case MESSAGE -> findMessage(entityId);
            case MESSAGE_REACTION -> findMessageReaction(entityId);
            case PLACE -> findPlace(entityId);
        };
    }

    private Optional<AdminReportTargetResponse> findPost(UUID entityId) {
        return jdbcClient
                .sql(
                        """
						SELECT p.id, p.user_id, u.username, p.status::text AS status,
							p.caption, p.deleted_at, p.created_at
						FROM posts p
						LEFT JOIN users u ON u.id = p.user_id
						WHERE p.id = :entityId
						""")
                .param("entityId", entityId)
                .query(
                        (rs, row) ->
                                new AdminReportTargetResponse(
                                        ReportType.POST,
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("user_id", UUID.class),
                                        rs.getString("username"),
                                        rs.getString("status"),
                                        rs.getString("caption"),
                                        findPostMediaUrls(entityId),
                                        rs.getObject("deleted_at") != null,
                                        rs.getObject("created_at", java.time.OffsetDateTime.class),
                                        null))
                .optional();
    }

    private List<String> findPostMediaUrls(UUID postId) {
        return jdbcClient
                .sql(
                        """
						SELECT m.cdn_url
						FROM post_media pm
						JOIN media_assets m ON m.id = pm.media_asset_id
						WHERE pm.post_id = :postId
						ORDER BY pm.position ASC
						""")
                .param("postId", postId)
                .query(String.class)
                .list();
    }

    private Optional<AdminReportTargetResponse> findComment(UUID entityId) {
        return jdbcClient
                .sql(
                        """
						SELECT c.id, c.user_id, u.username, c.content,
							c.deleted_at IS NOT NULL OR c.admin_removed_at IS NOT NULL AS deleted,
							c.created_at
						FROM comments c
						LEFT JOIN users u ON u.id = c.user_id
						WHERE c.id = :entityId
						""")
                .param("entityId", entityId)
                .query(
                        (rs, row) ->
                                new AdminReportTargetResponse(
                                        ReportType.COMMENT,
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("user_id", UUID.class),
                                        rs.getString("username"),
                                        null,
                                        rs.getString("content"),
                                        List.of(),
                                        rs.getBoolean("deleted"),
                                        rs.getObject("created_at", java.time.OffsetDateTime.class),
                                        null))
                .optional();
    }

    private Optional<AdminReportTargetResponse> findUser(UUID entityId) {
        return jdbcClient
                .sql(
                        """
						SELECT u.id, u.username, u.status::text AS status, u.bio, u.avatar_url,
							u.deleted_at, u.created_at
						FROM users u
						WHERE u.id = :entityId
						""")
                .param("entityId", entityId)
                .query(
                        (rs, row) -> {
                            String avatarUrl = rs.getString("avatar_url");
                            return new AdminReportTargetResponse(
                                    ReportType.USER,
                                    rs.getObject("id", UUID.class),
                                    rs.getObject("id", UUID.class),
                                    rs.getString("username"),
                                    rs.getString("status"),
                                    rs.getString("bio"),
                                    avatarUrl == null ? List.of() : List.of(avatarUrl),
                                    rs.getObject("deleted_at") != null,
                                    rs.getObject("created_at", java.time.OffsetDateTime.class),
                                    null);
                        })
                .optional();
    }

    private Optional<AdminReportTargetResponse> findStory(UUID entityId) {
        return jdbcClient
                .sql(
                        """
						SELECT s.id, s.user_id, u.username, s.caption, m.cdn_url,
							s.deleted_at IS NOT NULL OR s.admin_removed_at IS NOT NULL AS deleted,
							s.created_at
						FROM stories s
						LEFT JOIN users u ON u.id = s.user_id
						LEFT JOIN media_assets m ON m.id = s.media_asset_id
						WHERE s.id = :entityId
						""")
                .param("entityId", entityId)
                .query(
                        (rs, row) -> {
                            String cdnUrl = rs.getString("cdn_url");
                            return new AdminReportTargetResponse(
                                    ReportType.STORY,
                                    rs.getObject("id", UUID.class),
                                    rs.getObject("user_id", UUID.class),
                                    rs.getString("username"),
                                    null,
                                    rs.getString("caption"),
                                    cdnUrl == null ? List.of() : List.of(cdnUrl),
                                    rs.getBoolean("deleted"),
                                    rs.getObject("created_at", java.time.OffsetDateTime.class),
                                    null);
                        })
                .optional();
    }

    private Optional<AdminReportTargetResponse> findMessage(UUID entityId) {
        return jdbcClient
                .sql(
                        """
						SELECT msg.id, msg.sender_id, u.username, msg.content, m.cdn_url,
							msg.is_deleted OR msg.admin_removed_at IS NOT NULL AS deleted,
							msg.created_at
						FROM messages msg
						LEFT JOIN users u ON u.id = msg.sender_id
						LEFT JOIN media_assets m ON m.id = msg.media_asset_id
						WHERE msg.id = :entityId
						""")
                .param("entityId", entityId)
                .query(
                        (rs, row) -> {
                            String cdnUrl = rs.getString("cdn_url");
                            return new AdminReportTargetResponse(
                                    ReportType.MESSAGE,
                                    rs.getObject("id", UUID.class),
                                    rs.getObject("sender_id", UUID.class),
                                    rs.getString("username"),
                                    null,
                                    rs.getString("content"),
                                    cdnUrl == null ? List.of() : List.of(cdnUrl),
                                    rs.getBoolean("deleted"),
                                    rs.getObject("created_at", java.time.OffsetDateTime.class),
                                    null);
                        })
                .optional();
    }

    // The reactor is the owner, so the review shows who reacted; the snippet is of the message the
    // reaction sits on, which is the context a moderator needs to judge it. A message its sender
    // deleted has no content left to quote, and the reaction reads as removed alongside it.
    private Optional<AdminReportTargetResponse> findMessageReaction(UUID entityId) {
        return jdbcClient
                .sql(
                        """
						SELECT r.id, r.user_id, u.username, r.emoji, r.reacted_at,
							CASE WHEN msg.is_deleted THEN NULL ELSE msg.content END AS content,
							r.admin_removed_at IS NOT NULL OR msg.is_deleted
								OR msg.admin_removed_at IS NOT NULL AS removed
						FROM message_reactions r
						JOIN messages msg ON msg.id = r.message_id
						LEFT JOIN users u ON u.id = r.user_id
						WHERE r.id = :entityId
						""")
                .param("entityId", entityId)
                .query(
                        (rs, row) ->
                                new AdminReportTargetResponse(
                                        ReportType.MESSAGE_REACTION,
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("user_id", UUID.class),
                                        rs.getString("username"),
                                        null,
                                        Snippets.of(rs.getString("content")),
                                        List.of(),
                                        rs.getBoolean("removed"),
                                        rs.getObject("reacted_at", java.time.OffsetDateTime.class),
                                        rs.getString("emoji")))
                .optional();
    }

    // The reviewed text is the place name and its status is shown as the entity status. The creator
    // is the owner so the review can show who first wrote the name; a place whose creator is gone
    // reads with no owner, and one an administrator already removed reads as removed.
    private Optional<AdminReportTargetResponse> findPlace(UUID entityId) {
        return jdbcClient
                .sql(
                        """
						SELECT pl.id, pl.created_by, u.username, pl.name, pl.status::text AS status,
							pl.status <> 'active' AS removed, pl.created_at
						FROM places pl
						LEFT JOIN users u ON u.id = pl.created_by
						WHERE pl.id = :entityId
						""")
                .param("entityId", entityId)
                .query(
                        (rs, row) ->
                                new AdminReportTargetResponse(
                                        ReportType.PLACE,
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("created_by", UUID.class),
                                        rs.getString("username"),
                                        rs.getString("status"),
                                        rs.getString("name"),
                                        List.of(),
                                        rs.getBoolean("removed"),
                                        rs.getObject("created_at", java.time.OffsetDateTime.class),
                                        null))
                .optional();
    }
}
