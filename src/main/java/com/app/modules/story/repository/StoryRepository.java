package com.app.modules.story.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.story.entity.Story;

/**
 * Persistence access for {@link Story}.
 *
 * <p>JPQL queries inherit the {@code deleted_at IS NULL AND admin_removed_at IS NULL} filter from
 * the entity's {@code @SQLRestriction}; the expiry filter is an explicit predicate because expired
 * rows stay in the table until the cleanup job removes them.
 */
@Repository
public interface StoryRepository extends JpaRepository<Story, UUID> {

    /** Returns the story only if it is neither soft-deleted nor expired. */
    @Query("SELECT s FROM Story s WHERE s.id = :id AND s.expiresAt > :now")
    Optional<Story> findActiveById(UUID id, OffsetDateTime now);

    /** Active stories of one user in playback order (oldest first). */
    @Query(
            "SELECT s FROM Story s WHERE s.userId = :userId AND s.expiresAt > :now"
                    + " ORDER BY s.createdAt ASC")
    List<Story> findActiveByUser(UUID userId, OffsetDateTime now);

    /**
     * Authors with at least one active story the viewer has not already seen.
     *
     * <p>Candidates come from two tiers, and the union is the point. The accounts the viewer
     * follows come first, because a story from somebody they chose to follow is the one they most
     * want; suggested accounts follow, which is what keeps the surface populated for an account
     * that follows nobody. Ordering by tier then suggestion rank preserves both.
     *
     * <p>Every exclusion lives here rather than in the service because each one is a privacy rule,
     * and a filter the database applies cannot be forgotten by a later caller.
     *
     * <p>Privacy is the reason the private-account clause is a disjunction rather than a flat
     * exclusion. A private account's story is visible to an accepted follower and to nobody else,
     * so it is admitted only on that follow existing. Blocks in either direction remove the account
     * entirely, matching the stealth-block model. A dismissed suggestion is dropped, but a followed
     * account is not subject to dismissal or to the {@code suggestible} opt-out: both govern being
     * *offered* to strangers, not being read by somebody who already follows you.
     *
     * <p>The unseen predicate is what stops the feed offering the same story twice. An author whose
     * every active story the viewer has already opened drops out rather than being re-suggested.
     *
     * <p>This query is native, so it does not inherit the {@code @SQLRestriction} on {@link
     * com.app.modules.story.entity.Story} that hides a deleted or administratively removed row on
     * every JPQL read; it restates both. The live {@code expires_at} check is required for the same
     * reason expiry is checked everywhere else: an expired row outlives the story until the cleanup
     * job runs.
     *
     * @param viewerId the account reading
     * @param now the current instant, in UTC
     * @param limit maximum authors
     * @return author ids, followed accounts first then by suggestion rank
     */
    @Query(
            value =
                    "SELECT c.author_id FROM ("
                            + "   SELECT f.following_id AS author_id, 0 AS tier, 0 AS rank"
                            + "     FROM follows f"
                            + "    WHERE f.follower_id = :viewerId AND f.status = 'accepted'"
                            + "   UNION ALL"
                            + "   SELECT s.suggested_id, 1 AS tier, s.rank"
                            + "     FROM user_suggestions s"
                            + "    WHERE s.user_id = :viewerId"
                            + "      AND NOT EXISTS (SELECT 1 FROM suggestion_dismissals d"
                            + "        WHERE d.user_id = :viewerId AND d.dismissed_id = s.suggested_id)"
                            + "      AND EXISTS (SELECT 1 FROM user_settings st"
                            + "        WHERE st.user_id = s.suggested_id AND st.suggestible = TRUE)"
                            + " ) c"
                            + " JOIN users u ON u.id = c.author_id"
                            + " WHERE c.author_id <> :viewerId"
                            + " AND u.deleted_at IS NULL"
                            + " AND u.status = 'active'"
                            + " AND (u.is_private = FALSE OR EXISTS (SELECT 1 FROM follows pf"
                            + "   WHERE pf.follower_id = :viewerId AND pf.following_id = c.author_id"
                            + "   AND pf.status = 'accepted'))"
                            + " AND EXISTS (SELECT 1 FROM stories t"
                            + "   WHERE t.user_id = c.author_id AND t.expires_at > :now"
                            + "   AND t.deleted_at IS NULL AND t.admin_removed_at IS NULL"
                            + "   AND NOT EXISTS (SELECT 1 FROM story_views sv"
                            + "     WHERE sv.story_id = t.id AND sv.viewer_id = :viewerId))"
                            + " AND NOT EXISTS (SELECT 1 FROM blocks b"
                            + "   WHERE (b.blocker_id = :viewerId AND b.blocked_id = c.author_id)"
                            + "   OR (b.blocker_id = c.author_id AND b.blocked_id = :viewerId))"
                            + " GROUP BY c.author_id"
                            + " ORDER BY MIN(c.tier) ASC, MIN(c.rank) ASC, c.author_id"
                            + " LIMIT :limit",
            nativeQuery = true)
    List<UUID> findDiscoverableAuthors(
            @Param("viewerId") UUID viewerId,
            @Param("now") OffsetDateTime now,
            @Param("limit") int limit);

