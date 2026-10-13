package com.app.modules.social.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.social.entity.UserMute;
import com.app.modules.social.entity.UserMuteId;

@Repository
public interface UserMuteRepository extends JpaRepository<UserMute, UserMuteId> {

    /**
     * Stores the account's mute of another account, replacing the scopes of an earlier one.
     *
     * <p>One statement rather than read-then-write, so two requests racing on a pair with no row
     * yet cannot collide on the primary key. The update branch is skipped when the stored scopes
     * already equal the requested ones, which keeps {@code updated_at} (the list order) and the
     * returned count honest: the count is 1 only when the row was created or its scopes changed.
     *
     * @param userId the muting account
     * @param mutedUserId the muted account
     * @param mutePosts whether the muted account's posts are hidden
     * @param muteStories whether the muted account's stories are hidden
     * @return 1 when a row was inserted or changed, 0 when the same scopes were already stored
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value =
                    "INSERT INTO user_mutes (user_id, muted_user_id, mute_posts, mute_stories)"
                            + " VALUES (:userId, :mutedUserId, :mutePosts, :muteStories)"
                            + " ON CONFLICT (user_id, muted_user_id) DO UPDATE SET"
                            + " mute_posts = EXCLUDED.mute_posts,"
                            + " mute_stories = EXCLUDED.mute_stories"
                            + " WHERE (user_mutes.mute_posts, user_mutes.mute_stories)"
                            + " IS DISTINCT FROM (EXCLUDED.mute_posts, EXCLUDED.mute_stories)",
            nativeQuery = true)
    int upsert(
            @Param("userId") UUID userId,
            @Param("mutedUserId") UUID mutedUserId,
            @Param("mutePosts") boolean mutePosts,
            @Param("muteStories") boolean muteStories);

    /**
     * Removes the account's mute of another account.
     *
     * @param userId the muting account
     * @param mutedUserId the muted account
     * @return number of rows deleted (0 or 1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM UserMute m WHERE m.id.userId = :userId AND m.id.mutedUserId = :mutedUserId")
    int deleteByUserAndMuted(@Param("userId") UUID userId, @Param("mutedUserId") UUID mutedUserId);

    /**
     * Removes every mute between two accounts, in both directions.
     *
     * <p>A block supersedes a mute, so blocking either way clears both accounts' mutes of each
     * other.
     *
     * @param firstUserId one account of the pair
     * @param secondUserId the other account of the pair
     * @return number of rows deleted
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "DELETE FROM UserMute m WHERE (m.id.userId = :firstUserId AND m.id.mutedUserId ="
                    + " :secondUserId) OR (m.id.userId = :secondUserId AND m.id.mutedUserId ="
                    + " :firstUserId)")
    int deleteBetween(
            @Param("firstUserId") UUID firstUserId, @Param("secondUserId") UUID secondUserId);

    /**
     * First keyset page of the account's mutes, most recently set first.
     *
     * <p>Paired with {@link #findMutesBefore}; the no-cursor variant avoids binding an untyped null
     * cursor. The tiebreaker is {@code muted_user_id}, the unique key component within an account.
     * An account's mute list is small and bounded by the primary key's leading column, so no
     * dedicated sort index is kept.
     *
     * @param userId the muting account
     * @param pageable page size carrier
     * @return mutes ordered by the {@code (updated_at, muted_user_id)} tuple descending
     */
    @Query(
            value =
                    "SELECT * FROM user_mutes WHERE user_id = :userId"
                            + " ORDER BY updated_at DESC, muted_user_id DESC",
            nativeQuery = true)
    List<UserMute> findFirstMutes(@Param("userId") UUID userId, Pageable pageable);

    /**
     * Keyset page of the account's mutes strictly after the cursor tuple, most recently set first.
     *
     * <p>The {@code (updated_at, muted_user_id)} row-value comparison seeks to the cursor position
     * and never drops rows sharing a boundary {@code updated_at}.
     *
     * @param userId the muting account
     * @param cursorTime {@code updated_at} of the cursor row; never null
     * @param cursorMutedUserId muted-account id of the cursor row, breaking ties on equal time
     * @param pageable page size carrier
     * @return mutes ordered by the {@code (updated_at, muted_user_id)} tuple descending
     */
    @Query(
            value =
                    "SELECT * FROM user_mutes WHERE user_id = :userId"
                            + " AND (updated_at, muted_user_id) < (:cursorTime, :cursorMutedUserId)"
                            + " ORDER BY updated_at DESC, muted_user_id DESC",
            nativeQuery = true)
    List<UserMute> findMutesBefore(
            @Param("userId") UUID userId,
            @Param("cursorTime") OffsetDateTime cursorTime,
            @Param("cursorMutedUserId") UUID cursorMutedUserId,
            Pageable pageable);

    /**
     * The viewer's own mute and show-fewer choices about any of {@code userIds}, in one round trip.
     *
     * <p>Two index lookups on the primary keys appended in one statement, one per table. Only the
     * viewer's outgoing choices are read: nothing here lets an account learn it was muted or that
     * someone asked to see less of it.
     *
     * @param viewerId the requesting viewer
     * @param userIds candidate account ids on the current page
     * @return one row per mute and one per show-fewer request; an account absent from the result
     *     has neither
     */
    @Query(
            value =
                    "SELECT muted_user_id AS other_id, mute_posts, mute_stories,"
                            + " false AS show_fewer FROM user_mutes"
                            + " WHERE user_id = :viewerId AND muted_user_id IN (:userIds)"
                            + " UNION ALL "
                            + "SELECT author_id AS other_id, false AS mute_posts,"
                            + " false AS mute_stories, true AS show_fewer FROM account_show_fewer"
                            + " WHERE user_id = :viewerId AND author_id IN (:userIds)",
            nativeQuery = true)
    List<ViewerPreferenceProjection> findViewerPreferences(
            @Param("viewerId") UUID viewerId, @Param("userIds") Collection<UUID> userIds);
}
