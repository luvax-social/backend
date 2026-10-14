package com.app.modules.recommendation.rebuild.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import com.app.common.analytics.ClickHouseOperations;
import com.app.common.analytics.ingest.AnalyticsIngestionController;
import com.app.modules.hashtag.service.HashtagService;
import com.app.modules.recommendation.client.GorseClient;
import com.app.modules.recommendation.client.GorsePurger;
import com.app.modules.recommendation.client.dto.GorseFeedback;
import com.app.modules.recommendation.client.dto.GorseItem;
import com.app.modules.recommendation.client.dto.GorseItemPage;
import com.app.modules.recommendation.config.GorseRebuildProperties;
import com.app.modules.recommendation.rebuild.GorseRebuildMetrics;
import com.app.modules.recommendation.rebuild.GorseRebuildPhase;
import com.app.modules.recommendation.rebuild.GorseRebuildRun;
import com.app.modules.recommendation.rebuild.GorseRebuildRunRepository;
import com.app.modules.recommendation.rebuild.GorseRebuildSourceRepository;
import com.app.modules.recommendation.rebuild.GorseRebuildSourceRepository.PostRow;
import com.app.modules.recommendation.rebuild.GorseRebuildSourceRepository.UserPost;
import com.app.modules.recommendation.rebuild.GorseRebuildStatus;
import com.app.modules.recommendation.repository.UserEventAnalyticsRepository;
import com.app.modules.recommendation.repository.UserEventAnalyticsRepository.FeedbackTotal;

import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

/**
 * The feedback phase of the rebuild for the one feedback type whose truth is not the ClickHouse
 * sum. ClickHouse is append-only, so a preference that was marked and then undone still has its
 * earlier row there; {@code post_not_interested} in PostgreSQL decides whether the preference
 * stands.
 */
