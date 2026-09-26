package com.app.modules.recommendation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.response.UserSummaryResponse;
import com.app.common.response.ViewerRelationshipResponse;
import com.app.modules.recommendation.repository.SuggestionDismissalRepository;
import com.app.modules.recommendation.repository.UserSuggestionRepository;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.service.UserSummaryService;

@ExtendWith(MockitoExtension.class)
class SuggestionServiceImplTest {

    private static final UUID VIEWER = UUID.randomUUID();
    private static final UUID GRAPH_ONE = UUID.randomUUID();
    private static final UUID GRAPH_TWO = UUID.randomUUID();
    private static final UUID AFFINITY_ONE = UUID.randomUUID();
    private static final UUID VERIFIED_ONE = UUID.randomUUID();

    @Mock private UserSuggestionRepository userSuggestionRepository;
    @Mock private SuggestionDismissalRepository suggestionDismissalRepository;
    @Mock private GorseNeighbourSource gorseNeighbourSource;
    @Mock private UserSummaryService userSummaryService;
    @Mock private SocialService socialService;

    private SuggestionServiceImpl service;

    @BeforeEach
    void setUp() {
        service =
                new SuggestionServiceImpl(
                        userSuggestionRepository,
                        suggestionDismissalRepository,
                        gorseNeighbourSource,
                        userSummaryService,
                        socialService);
    }

    @Test
    void rebuildFor_gorseRecommenderAbsent_blendsFromTheOtherTwoSources() {
        // An unconfigured [[recommend.user-to-user]] recommender answers with an empty list rather
        // than an error, which is indistinguishable at the wire level from a user with no
        // neighbours. Both must contribute nothing rather than fail the rebuild.
        when(gorseNeighbourSource.neighbours(eq(VIEWER), anyInt())).thenReturn(List.of());
        when(userSuggestionRepository.findTwoHopCandidates(eq(VIEWER), anyInt()))
                .thenReturn(List.of(GRAPH_ONE, GRAPH_TWO));
        when(userSuggestionRepository.findAffinityCandidates(eq(VIEWER), anyInt(), anyInt()))
                .thenReturn(List.of(AFFINITY_ONE));

        when(userSuggestionRepository.countFreshFor(eq(VIEWER), any())).thenReturn(3);

        int written = service.rebuildFor(VIEWER);

        assertThat(written).isEqualTo(3);
        ArgumentCaptor<String> sources = ArgumentCaptor.forClass(String.class);
        verify(userSuggestionRepository, times(3))
                .upsertSuggestion(eq(VIEWER), any(), anyShort(), any(), sources.capture(), any());
        assertThat(sources.getAllValues()).noneMatch(value -> value.contains("gorse"));
    }

    @Test
    void rebuildFor_stampsEveryRowWithTheSameValueTheSweepIsBoundedBy() {
        // The regression this pins: the insert used to stamp computed_at from the database clock
        // while the sweep was bounded by a JVM timestamp. Whenever the database clock trailed the
        // JVM, every row the run had just written satisfied "computed_at < keptFrom" and the run
        // deleted its own generation, while still logging a healthy row count. Binding both sides
        // to one value makes that arithmetically impossible rather than merely unlikely.
        when(gorseNeighbourSource.neighbours(eq(VIEWER), anyInt())).thenReturn(List.of());
        when(userSuggestionRepository.findTwoHopCandidates(eq(VIEWER), anyInt()))
                .thenReturn(List.of(GRAPH_ONE, GRAPH_TWO));
        when(userSuggestionRepository.findAffinityCandidates(eq(VIEWER), anyInt(), anyInt()))
                .thenReturn(List.of());

        service.rebuildFor(VIEWER);

        ArgumentCaptor<OffsetDateTime> stamped = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> sweptFrom = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(userSuggestionRepository, times(2))
                .upsertSuggestion(eq(VIEWER), any(), anyShort(), any(), any(), stamped.capture());
        verify(userSuggestionRepository).deleteStaleFor(eq(VIEWER), sweptFrom.capture());

        assertThat(stamped.getAllValues()).containsOnly(sweptFrom.getValue());
    }

