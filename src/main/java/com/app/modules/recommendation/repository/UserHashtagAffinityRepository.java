package com.app.modules.recommendation.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.recommendation.entity.UserHashtagAffinity;
import com.app.modules.recommendation.entity.UserHashtagAffinityId;

/** Reads and batch recomputation for the {@code user_hashtag_affinity} derived read model. */
@Repository
public interface UserHashtagAffinityRepository
        extends JpaRepository<UserHashtagAffinity, UserHashtagAffinityId> {

    /**
     * Recomputes affinity for every user with activity in the window, in one statement, from the
     * signals staged in {@code affinity_signal_stage}.
     *
     * <p>The behavioural events live in ClickHouse, so the per-user contributions are read there
     * and staged in a temporary table of the current transaction; this statement only joins them to
     * the hashtags of each post. Event types were weighted by intent and decayed exponentially
     * toward the end of the window on the ClickHouse side, and a reversal cancels its own action
     * exactly - {@code post_unsave} carries the negation of {@code post_save}, {@code post_unlike}
     * of {@code post_like} - so a user who liked and then unliked a post contributes nothing from
     * that pair. A hashtag whose contributions sum to zero or below is dropped rather than stored
     * at zero.
     *
     * <p>Summing per (user, post) first and joining afterwards gives the same {@code weight} as
     * weighting each (event, hashtag) pair, and {@code SUM(event_count)} gives the same count of
     * (event, hashtag) pairs a row-per-event join would.
     *
     * <p>A staged {@code hashtag} signal is a hashtag click: its target is already a hashtag, so it
     * needs no join through {@code post_hashtags} and is the most direct statement of interest
     * available.
     *
     * <p>Banned and deleted hashtags are excluded here, at write time, not merely filtered on read.
     * A suggestion surface that offered one would produce a caption the post write path then
     * refuses with {@code POST_BANNED_HASHTAG}.
     *
     * <p>Scores are normalised into each user's own share of their decayed total, which is what
     * makes a user with three thousand events comparable with one with thirty; without it the
     * ranking would measure activity volume rather than interest.
     *
     * <p>{@code ON CONFLICT DO UPDATE} against the composite key is what keeps a double run
     * harmless rather than duplicative. This system assumes a single application instance and has
     * no distributed scheduler lock.
     *
     * @param windowStart inclusive lower bound of the window the staged signals were read for
     * @param windowEnd exclusive upper bound of that window
     * @param computedAt stamp written on every row this run touches; the sweep uses it to find rows
     *     this run did not refresh
     * @return number of affinity rows inserted or updated
     */
    @Modifying
    @Query(
            value =
                    """
					WITH signals AS (
						SELECT s.user_id, ph.hashtag_id, s.contribution, s.event_count
						FROM affinity_signal_stage s
						JOIN post_hashtags ph ON ph.post_id = s.target_id
						WHERE s.target_kind = 'post'
						UNION ALL
						SELECT s.user_id, s.target_id, s.contribution, s.event_count
						FROM affinity_signal_stage s
						WHERE s.target_kind = 'hashtag'
					),
					per_tag AS (
						SELECT s.user_id,
							s.hashtag_id,
							SUM(s.contribution) AS weight,
							SUM(s.event_count)  AS event_count
						FROM signals s
						JOIN hashtags h ON h.id = s.hashtag_id AND h.status = 'active'
						GROUP BY s.user_id, s.hashtag_id
						HAVING SUM(s.contribution) > 0
					),
					totals AS (
						SELECT user_id, SUM(weight) AS total FROM per_tag GROUP BY user_id
					)
					INSERT INTO user_hashtag_affinity AS a
						(user_id, hashtag_id, score, weight, event_count,
						window_start, window_end, computed_at)
					SELECT p.user_id,
						p.hashtag_id,
						ROUND((p.weight / t.total)::numeric, 8),
						ROUND(p.weight::numeric, 6),
						p.event_count,
						:windowStart,
						:windowEnd,
						:computedAt
					FROM per_tag p
					JOIN totals  t ON t.user_id = p.user_id
					WHERE t.total > 0
					ON CONFLICT (user_id, hashtag_id) DO UPDATE
					SET score        = EXCLUDED.score,
						weight       = EXCLUDED.weight,
						event_count  = EXCLUDED.event_count,
						window_start = EXCLUDED.window_start,
						window_end   = EXCLUDED.window_end,
						computed_at  = EXCLUDED.computed_at
					""",
            nativeQuery = true)
    int recomputeFromStage(
            @Param("windowStart") OffsetDateTime windowStart,
            @Param("windowEnd") OffsetDateTime windowEnd,
            @Param("computedAt") OffsetDateTime computedAt);

    /**
     * Deletes rows the current run did not refresh.
     *
     * <p>A user who stopped engaging, or a hashtag that left circulation, must lose its rows rather
     * than keep a score frozen at whatever it held when the job last saw it. Run in the same
     * transaction as {@link #recomputeFromStage}, so a reader sees either the whole previous state
     * or the whole new one and never a mixture.
     *
     * @param computedAt stamp of the current run; rows older than this were not refreshed
     * @return number of stale rows removed
     */
    @Modifying
    @Query(
            value = "DELETE FROM user_hashtag_affinity WHERE computed_at < :computedAt",
            nativeQuery = true)
    int deleteStale(@Param("computedAt") OffsetDateTime computedAt);

    /**
     * Highest-scoring hashtags for one user, strongest first.
     *
     * <p>Ordered by {@code (score DESC, hashtag_id DESC)} to match {@code
     * idx_user_hashtag_affinity_user_score}; the trailing id makes the order total, so a page
     * boundary falling inside a group of equal scores cannot drop or repeat a row.
     *
     * @param userId user whose affinities are read
     * @param limit maximum rows returned
     * @return affinity rows for the user, strongest first; empty when the user has no activity in
     *     the last computed window
     */
    @Query(
            value =
                    "SELECT * FROM user_hashtag_affinity WHERE user_id = :userId"
                            + " ORDER BY score DESC, hashtag_id DESC LIMIT :limit",
            nativeQuery = true)
    List<UserHashtagAffinity> findTopForUser(
            @Param("userId") UUID userId, @Param("limit") int limit);

    /**
     * Counts the rows held for one user, used to decide whether a personalised surface has data.
     */
    @Query(
            value = "SELECT count(*) FROM user_hashtag_affinity WHERE user_id = :userId",
            nativeQuery = true)
    long countForUser(@Param("userId") UUID userId);

    /**
     * Hashtags adjacent to the user's interests but not among them.
     *
     * <p>Candidates co-occur on posts with hashtags the user already engages with, while the user
     * holds no affinity row for them. This is the novelty source for personalised trending: a list
     * built only from what a user already reads is a filter bubble and is less useful than the
     * platform list it replaced.
     *
     * <p>Adjacency is measured by co-occurrence rather than by raw popularity on purpose.
     * Popularity would surface the same handful of platform-wide hashtags to every user, which the
     * platform tab already shows; co-occurrence surfaces something the user has a reason to care
     * about.
     *
     * <p>Banned and deleted hashtags are excluded, so a suggestion can never name a term the post
     * write path would refuse.
     *
     * @param userId user whose adjacency is computed
     * @param limit maximum candidates returned
     * @return hashtag ids ordered by co-occurrence strength, strongest first
     */
    @Query(
            value =
                    """
					SELECT ph2.hashtag_id
					FROM user_hashtag_affinity a
					JOIN post_hashtags ph1 ON ph1.hashtag_id = a.hashtag_id
					JOIN post_hashtags ph2 ON ph2.post_id = ph1.post_id
											AND ph2.hashtag_id <> a.hashtag_id
					JOIN hashtags h ON h.id = ph2.hashtag_id AND h.status = 'active'
					WHERE a.user_id = :userId
					AND NOT EXISTS (
							SELECT 1 FROM user_hashtag_affinity x
							WHERE x.user_id = :userId AND x.hashtag_id = ph2.hashtag_id)
					GROUP BY ph2.hashtag_id
					ORDER BY count(*) DESC, ph2.hashtag_id DESC
					LIMIT :limit
					""",
            nativeQuery = true)
    List<UUID> findAdjacentHashtagIds(@Param("userId") UUID userId, @Param("limit") int limit);
}
