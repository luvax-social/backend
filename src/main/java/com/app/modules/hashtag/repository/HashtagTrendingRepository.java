package com.app.modules.hashtag.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.hashtag.entity.HashtagTrending;
import com.app.modules.hashtag.entity.HashtagTrendingId;

@Repository
public interface HashtagTrendingRepository
        extends JpaRepository<HashtagTrending, HashtagTrendingId> {

    /**
     * Returns trending rows whose {@code period_start} equals the given instant, ordered by rank
     * ascending.
     *
     * @param periodStart the snapshot period start to match
     * @param pageable pagination and result-size bound
     * @return trending rows for the period ordered by rank ascending
     */
    List<HashtagTrending> findByIdPeriodStartOrderByRankAsc(
            OffsetDateTime periodStart, Pageable pageable);

    /** Counts trending rows for a given snapshot period. */
    long countByIdPeriodStart(OffsetDateTime periodStart);

    /**
     * Removes every trending snapshot row for one hashtag, across all periods.
     *
     * <p>Called in the same transaction as a status change that takes a hashtag out of discovery. A
     * hashtag is usually banned in reaction to something happening right now, which is exactly when
     * it is at the top of the trending list, so waiting for the next job cycle would leave it there
     * for the worst possible hour.
     *
     * @param hashtagId the hashtag whose trending rows are removed
     * @return the number of rows removed
     */
    @Modifying
    @Query("DELETE FROM HashtagTrending t WHERE t.id.hashtagId = :hashtagId")
    int deleteAllByHashtagId(@Param("hashtagId") UUID hashtagId);

    /**
     * One page of a trending snapshot, pinned hashtags first and then by rank.
     *
     * <p>A platform-wide pin has to lead the whole list, not the page it happens to fall on, so the
     * ordering is applied in the database rather than to an already-paged result.
     *
     * @param periodStart the snapshot window to read
     * @param limit page size
     * @param offset rows to skip
     * @return trending rows for the window, pinned first then by ascending rank
     */
    @Query(
            value =
                    "SELECT t.* FROM hashtag_trending t"
                            + " JOIN hashtags h ON h.id = t.hashtag_id"
                            + " WHERE t.period_start = :periodStart"
                            + " ORDER BY (h.pinned_at IS NULL), t.rank ASC, t.hashtag_id DESC"
                            + " LIMIT :limit OFFSET :offset",
            nativeQuery = true)
    List<HashtagTrending> findByPeriodPinnedFirst(
            @Param("periodStart") OffsetDateTime periodStart,
            @Param("limit") int limit,
            @Param("offset") int offset);

    /**
     * One cover image per hashtag: the newest published post carrying it that has media.
     *
     * <p>A lateral join rather than a group-by, so each hashtag costs one index seek into {@code
     * post_hashtags} and stops at the first row. A hashtag whose posts are all text, all removed or
     * all from private accounts yields a null url and keeps its row, because dropping the tag would
     * silently shorten the card instead of showing the tag with its fallback.
     *
     * <p>The author predicates are load-bearing rather than defensive. This thumbnail is rendered
     * with no per-post visibility pass behind it, so whatever this query returns is shown to
     * everyone; a private account's photograph must never be reachable through it. {@code status =
     * 'published'} covers moderation removal too, because a removal moves the old status into
     * {@code status_before_moderation} and sets {@code status} to {@code removed}.
     *
     * @param ids the hashtags to find covers for
     * @return rows of [hashtag_id, cover_url]; cover_url may be null
     */
    @Query(
            value =
                    "SELECT h.id, cover.cdn_url FROM hashtags h"
                            + " LEFT JOIN LATERAL ("
                            + "   SELECT ma.cdn_url FROM post_hashtags ph"
                            + "   JOIN posts p ON p.id = ph.post_id"
                            + "   JOIN users au ON au.id = p.user_id"
                            + "   JOIN post_media pm ON pm.post_id = p.id"
                            + "   JOIN media_assets ma ON ma.id = pm.media_asset_id"
                            + "   WHERE ph.hashtag_id = h.id"
                            + "     AND p.status = 'published'"
                            + "     AND p.deleted_at IS NULL"
                            + "     AND au.is_private = FALSE"
                            + "     AND au.deleted_at IS NULL"
                            + "     AND au.status = 'active'"
                            + "   ORDER BY p.created_at DESC, pm.\"position\" ASC"
                            + "   LIMIT 1"
                            + " ) cover ON TRUE"
                            + " WHERE h.id IN (:ids)",
            nativeQuery = true)
    List<Object[]> findCoverUrls(@Param("ids") List<UUID> ids);
}