@ExtendWith(MockitoExtension.class)
class GorseRebuildNegativeFeedbackTest {

    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);

    @Mock private GorseClient gorseClient;
    @Mock private GorsePurger gorsePurger;
    @Mock private GorseRebuildRunRepository runs;
    @Mock private GorseRebuildSourceRepository source;
    @Mock private UserEventAnalyticsRepository userEvents;
    @Mock private HashtagService hashtagService;
    @Mock private ClickHouseOperations clickHouse;
    @Mock private ObjectProvider<AnalyticsIngestionController> controllerProvider;
    @Mock private AnalyticsIngestionController controller;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private GorseRebuildServiceImpl service;

    private final UUID user = UUID.randomUUID();
    private final UUID likedPost = UUID.randomUUID();
    private final UUID livePreferencePost = UUID.randomUUID();
    private final UUID undonePreferencePost = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(controllerProvider.getIfAvailable()).thenReturn(controller);
        lenient().when(clickHouse.isReady()).thenReturn(true);
        lenient().when(source.findUserIdsAfter(any(), anyInt())).thenReturn(List.of(user));
        lenient().when(gorseClient.listItems(any(), anyInt())).thenReturn(heldCatalogue());
        lenient()
                .when(source.findAllPosts())
                .thenReturn(
                        List.of(
                                new PostRow(likedPost, false, NOW),
                                new PostRow(livePreferencePost, false, NOW),
                                new PostRow(undonePreferencePost, false, NOW)));
        lenient()
                .when(source.findExistingPostIds(any()))
                .thenReturn(Set.of(likedPost, livePreferencePost, undonePreferencePost));
        lenient().when(hashtagService.getHashtagNamesForPosts(any())).thenReturn(Map.of());
        lenient()
                .when(gorseClient.getFeedback(any(), any(), any()))
                .thenAnswer(
                        invocation ->
                                Optional.of(
                                        new GorseFeedback(
                                                invocation.getArgument(0),
                                                invocation.getArgument(1),
                                                invocation.getArgument(2),
                                                NOW,
                                                "like".equals(invocation.getArgument(0))
                                                        ? 2.0
                                                        : 1.0)));
        service =
                new GorseRebuildServiceImpl(
                        gorseClient,
                        gorsePurger,
                        runs,
                        source,
                        userEvents,
                        hashtagService,
                        clickHouse,
                        controllerProvider,
                        RateLimiterRegistry.ofDefaults(),
                        new GorseRebuildProperties(5, 500, 500, 1000),
                        new GorseRebuildMetrics(meters),
                        ObservationRegistry.NOOP,
                        millis -> {});
    }

    private GorseItemPage heldCatalogue() {
        return new GorseItemPage(
                "",
                List.of(likedPost, livePreferencePost, undonePreferencePost).stream()
                        .map(
                                id ->
                                        new GorseItem(
                                                id.toString(),
                                                false,
                                                List.of(),
                                                List.of(),
                                                NOW,
                                                null))
                        .toList());
    }

    private static GorseRebuildRun feedbackRun() {
        GorseRebuildRun run = new GorseRebuildRun();
        run.setId(UUID.randomUUID());
        run.setToken("token");
        run.setStatus(GorseRebuildStatus.RUNNING);
        run.setPhase(GorseRebuildPhase.FEEDBACK);
        return run;
    }

    @Test
    void execute_undonePreference_isSkippedAndTheLivePreferenceIsSent() {
        // ClickHouse still holds a row for both preferences: the append-only table never forgets
        // that the second one was marked before it was taken back.
        when(userEvents.findFeedbackTotals(any()))
                .thenReturn(
                        List.of(
                                new FeedbackTotal("like", user, likedPost, 2.0, NOW),
                                new FeedbackTotal(
                                        "not_interested", user, livePreferencePost, 1.0, NOW),
                                new FeedbackTotal(
                                        "not_interested", user, undonePreferencePost, 1.0, NOW)));
        when(source.findNotInterestedPairs(List.of(user)))
                .thenReturn(Set.of(new UserPost(user, livePreferencePost)));
        GorseRebuildRun run = feedbackRun();

        service.execute(run);

        ArgumentCaptor<List<GorseFeedback>> sent = ArgumentCaptor.forClass(List.class);
        verify(gorseClient).upsertFeedback(sent.capture());
        assertThat(sent.getValue())
                .extracting(GorseFeedback::feedbackType, GorseFeedback::itemId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("like", likedPost.toString()),
                        org.assertj.core.groups.Tuple.tuple(
                                "not_interested", livePreferencePost.toString()));
        assertThat(run.getStatus()).isEqualTo(GorseRebuildStatus.DONE);
        assertThat(run.getFeedbackSent()).isEqualTo(2);
        assertThat(run.getFeedbackSkipped()).isEqualTo(1);
        assertThat(
                        meters.get("luvax.gorse.rebuild.skipped")
                                .tag("reason", "undone_not_interested")
                                .counter()
                                .count())
                .isEqualTo(1.0);
        assertThat(
                        meters.get("luvax.gorse.rebuild.skipped")
                                .tag("reason", "unknown_post")
                                .counter()
                                .count())
                .isZero();
        verify(gorseClient, never()).hideItem(any());
    }

    @Test
    void execute_everyPreferenceUndone_sendsNoNegativeFeedbackAtAll() {
        when(userEvents.findFeedbackTotals(any()))
                .thenReturn(
                        List.of(
                                new FeedbackTotal(
                                        "not_interested", user, undonePreferencePost, 1.0, NOW)));
        when(source.findNotInterestedPairs(List.of(user))).thenReturn(Set.of());
        GorseRebuildRun run = feedbackRun();

        service.execute(run);

        verify(gorseClient, never()).upsertFeedback(anyList());
        assertThat(run.getStatus()).isEqualTo(GorseRebuildStatus.DONE);
        assertThat(run.getFeedbackSent()).isZero();
        assertThat(run.getFeedbackSkipped()).isEqualTo(1);
    }

    @Test
    void execute_noNegativeFeedbackInTheBatch_doesNotReadThePreferenceTable() {
        when(userEvents.findFeedbackTotals(any()))
                .thenReturn(List.of(new FeedbackTotal("like", user, likedPost, 2.0, NOW)));
        GorseRebuildRun run = feedbackRun();

        service.execute(run);

        verify(source, never()).findNotInterestedPairs(any());
        assertThat(run.getFeedbackSent()).isEqualTo(1);
    }

    @Test
    void execute_unknownPostAndUndonePreference_areCountedUnderTheirOwnReasons() {
        UUID unknown = UUID.randomUUID();
        when(userEvents.findFeedbackTotals(any()))
                .thenReturn(
                        List.of(
                                new FeedbackTotal("like", user, unknown, 1.0, NOW),
                                new FeedbackTotal(
                                        "not_interested", user, undonePreferencePost, 1.0, NOW)));
        when(source.findNotInterestedPairs(List.of(user))).thenReturn(Set.of());
        GorseRebuildRun run = feedbackRun();

        service.execute(run);

        assertThat(run.getFeedbackSkipped()).isEqualTo(2);
        assertThat(
                        meters.get("luvax.gorse.rebuild.skipped")
                                .tag("reason", "unknown_post")
                                .counter()
                                .count())
                .isEqualTo(1.0);
        assertThat(
                        meters.get("luvax.gorse.rebuild.skipped")
                                .tag("reason", "undone_not_interested")
                                .counter()
                                .count())
                .isEqualTo(1.0);
    }

    @Test
    void execute_verification_doesNotExpectAnUndonePreferenceInGorse() {
        // Sampling an undone tuple would look for feedback the rebuild deliberately did not send
        // and end the run FAILED_VERIFICATION on a store that is exactly right.
        when(userEvents.findFeedbackTotals(any()))
                .thenReturn(
                        List.of(
                                new FeedbackTotal(
                                        "not_interested", user, undonePreferencePost, 1.0, NOW)));
        when(source.findNotInterestedPairs(List.of(user))).thenReturn(Set.of());
        GorseRebuildRun run = feedbackRun();

        service.execute(run);

        verify(gorseClient, never()).getFeedback(any(), any(), any());
        assertThat(run.getStatus()).isEqualTo(GorseRebuildStatus.DONE);
    }
}
