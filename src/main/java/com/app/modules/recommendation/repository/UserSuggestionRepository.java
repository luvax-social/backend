package com.app.modules.recommendation.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.recommendation.entity.UserSuggestion;
import com.app.modules.recommendation.entity.UserSuggestionId;

@Repository
public interface UserSuggestionRepository extends JpaRepository<UserSuggestion, UserSuggestionId> {

    /**
     * One viewer's suggestions, with every exclusion rule applied at read time.
     *
     * <p>The filters are here and not only in the precompute job, and that is the whole point of
     * this query. The job runs on a twelve-hour cycle: with job-time-only filtering, a viewer who
     * follows somebody at 10am keeps being offered them until 8pm. Every rule that can change
     * between two runs is therefore re-evaluated on every read.
     *
     * <p>The exclusions, and why each is where it is:
     *
     * <ul>
     *   <li>Already followed covers {@code follows} at any status, so a pending request to a
     *       private account excludes the target exactly as an accepted follow does. Offering
     *       somebody you have already asked to follow is the most obviously broken case.
     *   <li>Blocked in either direction, matching the stealth block model every other list honours.
     *   <li>{@code status = 'active'} excludes banned, suspended and deactivated accounts in one
     *       predicate rather than three.
     *   <li>Dismissed is an index lookup on the {@code suggestion_dismissals} primary key, which is
     *       why that key leads on {@code user_id}.
     *   <li>{@code user_settings.suggestible} is the account's own opt-out. The join is inner
     *       rather than left because every account has exactly one settings row from creation; a
     *       left join would quietly admit an account whose invariant is broken.
     * </ul>
     *
     * <p>Private accounts are deliberately not excluded. Following one produces a pending request,
     * which is the normal contract, and the account's own control over being offered is the opt-out
     * above rather than a blanket exclusion.
     *
     * @param viewerId the account reading its own suggestions
     * @param limit maximum rows to return
     * @return suggested account ids in precomputed rank order
     */
    @Query(
            value =
                    "SELECT s.suggested_id FROM user_suggestions s"
                            + " JOIN users u ON u.id = s.suggested_id"
                            + " JOIN user_settings st ON st.user_id = s.suggested_id"
                            + " WHERE s.user_id = :viewerId"
                            + " AND s.suggested_id <> :viewerId"
                            + " AND u.deleted_at IS NULL"
                            + " AND u.status = 'active'"
                            + " AND st.suggestible = TRUE"
                            + " AND NOT EXISTS (SELECT 1 FROM follows f"
                            + " WHERE f.follower_id = :viewerId"
                            + " AND f.following_id = s.suggested_id)"
                            + " AND NOT EXISTS (SELECT 1 FROM blocks b"
                            + " WHERE (b.blocker_id = :viewerId AND b.blocked_id = s.suggested_id)"
                            + " OR (b.blocker_id = s.suggested_id AND b.blocked_id = :viewerId))"
                            + " AND NOT EXISTS (SELECT 1 FROM suggestion_dismissals d"
                            + " WHERE d.user_id = :viewerId AND d.dismissed_id = s.suggested_id)"
                            + " ORDER BY s.rank ASC"
                            + " LIMIT :limit",
            nativeQuery = true)
    List<UUID> findVisibleSuggestions(@Param("viewerId") UUID viewerId, @Param("limit") int limit);