    @Test
    void rebuildFor_reportsTheRowsThatSurvivedTheSweepRatherThanTheRowsItWrote() {
        // Reporting ordered.size() is what made the wipe invisible in the log. The count now comes
        // from the table after the sweep, so a run that deletes its own rows reports zero.
        when(gorseNeighbourSource.neighbours(eq(VIEWER), anyInt())).thenReturn(List.of());
        when(userSuggestionRepository.findTwoHopCandidates(eq(VIEWER), anyInt()))
                .thenReturn(List.of(GRAPH_ONE, GRAPH_TWO));
        when(userSuggestionRepository.findAffinityCandidates(eq(VIEWER), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(userSuggestionRepository.countFreshFor(eq(VIEWER), any())).thenReturn(0);

        assertThat(service.rebuildFor(VIEWER)).isZero();
    }

    @Test
    void rebuildFor_allSourcesEmpty_writesNothingAndClearsTheStaleGeneration() {
        when(gorseNeighbourSource.neighbours(eq(VIEWER), anyInt())).thenReturn(List.of());
        when(userSuggestionRepository.findTwoHopCandidates(eq(VIEWER), anyInt()))
                .thenReturn(List.of());
        when(userSuggestionRepository.findAffinityCandidates(eq(VIEWER), anyInt(), anyInt()))
                .thenReturn(List.of());

        assertThat(service.rebuildFor(VIEWER)).isZero();
        verify(userSuggestionRepository, never())
                .upsertSuggestion(any(), any(), anyShort(), any(), anyString(), any());
        verify(userSuggestionRepository).deleteStaleFor(eq(VIEWER), any());
    }

    @Test
    void rebuildFor_candidateInEverySource_outranksOneInASingleSource() {
        // Rank fusion, not score addition: an account all three sources agree on must lead one that
        // only the graph names, whatever units those sources measure in.
        when(gorseNeighbourSource.neighbours(eq(VIEWER), anyInt())).thenReturn(List.of(GRAPH_TWO));
        when(userSuggestionRepository.findTwoHopCandidates(eq(VIEWER), anyInt()))
                .thenReturn(List.of(GRAPH_ONE, GRAPH_TWO));
        when(userSuggestionRepository.findAffinityCandidates(eq(VIEWER), anyInt(), anyInt()))
                .thenReturn(List.of(GRAPH_TWO));

        service.rebuildFor(VIEWER);

        ArgumentCaptor<UUID> ids = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Short> ranks = ArgumentCaptor.forClass(Short.class);
        verify(userSuggestionRepository, times(2))
                .upsertSuggestion(
                        eq(VIEWER),
                        ids.capture(),
                        ranks.capture(),
                        any(BigDecimal.class),
                        any(),
                        any());
        int indexOfShared = ids.getAllValues().indexOf(GRAPH_TWO);
        assertThat(ranks.getAllValues().get(indexOfShared)).isEqualTo((short) 1);
    }

    @Test
    void suggestionsFor_precomputedListEmpty_fallsBackToVerifiedColdStart() {
        when(userSuggestionRepository.findVisibleSuggestions(eq(VIEWER), anyInt()))
                .thenReturn(List.of());
        when(userSuggestionRepository.findVerifiedColdStart(eq(VIEWER), anyInt()))
                .thenReturn(List.of(VERIFIED_ONE));
        when(userSummaryService.loadSummaries(List.of(VERIFIED_ONE)))
                .thenReturn(
                        Map.of(
                                VERIFIED_ONE,
                                new UserSummaryResponse(
                                        VERIFIED_ONE, "star", "Star", null, true, "music")));
        when(socialService.loadRelationships(VIEWER, List.of(VERIFIED_ONE))).thenReturn(Map.of());

        var rows = service.suggestionsFor(VIEWER, 5);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).user().isVerified()).isTrue();
        assertThat(rows.get(0).user().verifiedCategory()).isEqualTo("music");
        assertThat(rows.get(0).viewerState()).isEqualTo(ViewerRelationshipResponse.NONE);
    }

    @Test
    void suggestionsFor_precomputedListPresent_doesNotReachForColdStart() {
        when(userSuggestionRepository.findVisibleSuggestions(eq(VIEWER), anyInt()))
                .thenReturn(List.of(GRAPH_ONE));
        when(userSummaryService.loadSummaries(List.of(GRAPH_ONE)))
                .thenReturn(
                        Map.of(
                                GRAPH_ONE,
                                new UserSummaryResponse(
                                        GRAPH_ONE, "friend", "Friend", null, false, null)));
        when(socialService.loadRelationships(VIEWER, List.of(GRAPH_ONE))).thenReturn(Map.of());

        assertThat(service.suggestionsFor(VIEWER, 5)).hasSize(1);
        verify(userSuggestionRepository, never()).findVerifiedColdStart(any(), anyInt());
    }

    @Test
    void dismiss_self_isIgnoredRatherThanWritten() {
        service.dismiss(VIEWER, VIEWER);
        verify(suggestionDismissalRepository, never()).dismiss(any(), any());
    }

    @Test
    void dismiss_otherAccount_isRecorded() {
        service.dismiss(VIEWER, GRAPH_ONE);
        verify(suggestionDismissalRepository).dismiss(VIEWER, GRAPH_ONE);
    }
}
