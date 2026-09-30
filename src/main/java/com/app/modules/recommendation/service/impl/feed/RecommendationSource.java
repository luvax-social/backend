package com.app.modules.recommendation.service.impl.feed;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.app.common.analytics.ClickHouseException;
import com.app.modules.recommendation.client.GorseClient;
import com.app.modules.recommendation.client.dto.GorseScore;
import com.app.modules.recommendation.config.RecommendationProperties;
import com.app.modules.recommendation.enums.UserEventType;
import com.app.modules.recommendation.repository.UserEventAnalyticsRepository;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;

/**
 * Candidate source stage of the recommendation feed pipeline.
 *
 * <p>Serves personalized candidates from Gorse and degrades to the non-personalized popularity
 * ranking when Gorse recommend is unavailable or the circuit is open. When Gorse's personalized
 * list itself runs short of the requested count - the exhaustion case, now that replacement is off
 * - this backfills from the time-decayed trending recommender in two ordered passes: trending items
 * the viewer has not read, then trending items the viewer has read. A read item therefore only ever
 * appears once every unread option is gone, and always at the tail of the batch.
 */
@Slf4j
@Component
public class RecommendationSource {

    public static final char SOURCE_GORSE = 'g';
    public static final char SOURCE_POPULAR = 'p';

    private final GorseClient gorseClient;
    private final UserEventAnalyticsRepository userEventAnalyticsRepository;
    private final RecommendationProperties recommendationProperties;

    public RecommendationSource(
            GorseClient gorseClient,
            UserEventAnalyticsRepository userEventAnalyticsRepository,
            RecommendationProperties recommendationProperties) {
        this.gorseClient = gorseClient;
        this.userEventAnalyticsRepository = userEventAnalyticsRepository;
        this.recommendationProperties = recommendationProperties;
    }

    /**
     * A batch of scored candidates tagged with the source that actually produced them.
     *
     * <p>{@code scores} is the primary list's results followed by any topup candidates appended
     * after it. {@code primaryCount} is how many leading entries came from the primary list (Gorse
     * personalized, or the popularity list when {@code source} is {@link #SOURCE_POPULAR}); the
     * remainder came from the trending topup. {@code trendingChunkFetched} is the size of the raw
     * trending chunk the topup read this call, or zero when topup was not attempted or failed.
     *
     * <p>Deliberately does not report a ready-to-use next offset: the Filter stage downstream may
     * stop inspecting {@code scores} before reaching the end of it once a page fills, and only the
     * caller knows how many entries were actually inspected. The caller derives the real offset
     * advancement from that count plus these two fields - see {@code
     * RecommendationFeedServiceImpl}.
     */
    public record SourceBatch(
            char source, List<GorseScore> scores, int primaryCount, int trendingChunkFetched) {}

    /**
     * Fetches the next candidate batch from the requested source, backfilling from trending when
     * the personalized source runs short.
     *
     * @param viewerId user the feed is for
     * @param source {@link #SOURCE_GORSE} or {@link #SOURCE_POPULAR}
     * @param n maximum candidates to fetch; also the topup target, since the caller has already
     *     over-fetched this to account for downstream filtering
     * @param gorseOffset zero-based offset into Gorse's ranked list (or the popularity list, when
     *     {@code source} is {@link #SOURCE_POPULAR})
     * @param trendingOffset zero-based offset into the trending list the topup reads from; ignored
     *     when {@code source} is {@link #SOURCE_POPULAR}, since that path never tops up
     * @return candidates from the requested source, backfilled from trending when short; empty
     *     batch when no source can serve
     */
    @CircuitBreaker(name = "gorse", fallbackMethod = "popularFallback")
    public SourceBatch fetch(
            UUID viewerId, char source, int n, int gorseOffset, int trendingOffset) {
        if (source == SOURCE_POPULAR) {
            List<GorseScore> popular = gorseClient.popular(n, gorseOffset);
            return new SourceBatch(SOURCE_POPULAR, popular, popular.size(), 0);
        }
        List<GorseScore> gorseScores = gorseClient.recommend(viewerId, n, gorseOffset);
        int shortfall = n - gorseScores.size();
        if (shortfall <= 0) {
            return new SourceBatch(SOURCE_GORSE, gorseScores, gorseScores.size(), 0);
        }
        // Gorse's own ranker merges the trending recommender as one of its inputs (see
        // gorse/config/config.toml [recommend.ranker]), so an item already shown via gorse on an
        // earlier page is a realistic topup candidate unless excluded by its full history, not
        // just this round's results. On the first page (gorseOffset 0) gorseScores already is
        // that full history, so no extra call is needed; on a later page, fetch the complete
        // prefix gorse has served this viewer from position 0 up to now.
        List<GorseScore> gorseShownSoFar =
                gorseOffset == 0
                        ? gorseScores
                        : gorseClient.recommend(viewerId, gorseOffset + gorseScores.size(), 0);
        TopUpResult topUp = topUp(viewerId, shortfall, trendingOffset, gorseShownSoFar);
        List<GorseScore> combined = new ArrayList<>(gorseScores);
        combined.addAll(topUp.candidates());
        return new SourceBatch(SOURCE_GORSE, combined, gorseScores.size(), topUp.chunkFetched());
    }

