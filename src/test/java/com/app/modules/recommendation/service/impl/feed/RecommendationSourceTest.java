package com.app.modules.recommendation.service.impl.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

import com.app.common.analytics.ClickHouseUnavailableException;
import com.app.modules.recommendation.client.GorseClient;
import com.app.modules.recommendation.client.dto.GorseScore;
import com.app.modules.recommendation.config.RecommendationProperties;
import com.app.modules.recommendation.enums.UserEventType;
import com.app.modules.recommendation.repository.UserEventAnalyticsRepository;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

@ExtendWith(MockitoExtension.class)
class RecommendationSourceTest {

    @Mock private GorseClient gorseClient;
    @Mock private UserEventAnalyticsRepository userEventAnalyticsRepository;

    private RecommendationProperties properties;
    private RecommendationSource source;
    private final UUID viewerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties = new RecommendationProperties();
        // Deterministic chunk sizing in tests: shortfall * 1 = shortfall.
        properties.setTopUpOverfetchMultiplier(1);
        source = new RecommendationSource(gorseClient, userEventAnalyticsRepository, properties);
    }

    @Test
    void fetch_gorseShort_ordersGorseThenUnreadTrendingThenReadTrending() {
        UUID gorseItem = UUID.randomUUID();
        UUID unreadTrendingItem = UUID.randomUUID();
        UUID readTrendingItem = UUID.randomUUID();
        when(gorseClient.recommend(viewerId, 3, 0))
                .thenReturn(List.of(new GorseScore(gorseItem.toString(), 9.0)));
        // Shortfall is 2, overfetch multiplier 1 -> trending chunk size 2. The trending chunk
        // itself returns the read-looking item ranked ahead of the unread one, so a passing
        // assertion on output order proves the implementation reorders by pass rather than merely
        // preserving trending's own order.
        when(gorseClient.trending(2, 0))
                .thenReturn(
                        List.of(
                                new GorseScore(readTrendingItem.toString(), 8.0),
                                new GorseScore(unreadTrendingItem.toString(), 7.0)));
        when(userEventAnalyticsRepository.findRecentEntityIds(
                        eq(viewerId), eq(UserEventType.POST_VIEW), any(), any(), anyInt()))
                .thenReturn(List.of(readTrendingItem));

        RecommendationSource.SourceBatch batch =
                source.fetch(viewerId, RecommendationSource.SOURCE_GORSE, 3, 0, 0);

        assertThat(batch.scores().stream().map(GorseScore::id).toList())
                .containsExactly(
                        gorseItem.toString(),
                        unreadTrendingItem.toString(),
                        readTrendingItem.toString());
        assertThat(batch.primaryCount()).isEqualTo(1);
        assertThat(batch.trendingChunkFetched()).isEqualTo(2);
    }

    @Test
    void fetch_gorseFillsFullOverfetchedSet_noTopupAttempted() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(gorseClient.recommend(viewerId, 2, 0))
                .thenReturn(
                        List.of(
                                new GorseScore(a.toString(), 9.0),
                                new GorseScore(b.toString(), 8.0)));

        RecommendationSource.SourceBatch batch =
                source.fetch(viewerId, RecommendationSource.SOURCE_GORSE, 2, 0, 0);

        assertThat(batch.scores()).hasSize(2);
        verify(gorseClient, never()).trending(anyInt(), anyInt());
        verify(userEventAnalyticsRepository, never())
                .findRecentEntityIds(any(), any(), any(), any(), anyInt());
        assertThat(batch.trendingChunkFetched()).isZero();
    }

    @Test
    void fetch_topup_neverDuplicatesAGorseReturnedId() {
        UUID sharedId = UUID.randomUUID();
        UUID onlyGorseItem = UUID.randomUUID();
        when(gorseClient.recommend(viewerId, 3, 0))
                .thenReturn(List.of(new GorseScore(onlyGorseItem.toString(), 9.0)));
        // Trending happens to return the same id Gorse already served, plus one new one.
        when(gorseClient.trending(2, 0))
                .thenReturn(
                        List.of(
                                new GorseScore(sharedId.toString(), 5.0),
                                new GorseScore(onlyGorseItem.toString(), 4.0)));
        when(userEventAnalyticsRepository.findRecentEntityIds(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        RecommendationSource.SourceBatch batch =
                source.fetch(viewerId, RecommendationSource.SOURCE_GORSE, 3, 0, 0);

        List<String> ids = batch.scores().stream().map(GorseScore::id).toList();
        assertThat(ids).containsExactly(onlyGorseItem.toString(), sharedId.toString());
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void fetch_circuitBreakerOpen_topupNeverAttempted() {
        // popularFallback is what the framework invokes when the breaker is open; it never calls
        // trending() or the read-set repository, because topup is code inside the primary call's
        // own body, not part of the fallback method.
        RecommendationSource.SourceBatch batch =
                source.popularFallback(
                        viewerId,
                        RecommendationSource.SOURCE_GORSE,
                        3,
                        0,
                        0,
                        CallNotPermittedException.createCallNotPermittedException(
                                CircuitBreaker.ofDefaults("gorse")));

        verify(gorseClient, never()).trending(anyInt(), anyInt());
        verify(userEventAnalyticsRepository, never())
                .findRecentEntityIds(any(), any(), any(), any(), anyInt());
        assertThat(batch.source()).isEqualTo(RecommendationSource.SOURCE_POPULAR);
    }

    @Test
    void fetch_topupTrendingCallThrows_returnsGorseResultsOnlyWithoutTrippingFallback() {
        UUID gorseItem = UUID.randomUUID();
        when(gorseClient.recommend(viewerId, 3, 0))
                .thenReturn(List.of(new GorseScore(gorseItem.toString(), 9.0)));
        when(gorseClient.trending(anyInt(), anyInt()))
                .thenThrow(new ResourceAccessException("timeout"));

        RecommendationSource.SourceBatch batch =
                source.fetch(viewerId, RecommendationSource.SOURCE_GORSE, 3, 0, 0);

        assertThat(batch.scores()).hasSize(1);
        assertThat(batch.source()).isEqualTo(RecommendationSource.SOURCE_GORSE);
        assertThat(batch.trendingChunkFetched()).isZero();
    }

    @Test
    void fetch_clickHouseUnavailableDuringTopup_degradesToGorseResultsAndNeverTouchesTheBreaker() {
        UUID gorseItem = UUID.randomUUID();
        when(gorseClient.recommend(viewerId, 3, 0))
                .thenReturn(List.of(new GorseScore(gorseItem.toString(), 9.0)));
        when(gorseClient.trending(anyInt(), anyInt()))
                .thenReturn(List.of(new GorseScore(UUID.randomUUID().toString(), 5.0)));
        when(userEventAnalyticsRepository.findRecentEntityIds(any(), any(), any(), any(), anyInt()))
                .thenThrow(
                        new ClickHouseUnavailableException(
                                ClickHouseUnavailableException.Reason.CIRCUIT_OPEN,
                                "circuit open"));

        RecommendationSource.SourceBatch batch =
                source.fetch(viewerId, RecommendationSource.SOURCE_GORSE, 3, 0, 0);

        // The primary Gorse list is served as it is, and the source is still Gorse: nothing was
        // thrown out of fetch(), so the gorse circuit breaker that wraps it records no failure.
        assertThat(batch.source()).isEqualTo(RecommendationSource.SOURCE_GORSE);
        assertThat(batch.scores()).extracting(GorseScore::id).containsExactly(gorseItem.toString());
        assertThat(batch.trendingChunkFetched()).isZero();
    }

    @Test
    void fetch_topupOnLaterPage_excludesEveryIdGorseAlreadyShownAcrossEarlierPages() {
        // gorseOffset=5: this is not the first page/round, so an earlier page already consumed
        // gorse positions 0-4. One of those earlier ids also appears in the trending chunk this
        // round - gorse's own ranker merges trending as an input, so this is the realistic case,
        // not a contrived one. Only the full prefix fetch (not just this round's 1 result) can
        // catch it.
        UUID earlierPageItem = UUID.randomUUID();
        UUID currentRoundGorseItem = UUID.randomUUID();
        UUID freshTrendingItem = UUID.randomUUID();
        when(gorseClient.recommend(viewerId, 3, 5))
                .thenReturn(List.of(new GorseScore(currentRoundGorseItem.toString(), 9.0)));
        // Prefix fetch covers positions [0, 6): the 5 already consumed plus this round's 1.
        when(gorseClient.recommend(viewerId, 6, 0))
                .thenReturn(
                        List.of(
                                new GorseScore(earlierPageItem.toString(), 9.5),
                                new GorseScore(currentRoundGorseItem.toString(), 9.0)));
        when(gorseClient.trending(2, 0))
                .thenReturn(
                        List.of(
                                new GorseScore(earlierPageItem.toString(), 5.0),
                                new GorseScore(freshTrendingItem.toString(), 4.0)));
        when(userEventAnalyticsRepository.findRecentEntityIds(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        RecommendationSource.SourceBatch batch =
                source.fetch(viewerId, RecommendationSource.SOURCE_GORSE, 3, 5, 0);

        List<String> ids = batch.scores().stream().map(GorseScore::id).toList();
        assertThat(ids)
                .containsExactly(currentRoundGorseItem.toString(), freshTrendingItem.toString());
        assertThat(ids).doesNotContain(earlierPageItem.toString());
    }

    @Test
    void fetch_popularSource_neverAttemptsTopup() {
        when(gorseClient.popular(5, 0)).thenReturn(List.of(new GorseScore("only-item", 1.0)));

        RecommendationSource.SourceBatch batch =
                source.fetch(viewerId, RecommendationSource.SOURCE_POPULAR, 5, 0, 0);

        assertThat(batch.scores()).hasSize(1);
        verify(gorseClient, never()).trending(anyInt(), anyInt());
        verify(userEventAnalyticsRepository, never())
                .findRecentEntityIds(any(), any(), any(), any(), anyInt());
    }
}