    /**
     * Source one: triadic closure over the follow graph.
     *
     * <p>Accounts followed by the accounts the caller follows, ranked by how many of the caller's
     * followees reach them. The primary source, because a shared follow is the strongest and most
     * explainable signal available without leaving PostgreSQL.
     *
     * <p>Both sides of the self-join are served by {@code idx_follows_follower (follower_id,
     * status, created_at DESC)}: the first hop matches the caller, the second matches each
     * followee, and {@code status} is the second column of that index in both.
     *
     * <p>Already-followed candidates are excluded here as well as at read time. That is not
     * redundant: leaving them in would let accounts the caller already follows occupy the whole
     * precomputed list, which the read-time filter would then strip, leaving an empty rail.
     *
     * @param viewerId the account to build candidates for
     * @param limit maximum candidates
     * @return candidate ids ordered by shared-follower count descending
     */
    @Query(
            value =
                    "SELECT f2.following_id FROM follows f1"
                            + " JOIN follows f2 ON f2.follower_id = f1.following_id"
                            + " WHERE f1.follower_id = :viewerId AND f1.status = 'accepted'"
                            + " AND f2.status = 'accepted'"
                            + " AND f2.following_id <> :viewerId"
                            + " AND NOT EXISTS (SELECT 1 FROM follows fx"
                            + " WHERE fx.follower_id = :viewerId"
                            + " AND fx.following_id = f2.following_id)"
                            + " GROUP BY f2.following_id"
                            + " ORDER BY COUNT(*) DESC, f2.following_id ASC"
                            + " LIMIT :limit",
            nativeQuery = true)
    List<UUID> findTwoHopCandidates(@Param("viewerId") UUID viewerId, @Param("limit") int limit);

    /**
     * Source three: overlap between two accounts' hashtag interest profiles.
     *
     * <p>Consumes {@code user_hashtag_affinity}, which the P1 affinity job builds; nothing here
     * rederives it. Overlap is the sum of the per-hashtag minimum of the two scores, which is the
     * standard histogram-intersection measure: it rewards agreeing strongly on the same hashtags
     * and cannot be inflated by one side simply having a broader profile.
     *
     * <p>On the seeded dataset this source barely differentiates, because every seeded account
     * engages near-uniformly across roughly 134 hashtags. That is a property of the seed data, not
     * of the query, and it is why verification of this source needs a deliberately skewed account.
     *
     * <p>The viewer side is bounded to their strongest {@code profileDepth} hashtags rather than
     * their whole profile. Without that bound the shape is O(viewers x table size): a viewer
     * holding most of the hashtag corpus joins most of the table, which no index can make
     * selective, because the query genuinely reads the rows. Measured on the seeded data, one
     * viewer held 142 of the 147 distinct hashtags and the join produced 8,987 of 12,117 rows. The
     * bound is what removes that shape; the hashtag_id index makes what remains index-only.
     *
     * <p>Ordering the profile by score means the bound keeps the strongest hashtags first. It is a
     * deliberate ranking tradeoff of measured size, not a near-free truncation, and an earlier
     * version of this comment claimed the opposite. Measured on the seeded data for a viewer
     * holding 142 affinity rows: the kept top 32 carry 0.50613581 of the score mass and the
     * discarded tail of 110 carries 0.49386414, so the bound drops 49.4 percent of the viewer's
     * total affinity mass. The cut is not a natural break either - rank 32 scores 0.01107289 and
     * rank 33 scores 0.01106574, adjacent values differing by 0.06 percent.
     *
     * <p>The effect on the output, comparing the bounded and unbounded forms at 60 candidates each:
     * 7 of 60 accounts appear only in the bounded list and 7 only in the unbounded one, just 1 of
     * 60 holds the same position in both, and none of the top 10 positions is held by the same
     * account.
     *
     * <p>Both measurements are against the seeded distribution, which the paragraph above describes
     * as near-uniform across roughly 134 hashtags. A near-uniform profile is the worst case for a
     * flat cut, because no prefix dominates. The honest reading is therefore that the
     * near-zero-tail premise is refuted on the available data and unverified on production data. Do
     * not raise or lower this bound, or replace it with a score-mass threshold, without a
     * production score distribution in hand; the bound is kept at its current value because the
     * fan-out it prevents is real, not because the signal it drops is small.
     *
     * @param viewerId the account to build candidates for
     * @param profileDepth how many of the viewer's strongest hashtags to match on
     * @param limit maximum candidates
     * @return candidate ids ordered by profile overlap descending
     */
    @Query(
            value =
                    "WITH profile AS ("
                            + " SELECT hashtag_id, score FROM user_hashtag_affinity"
                            + " WHERE user_id = :viewerId"
                            + " ORDER BY score DESC, hashtag_id DESC"
                            + " LIMIT :profileDepth)"
                            + " SELECT a2.user_id FROM profile a1"
                            + " JOIN user_hashtag_affinity a2"
                            + " ON a2.hashtag_id = a1.hashtag_id AND a2.user_id <> :viewerId"
                            + " WHERE NOT EXISTS (SELECT 1 FROM follows fx"
                            + " WHERE fx.follower_id = :viewerId AND fx.following_id = a2.user_id)"
                            + " GROUP BY a2.user_id"
                            + " ORDER BY SUM(LEAST(a1.score, a2.score)) DESC, a2.user_id ASC"
                            + " LIMIT :limit",
            nativeQuery = true)
    List<UUID> findAffinityCandidates(
            @Param("viewerId") UUID viewerId,
            @Param("profileDepth") int profileDepth,
            @Param("limit") int limit);

