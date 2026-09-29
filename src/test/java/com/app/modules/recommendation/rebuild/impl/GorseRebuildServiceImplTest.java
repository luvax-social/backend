package com.app.modules.recommendation.rebuild.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

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
import com.app.modules.recommendation.rebuild.GorseRebuildStatus;
import com.app.modules.recommendation.repository.UserEventAnalyticsRepository;
import com.app.modules.recommendation.repository.UserEventAnalyticsRepository.FeedbackTotal;

import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

@ExtendWith(MockitoExtension.class)
class GorseRebuildServiceImplTest {

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
    private final List<Long> pauses = new ArrayList<>();
    private GorseRebuildServiceImpl service;

    private final UUID user = UUID.randomUUID();
    private final UUID post = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(controllerProvider.getIfAvailable()).thenReturn(controller);
        lenient().when(clickHouse.isReady()).thenReturn(true);
        lenient().when(gorseClient.listItems(any(), anyInt())).thenReturn(emptyPage());
        lenient().when(gorsePurger.findPurgeObstacles()).thenReturn(List.of());
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
                        pauses::add);
    }

    private static GorseItemPage emptyPage() {
        return new GorseItemPage("", List.of());
    }

    private static GorseRebuildRun newRun(GorseRebuildPhase phase) {
        GorseRebuildRun run = new GorseRebuildRun();
        run.setId(UUID.randomUUID());
        run.setToken("token");
        run.setStatus(GorseRebuildStatus.RUNNING);
        run.setPhase(phase);
        return run;
    }

    private void stubCatalogue() {
        lenient().when(source.findUserIdsAfter(any(), anyInt())).thenReturn(List.of(user));
        lenient()
                .when(source.findPostsAfter(any(), anyInt()))
                .thenReturn(List.of(new PostRow(post, false, NOW)));
        lenient().when(source.findAllPosts()).thenReturn(List.of(new PostRow(post, false, NOW)));
        lenient()
                .when(hashtagService.getHashtagNamesForPosts(any()))
                .thenReturn(Map.of(post, List.of("music")));
        lenient()
                .when(userEvents.findFeedbackTotals(any()))
                .thenReturn(List.of(new FeedbackTotal("like", user, post, 2.0, NOW)));
        lenient().when(source.findExistingPostIds(any())).thenReturn(Set.of(post));
        lenient()
                .when(gorseClient.getFeedback("like", user.toString(), post.toString()))
                .thenReturn(
                        Optional.of(
                                new GorseFeedback(
                                        "like", user.toString(), post.toString(), NOW, 2.0)));
    }

    private void stubGorseHoldingTheCatalogue() {
        lenient()
                .when(gorseClient.listItems(any(), eq(1000)))
                .thenReturn(
                        new GorseItemPage(
                                "",
                                List.of(
                                        new GorseItem(
                                                post.toString(),
                                                false,
                                                List.of(),
                                                List.of("music"),
                                                NOW,
                                                null))));
    }

    @Test
    void execute_missingTruncatePrivilege_failsInPreflightNamingItAndChangesNothing() {
        GorseRebuildRun run = newRun(GorseRebuildPhase.PREFLIGHT);
        when(gorsePurger.findPurgeObstacles())
                .thenReturn(
                        List.of(
                                "role luvax lacks the TRUNCATE privilege on gorse.public.feedback",
                                "role luvax lacks the TRUNCATE privilege on gorse.public.items"));

        service.execute(run);

        assertThat(run.getStatus()).isEqualTo(GorseRebuildStatus.FAILED);
        assertThat(run.getPhase()).isEqualTo(GorseRebuildPhase.PREFLIGHT);
        assertThat(run.getLastError())
                .contains("lacks the TRUNCATE privilege on gorse.public.feedback")
                .contains("gorse.public.items")
                .contains("restart with the same token");
        verify(gorsePurger, never()).purge();
        verify(gorseClient, never()).upsertUsers(anyList());
        verify(gorseClient, never()).upsertItems(anyList());
        verify(gorseClient, never()).upsertFeedback(anyList());
        verify(controller, never()).suspend(anyString());
        assertThat(meters.get("luvax.gorse.rebuild.phase").gauge().value()).isEqualTo(-1.0);
    }

    @Test
    void execute_analyticsNotReady_failsInPreflight() {
        GorseRebuildRun run = newRun(GorseRebuildPhase.PREFLIGHT);
        when(clickHouse.isReady()).thenReturn(false);

        service.execute(run);

        assertThat(run.getStatus()).isEqualTo(GorseRebuildStatus.FAILED);
        assertThat(run.getLastError()).contains("ClickHouse analytics schema is not ready");
        verify(gorsePurger, never()).purge();
    }

    @Test
    void execute_gorseDoesNotAnswer_failsInPreflight() {
        GorseRebuildRun run = newRun(GorseRebuildPhase.PREFLIGHT);
        when(gorseClient.listItems(any(), eq(1)))
                .thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));

        service.execute(run);

        assertThat(run.getStatus()).isEqualTo(GorseRebuildStatus.FAILED);
        assertThat(run.getLastError()).contains("Gorse did not answer");
        verify(gorsePurger, never()).purge();
    }

    @Test
    void execute_fullRun_pushesEverythingVerifiesAndEndsDone() {
        GorseRebuildRun run = newRun(GorseRebuildPhase.PREFLIGHT);
        stubCatalogue();
        stubGorseHoldingTheCatalogue();

        service.execute(run);

        assertThat(run.getStatus()).isEqualTo(GorseRebuildStatus.DONE);
        assertThat(run.getPhase()).isEqualTo(GorseRebuildPhase.DONE);
        assertThat(run.getUsersSent()).isEqualTo(1);
        assertThat(run.getItemsSent()).isEqualTo(1);
        assertThat(run.getFeedbackSent()).isEqualTo(1);
        assertThat(run.getFinishedAt()).isNotNull();
        InOrder order = inOrder(controller, gorsePurger, gorseClient);
        order.verify(controller).suspend("gorse-rebuild");
        order.verify(gorsePurger).purge();
        order.verify(gorseClient).upsertUsers(anyList());
        order.verify(gorseClient).upsertItems(anyList());
        order.verify(gorseClient).upsertFeedback(anyList());
        order.verify(controller).resume("gorse-rebuild");
        assertThat(meters.get("luvax.gorse.rebuild.phase").gauge().value()).isEqualTo(7.0);
    }

    @Test
    void execute_itemsCarryHiddenFlagAndTagLabels() {
        GorseRebuildRun run = newRun(GorseRebuildPhase.ITEMS);
        UUID removed = UUID.randomUUID();
        when(source.findPostsAfter(any(), anyInt()))
                .thenReturn(
                        List.of(new PostRow(post, false, NOW), new PostRow(removed, true, NOW)));
        when(hashtagService.getHashtagNamesForPosts(any()))
                .thenReturn(Map.of(post, List.of("music", "night")));
        lenient().when(source.findUserIdsAfter(any(), anyInt())).thenReturn(List.of());
        lenient().when(source.findAllPosts()).thenReturn(List.of());

        service.execute(run);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<GorseItem>> sent =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(gorseClient).upsertItems(sent.capture());
        assertThat(sent.getValue())
                .extracting(GorseItem::itemId, GorseItem::hidden, GorseItem::labels)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                post.toString(), false, List.of("music", "night")),
                        org.assertj.core.groups.Tuple.tuple(removed.toString(), true, List.of()));
    }

    @Test
    void execute_feedbackForAPostPostgresDoesNotHave_isSkippedAndCounted() {
        GorseRebuildRun run = newRun(GorseRebuildPhase.FEEDBACK);
        UUID unknown = UUID.randomUUID();
        stubCatalogue();
        stubGorseHoldingTheCatalogue();
        when(userEvents.findFeedbackTotals(any()))
                .thenReturn(
                        List.of(
                                new FeedbackTotal("like", user, post, 2.0, NOW),
                                new FeedbackTotal("like", user, unknown, 1.0, NOW)));

        service.execute(run);

        assertThat(run.getFeedbackSent()).isEqualTo(1);
        assertThat(run.getFeedbackSkipped()).isEqualTo(1);
        assertThat(meters.get("luvax.gorse.rebuild.skipped").counter().count()).isEqualTo(1.0);
    }

    @Test
    void execute_gorseServerFailure_isRetriedThreeTimesThenFailsWithTheCheckpointKept() {
        GorseRebuildRun run = newRun(GorseRebuildPhase.USERS);
        stubCatalogue();
        doThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))
                .when(gorseClient)
                .upsertUsers(anyList());

        service.execute(run);

        assertThat(pauses).containsExactly(2_000L, 4_000L, 8_000L);
        verify(gorseClient, times(4)).upsertUsers(anyList());
        assertThat(run.getStatus()).isEqualTo(GorseRebuildStatus.FAILED);
        assertThat(run.getPhase()).isEqualTo(GorseRebuildPhase.USERS);
        assertThat(run.getUsersSent()).isZero();
        verify(controller).resume("gorse-rebuild");
    }

    @Test
    void execute_gorseClientError_failsAtOnceWithoutRetrying() {
        GorseRebuildRun run = newRun(GorseRebuildPhase.USERS);
        stubCatalogue();
        doThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST))
                .when(gorseClient)
                .upsertUsers(anyList());

        service.execute(run);

        assertThat(pauses).isEmpty();
        verify(gorseClient, times(1)).upsertUsers(anyList());
        assertThat(run.getStatus()).isEqualTo(GorseRebuildStatus.FAILED);
        assertThat(run.getLastError()).contains("400");
        verify(controller).resume("gorse-rebuild");
    }

    @Test
    void execute_resumedInFeedback_continuesAfterTheCheckpointWithoutRepeatingEarlierPhases() {
        UUID checkpoint = UUID.randomUUID();
        GorseRebuildRun run = newRun(GorseRebuildPhase.FEEDBACK);
        run.setCheckpointId(checkpoint);
        run.setUsersSent(140);
        run.setItemsSent(746);
        stubCatalogue();
        stubGorseHoldingTheCatalogue();

        service.execute(run);

        verify(source).findUserIdsAfter(eq(checkpoint), anyInt());
        verify(gorsePurger, never()).purge();
        verify(gorseClient, never()).upsertUsers(anyList());
        verify(gorseClient, never()).upsertItems(anyList());
        assertThat(run.getStatus()).isEqualTo(GorseRebuildStatus.DONE);
        assertThat(run.getUsersSent()).isEqualTo(140);
        verify(controller).suspend("gorse-rebuild");
        verify(controller).resume("gorse-rebuild");
    }

    @Test
    void execute_gorseHoldsAnItemPostgresDoesNot_endsFailedVerificationNamingIt() {
        GorseRebuildRun run = newRun(GorseRebuildPhase.VERIFY);
        stubCatalogue();
        when(gorseClient.listItems(any(), eq(1000)))
                .thenReturn(
                        new GorseItemPage(
                                "",
                                List.of(
                                        new GorseItem(
                                                post.toString(),
                                                false,
                                                List.of(),
                                                List.of(),
                                                NOW,
                                                null),
                                        new GorseItem(
                                                "stray-item",
                                                false,
                                                List.of(),
                                                List.of(),
                                                NOW,
                                                null))));

        service.execute(run);

        assertThat(run.getStatus()).isEqualTo(GorseRebuildStatus.FAILED_VERIFICATION);
        assertThat(run.getLastError()).contains("stray=1");
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(meters.get("luvax.gorse.rebuild.verification.failures").counter().count())
                .isEqualTo(1.0);
        verify(controller).resume("gorse-rebuild");
    }

    @Test
    void execute_hiddenFlagDiffers_endsFailedVerification() {
        GorseRebuildRun run = newRun(GorseRebuildPhase.VERIFY);
        stubCatalogue();
        when(gorseClient.listItems(any(), eq(1000)))
                .thenReturn(
                        new GorseItemPage(
                                "",
                                List.of(
                                        new GorseItem(
                                                post.toString(),
                                                true,
                                                List.of(),
                                                List.of(),
                                                NOW,
                                                null))));

        service.execute(run);

        assertThat(run.getStatus()).isEqualTo(GorseRebuildStatus.FAILED_VERIFICATION);
        assertThat(run.getLastError()).contains("hiddenMismatch=1");
    }

    @Test
    void execute_feedbackValueDiffers_endsFailedVerification() {
        GorseRebuildRun run = newRun(GorseRebuildPhase.VERIFY);
        stubCatalogue();
        stubGorseHoldingTheCatalogue();
        when(gorseClient.getFeedback("like", user.toString(), post.toString()))
                .thenReturn(
                        Optional.of(
                                new GorseFeedback(
                                        "like", user.toString(), post.toString(), NOW, 5.0)));

        service.execute(run);

        assertThat(run.getStatus()).isEqualTo(GorseRebuildStatus.FAILED_VERIFICATION);
        assertThat(run.getLastError()).contains("feedbackMismatch=1");
    }

    @Test
    void execute_unexpectedFailure_isRecordedAndTheListenerStillResumes() {
        GorseRebuildRun run = newRun(GorseRebuildPhase.FEEDBACK);
        stubCatalogue();
        when(userEvents.findFeedbackTotals(any()))
                .thenThrow(new IllegalStateException("clickhouse went away"));

        service.execute(run);

        assertThat(run.getStatus()).isEqualTo(GorseRebuildStatus.FAILED);
        assertThat(run.getLastError()).contains("clickhouse went away");
        assertThat(run.getPhase()).isEqualTo(GorseRebuildPhase.FEEDBACK);
        verify(controller).resume("gorse-rebuild");
    }
}
