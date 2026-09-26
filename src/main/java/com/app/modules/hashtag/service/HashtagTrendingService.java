package com.app.modules.hashtag.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;

import com.app.common.response.PageResponse;
import com.app.modules.hashtag.dto.response.HashtagTrendingResponse;
import com.app.modules.hashtag.dto.response.TrendingPreviewResponse;
import com.app.modules.hashtag.entity.HashtagTrending;

/** Computes and serves periodic hashtag trending snapshots. */
public interface HashtagTrendingService {

    /**
     * Aggregates hashtag usage within {@code [windowStart, windowEnd)} and writes ranked rows to
     * {@code hashtag_trending}.
     *
     * <p>Idempotent for a given {@code period_start}: re-running deletes the existing snapshot for
     * that period and re-inserts the current top ranks, so hashtags that dropped out of the top set
     * are removed rather than left as stale rows.
     *
     * @param windowStart the window lower bound, inclusive
     * @param windowEnd the window upper bound, exclusive
     * @return the persisted trending rows ordered by rank ascending
     */
    List<HashtagTrending> snapshotTrending(OffsetDateTime windowStart, OffsetDateTime windowEnd);

    /**
     * Returns the most recent trending snapshot page ordered by rank ascending.
     *
     * <p>Yields an empty page when no snapshot exists.
     *
     * @param pageable offset pagination request
     * @return the offset-paginated trending response
     */
    PageResponse<HashtagTrendingResponse> getTrending(Pageable pageable);

    /**
     * Pairs each trending entry with the cover image of its newest visible post.
     *
     * <p>Takes already-ranked entries rather than choosing a source itself, because the two sources
     * are this service and {@code PersonalisedTrendingService}, and that service already depends on
     * this one. Injecting it back would close a dependency cycle. The caller picks the source, the
     * same way the two existing trending endpoints already do.
     *
     * <p>An entry whose posts are all text, all removed, or all from private accounts keeps its
     * place with a null {@code previewUrl}. Dropping it would silently shorten the card instead of
     * showing the tag with its fallback.
     *
     * @param entries trending entries in the order they should be rendered
     * @return one preview per entry, in the same order
     */
    List<TrendingPreviewResponse> attachCovers(List<HashtagTrendingResponse> entries);

    /**
     * Describes hashtags that are not in the current trending snapshot, for a list that mixes
     * snapshot entries with entries drawn from elsewhere.
     *
     * <p>Carries the current snapshot's window boundaries on every entry, so a personalised list
     * reports one consistent window rather than leaving the period null on the entries that came
     * from affinity or adjacency. Rank is left at zero; the caller assigns position in its own
     * list.
     *
     * @param hashtagIds hashtags to describe; ids that do not resolve are omitted
     * @return one entry per resolvable hashtag, in no particular order
     */
    List<HashtagTrendingResponse> describeHashtags(List<UUID> hashtagIds);
}