    /**
     * The cold-start list: verified accounts, most-followed first.
     *
     * <p>Served for an account with no follows and no events. Drawn from the verification work
     * rather than from a most-followed placeholder, which is why verification was built first: the
     * cold-start path is built once, correctly, instead of being replaced later.
     *
     * <p>Served by {@code idx_users_verified_followers}, partial on {@code is_verified}.
     *
     * @param viewerId the account reading, excluded from its own list
     * @param limit maximum rows
     * @return verified account ids ordered by follower count descending
     */
    @Query(
            value =
                    "SELECT u.id FROM users u"
                            + " JOIN user_settings st ON st.user_id = u.id"
                            + " WHERE u.is_verified = TRUE"
                            + " AND u.deleted_at IS NULL"
                            + " AND u.status = 'active'"
                            + " AND st.suggestible = TRUE"
                            + " AND u.id <> :viewerId"
                            + " AND NOT EXISTS (SELECT 1 FROM follows f"
                            + " WHERE f.follower_id = :viewerId AND f.following_id = u.id)"
                            + " AND NOT EXISTS (SELECT 1 FROM blocks b"
                            + " WHERE (b.blocker_id = :viewerId AND b.blocked_id = u.id)"
                            + " OR (b.blocker_id = u.id AND b.blocked_id = :viewerId))"
                            + " AND NOT EXISTS (SELECT 1 FROM suggestion_dismissals d"
                            + " WHERE d.user_id = :viewerId AND d.dismissed_id = u.id)"
                            + " ORDER BY u.follower_count DESC, u.id ASC"
                            + " LIMIT :limit",
            nativeQuery = true)
    List<UUID> findVerifiedColdStart(@Param("viewerId") UUID viewerId, @Param("limit") int limit);

    /**
     * Writes one blended list, replacing whatever the previous run left.
     *
     * <p>{@code ON CONFLICT DO UPDATE} is what makes a double run idempotent. There is no
     * distributed scheduler lock in this application, and this job inherits that assumption exactly
     * as the affinity job and the statistics jobs do: two instances running it produce the same
     * rows rather than duplicates.
     *
     * @param viewerId the account the row is for
     * @param suggestedId the offered account
     * @param rank position in the blended list, 1-based
     * @param score the fused reciprocal-rank score
     * @param sources comma-separated contributing sources
     * @param computedAt stamp of the current run, supplied by the caller
     */
    @Modifying
    @Query(
            value =
                    "INSERT INTO user_suggestions"
                            + " (user_id, suggested_id, rank, score, sources, computed_at)"
                            + " VALUES (:viewerId, :suggestedId, :rank, :score, :sources,"
                            + " :computedAt)"
                            + " ON CONFLICT (user_id, suggested_id) DO UPDATE SET"
                            + " rank = EXCLUDED.rank, score = EXCLUDED.score,"
                            + " sources = EXCLUDED.sources, computed_at = EXCLUDED.computed_at",
            nativeQuery = true)
    void upsertSuggestion(
            @Param("viewerId") UUID viewerId,
            @Param("suggestedId") UUID suggestedId,
            @Param("rank") short rank,
            @Param("score") java.math.BigDecimal score,
            @Param("sources") String sources,
            @Param("computedAt") OffsetDateTime computedAt);