    private record TopUpResult(List<GorseScore> candidates, int chunkFetched) {}

    // Failures here are deliberately swallowed rather than rethrown: this method runs inside
    // fetch(), which the gorse circuit breaker wraps, so an uncaught exception would trip
    // popularFallback and discard the primary Gorse results that already succeeded. Topup is an
    // enrichment of a successful response, not a required part of it.
    private TopUpResult topUp(
            UUID viewerId, int shortfall, int trendingOffset, List<GorseScore> alreadyReturned) {
        int chunkSize = shortfall * recommendationProperties.getTopUpOverfetchMultiplier();
        try {
            Set<String> excluded = new HashSet<>();
            for (GorseScore score : alreadyReturned) {
                excluded.add(score.id());
            }
            List<GorseScore> chunk = gorseClient.trending(chunkSize, trendingOffset);
            Set<String> readIds = readSet(viewerId);

            List<GorseScore> unread = new ArrayList<>();
            List<GorseScore> read = new ArrayList<>();
            for (GorseScore score : chunk) {
                if (!excluded.add(score.id())) {
                    continue; // already returned by gorse, or already picked for this chunk
                }
                (readIds.contains(score.id()) ? read : unread).add(score);
            }

            List<GorseScore> candidates = new ArrayList<>();
            takeUpTo(unread, shortfall - candidates.size(), candidates);
            takeUpTo(read, shortfall - candidates.size(), candidates);
            // The chunk's full size is reported regardless of how many candidates were selected
            // from it: whether the caller actually advances the trending offset by this amount
            // depends on whether it ends up inspecting any of these candidates at all (see
            // RecommendationFeedServiceImpl). When it does, the whole chunk is considered spent -
            // an unread candidate beyond the shortfall, or a read candidate scanned past while
            // filling from unread, is not retried on a later page. See the plan's Finding 7.
            return new TopUpResult(candidates, chunk.size());
        } catch (RestClientException | DataAccessException | ClickHouseException e) {
            // A ClickHouse failure lands here too, and never reaches the gorse breaker: the
            // read-set only enriches a successful Gorse answer, so losing it costs the unread
            // ordering of the topup and nothing else.
            log.warn(
                    "Recommendation topup unavailable for user {}, serving gorse results only: {}",
                    viewerId,
                    e.getMessage());
            return new TopUpResult(List.of(), 0);
        }
    }

    private Set<String> readSet(UUID viewerId) {
        OffsetDateTime to = OffsetDateTime.now();
        OffsetDateTime from = to.minus(recommendationProperties.getReadSetWindow());
        List<UUID> recentReads =
                userEventAnalyticsRepository.findRecentEntityIds(
                        viewerId,
                        UserEventType.POST_VIEW,
                        from,
                        to,
                        recommendationProperties.getReadSetMaxRows());
        Set<String> readIds = new HashSet<>(recentReads.size());
        for (UUID id : recentReads) {
            readIds.add(id.toString());
        }
        return readIds;
    }

    private static void takeUpTo(List<GorseScore> from, int remaining, List<GorseScore> into) {
        for (int i = 0; i < from.size() && i < remaining; i++) {
            into.add(from.get(i));
        }
    }

    // Resilience4j fallback: invoked when the primary throws OR the circuit is open. Only
    // availability failures may degrade; programming errors must surface to the caller. This
    // method never calls trending() or the read-set repository: topup only runs after a
    // successful primary call, inside fetch()'s own body, so a breaker-open state serves plain
    // popularity with no topup attempted at all.
    SourceBatch popularFallback(
            UUID viewerId, char source, int n, int gorseOffset, int trendingOffset, Throwable t) {
        if (!isAvailabilityFailure(t)) {
            if (t instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Unexpected recommendation source failure", t);
        }
        log.warn(
                "Gorse source '{}' unavailable ({}: {}), degrading",
                source,
                t.getClass().getSimpleName(),
                t.getMessage());
        if (source == SOURCE_POPULAR) {
            // Popularity itself failed; an empty batch signals the caller to fall through to the
            // chronological feed.
            return new SourceBatch(SOURCE_POPULAR, List.of(), 0, 0);
        }
        try {
            List<GorseScore> popular = gorseClient.popular(n, gorseOffset);
            return new SourceBatch(SOURCE_POPULAR, popular, popular.size(), 0);
        } catch (RestClientException e) {
            log.warn("Gorse popularity fallback also unavailable: {}", e.getMessage());
            return new SourceBatch(SOURCE_POPULAR, List.of(), 0, 0);
        }
    }

    // Connection/timeout failures, open circuit, and 5xx responses count as unavailability; 4xx
    // responses indicate a client-side bug and must not be masked as degradation.
    private static boolean isAvailabilityFailure(Throwable t) {
        if (t instanceof CallNotPermittedException) {
            return true;
        }
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof ResourceAccessException || cause instanceof IOException) {
                return true;
            }
            if (cause instanceof RestClientResponseException response) {
                return response.getStatusCode().is5xxServerError();
            }
        }
        return false;
    }
}