    /** Active stories of the given authors, grouped per author in playback order. */
    @Query(
            "SELECT s FROM Story s WHERE s.userId IN :authorIds AND s.expiresAt > :now"
                    + " ORDER BY s.userId, s.createdAt ASC")
    List<Story> findActiveByAuthors(List<UUID> authorIds, OffsetDateTime now);

    /**
     * Re-reads the trigger-maintained view counter, bypassing the possibly stale first-level cached
     * entity.
     */
    @Query("SELECT s.viewCount FROM Story s WHERE s.id = :storyId")
    int findViewCount(UUID storyId);

    /**
     * Re-reads the trigger-maintained like counter, bypassing the possibly stale first-level cached
     * entity.
     */
    @Query("SELECT s.likeCount FROM Story s WHERE s.id = :storyId")
    int findLikeCount(UUID storyId);

    /**
     * Hard-deletes rows that are BOTH hidden AND expired (DATA_RULES §3B); expired-but-live rows
     * are never cleanup targets.
     *
     * <p>Hidden means either tombstone is set. Administrative removal wrote {@code deleted_at}
     * before V95 and writes {@code admin_removed_at} after it, so matching on either is what keeps
     * the job purging the same rows it always did; matching on {@code deleted_at} alone would leave
     * every administratively removed expired story in the table for good.
     *
     * <p>Native because it must see hidden rows past the {@code @SQLRestriction} filter. {@code
     * story_views} rows follow via {@code ON DELETE CASCADE}.
     *
     * <p>Takes the cutoff as a parameter rather than reading the database clock. {@code expires_at}
     * is written by the service from the JVM clock and the three JPQL reads above compare it
     * against a JVM value, so this statement calling {@code NOW()} put one column in two clock
     * domains inside one repository: with the database clock ahead of the JVM, a story the reads
     * still treated as live was already purgeable here.
     *
     * @param now the cutoff, from the same clock that wrote {@code expires_at}
     */
    @Modifying
    @Query(
            value =
                    "DELETE FROM stories WHERE (deleted_at IS NOT NULL OR admin_removed_at IS NOT"
                            + " NULL) AND expires_at < :now",
            nativeQuery = true)
    int purgeSoftDeletedExpired(@Param("now") OffsetDateTime now);

    /**
     * Reads the owner of a story whatever its soft-delete state, for the moderation path.
     *
     * <p>Native because a moderator acts on a story that is already removed as readily as on a live
     * one, and the entity's {@code @SQLRestriction} would hide the removed case.
     *
     * @param storyId story identifier
     * @return the author's id, or empty when no row holds that id
     */
    @Query(value = "SELECT s.user_id FROM stories s WHERE s.id = :storyId", nativeQuery = true)
    Optional<UUID> findOwnerIdIncludingDeleted(UUID storyId);

    /**
     * Reports whether a story is administratively removed, spanning the {@code @SQLRestriction}.
     *
     * <p>Reads {@code admin_removed_at} and not {@code deleted_at}, so a story its owner deleted is
     * not mistaken for one a moderator removed.
     *
     * @param storyId story identifier
     * @return true when the row carries an {@code admin_removed_at}, or empty when no row holds
     *     that id
     */
    @Query(
            value = "SELECT s.admin_removed_at IS NOT NULL FROM stories s WHERE s.id = :storyId",
            nativeQuery = true)
    Optional<Boolean> isAdminRemoved(UUID storyId);

    /**
     * Sets or clears a story's soft-delete marker on behalf of the moderation path.
     *
     * <p>Native for the same reason as the two reads above. Expiry is deliberately untouched: a
     * restore returns the row to the live state it held and lets {@code expires_at} continue to
     * decide visibility, so a story that expired while removed does not come back into any feed.
     *
     * @param storyId story identifier
     * @param deletedAt soft-delete timestamp, or null when restoring
     * @return number of updated stories
     */
    @Modifying
    @Query(
            value = "UPDATE stories SET admin_removed_at = :adminRemovedAt WHERE id = :storyId",
            nativeQuery = true)
    int applyAdminModeration(UUID storyId, OffsetDateTime adminRemovedAt);
}