    /**
     * Removes the rows a run did not rewrite.
     *
     * <p>Without this a candidate that stops qualifying would stay in the table for ever, because
     * the upsert only ever writes rows that are still candidates. Bounded by {@code computed_at} so
     * it deletes exactly the previous generation for this viewer.
     *
     * <p>{@code keptFrom} must be the same value the run passed as {@code computedAt} on {@link
     * #upsertSuggestion}. Both sides are then in one clock domain, so a row this run wrote can
     * never satisfy {@code computed_at < :keptFrom} and be swept by the run that created it.
     *
     * @param viewerId the account whose stale rows should go
     * @param keptFrom the timestamp the current run stamped its own rows with
     * @return number of previous-generation rows removed
     */
    @Modifying
    @Query(
            value =
                    "DELETE FROM user_suggestions WHERE user_id = :viewerId"
                            + " AND computed_at < :keptFrom",
            nativeQuery = true)
    int deleteStaleFor(
            @Param("viewerId") UUID viewerId, @Param("keptFrom") OffsetDateTime keptFrom);

    /**
     * Rows for one viewer that carry the current run's stamp.
     *
     * <p>Counted after the sweep so the job reports what actually survived rather than what it
     * intended to write. A run that writes rows and then deletes them reports zero here, which is
     * the signal that was missing when the sweep last wiped its own generation.
     *
     * @param viewerId the account whose rows should be counted
     * @param computedAt stamp of the current run
     * @return number of rows this run left behind
     */
    @Query(
            value =
                    "SELECT count(*) FROM user_suggestions WHERE user_id = :viewerId"
                            + " AND computed_at = :computedAt",
            nativeQuery = true)
    int countFreshFor(
            @Param("viewerId") UUID viewerId, @Param("computedAt") OffsetDateTime computedAt);

    /**
     * The accounts a precompute run should build lists for.
     *
     * <p>Every live active account. The job is bounded by this rather than by activity, because an
     * account with no follows still needs a cold-start list and discovering that it has none is
     * what the job is for.
     *
     * @return candidate viewer ids
     */
    @Query(
            value =
                    "SELECT u.id FROM users u WHERE u.deleted_at IS NULL AND u.status = 'active'"
                            + " ORDER BY u.id",
            nativeQuery = true)
    List<UUID> findViewersToCompute();

    /**
     * The stored source labels for a set of candidates, for the reason line on the suggestion card.
     *
     * <p>Kept separate from {@link #findVisibleSuggestions} rather than folded into its projection.
     * That query is also the candidate source for story discovery, which has no use for the labels,
     * and widening it would make both call sites pay for one.
     *
     * <p>A cold-start candidate has no row here at all: it was never precomputed, so no reason for
     * it was ever recorded. That is not the same as a row whose labels are empty.
     *
     * @param viewerId the account reading its own suggestions
     * @param ids the candidates to label
     * @return rows of [suggested_id, sources]
     */
    @Query(
            value =
                    "SELECT s.suggested_id, s.sources FROM user_suggestions s"
                            + " WHERE s.user_id = :viewerId AND s.suggested_id IN (:ids)",
            nativeQuery = true)
    List<Object[]> findSourcesFor(@Param("viewerId") UUID viewerId, @Param("ids") List<UUID> ids);

    /**
     * The profile fields a suggestion card renders beyond the shared identity summary.
     *
     * <p>Banner and follower count travel together in one query rather than two, because the card
     * needs both for the same set of accounts and a second round trip would buy nothing.
     *
     * <p>An account with no banner yields a null column rather than a missing row, so the caller
     * distinguishes "has no banner" from "is not in this set". {@code follower_count} is the
     * trigger-maintained denormalised counter on {@code users} and is never null.
     *
     * @param ids the accounts to read
     * @return rows of [id, banner_url, follower_count]
     */
    @Query(
            value = "SELECT u.id, u.banner_url, u.follower_count FROM users u WHERE u.id IN (:ids)",
            nativeQuery = true)
    List<Object[]> findProfileCardFields(@Param("ids") List<UUID> ids);
}
