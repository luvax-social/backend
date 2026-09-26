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
     * Recomputes affinity for every user with activity in the window, in one statement.
     *
     * <p>Every read of {@code user_events} here is bounded by {@code created_at} on both sides.
     * That table is partitioned by month with a {@code DEFAULT} catch-all, so a predicate on {@code
     * user_id} alone prunes nothing and touches every partition ever declared; the bound is what
     * makes this a scan of the two or three partitions the window covers instead of all
     * twenty-four.
     *
     * <p>Event types are weighted by intent rather than counted equally, and each contribution
     * decays exponentially toward the end of the window so recent activity outranks old activity. A
     * reversal cancels its own action exactly - {@code post_unsave} carries the negation of {@code
     * post_save}, {@code post_unlike} of {@code post_like} - so a user who liked and then unliked a
     * post contributes nothing from that pair. A hashtag whose contributions sum to zero or below
     * is dropped rather than stored at zero.
     *
     * <p>{@code hashtag_click} is unioned in separately because its {@code entity_id} is already a
     * hashtag: it needs no join through {@code post_hashtags} and is the most direct statement of
     * interest available.
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
     * harmless rather than duplicative, following {@code platform_stats}. This system assumes a
     * single application instance and has no distributed scheduler lock.
     *
     * @param windowStart inclusive lower bound on {@code user_events.created_at}
     * @param windowEnd exclusive upper bound, and the point decay is measured back from
     * @param halfLifeSeconds age at which a contribution is worth half its original weight
     * @param computedAt stamp written on every row this run touches; the sweep uses it to find rows
     *     this run did not refresh
     * @return number of affinity rows inserted or updated
     */
    @Modifying
    @Query(
            value =
                    """
					WITH signals AS (
						SELECT ue.user_id,
							ph.hashtag_id,
							w.weight * exp(ln(0.5)
								* EXTRACT(EPOCH FROM (:windowEnd - ue.created_at))
								/ :halfLifeSeconds) AS contribution
						FROM user_events ue
						JOIN (VALUES
									('post_save',    4.0),
									('post_share',   3.0),
									('post_comment', 3.0),
									('post_like',    2.0),
									('post_view',    0.25),
									('post_unsave', -4.0),
									('post_unlike', -2.0)
							) AS w(event_type, weight)
							ON w.event_type = ue.event_type::text
						JOIN post_hashtags ph ON ph.post_id = ue.entity_id
						WHERE ue.created_at >= :windowStart
						AND ue.created_at <  :windowEnd
						AND ue.entity_type = 'post'
						AND ue.entity_id IS NOT NULL
						UNION ALL
						SELECT ue.user_id,
							ue.entity_id AS hashtag_id,
							3.0 * exp(ln(0.5)
								* EXTRACT(EPOCH FROM (:windowEnd - ue.created_at))
								/ :halfLifeSeconds) AS contribution
						FROM user_events ue
						WHERE ue.created_at >= :windowStart
						AND ue.created_at <  :windowEnd
						AND ue.event_type = 'hashtag_click'
						AND ue.entity_type = 'hashtag'
						AND ue.entity_id IS NOT NULL
					),
					per_tag AS (
						SELECT s.user_id,
							s.hashtag_id,
							SUM(s.contribution) AS weight,
							COUNT(*)            AS event_count
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
    int recomputeWindow(
            @Param("windowStart") OffsetDateTime windowStart,
            @Param("windowEnd") OffsetDateTime windowEnd,
            @Param("halfLifeSeconds") long halfLifeSeconds,
            @Param("computedAt") OffsetDateTime computedAt);

    /**
     * Deletes rows the current run did not refresh.
     *
     * <p>A user who stopped engaging, or a hashtag that left circulation, must lose its rows rather
     * than keep a score frozen at whatever it held when the job last saw it. Run in the same
     * transaction as {@link #recomputeWindow}, so a reader sees either the whole previous state or
     * the whole new one and never a mixture.
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
