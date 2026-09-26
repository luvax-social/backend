package com.app.modules.recommendation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.response.UserSummaryResponse;
import com.app.common.response.ViewerRelationshipResponse;
import com.app.modules.recommendation.dto.response.SuggestedUserResponse;
import com.app.modules.recommendation.repository.SuggestionDismissalRepository;
import com.app.modules.recommendation.repository.UserSuggestionRepository;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.service.UserSummaryService;

/**
 * The suggestion row carries the banner and the reason labels, because the in-feed card renders
 * both and neither is on the shared {@link UserSummaryResponse}.
 */
@ExtendWith(MockitoExtension.class)
class SuggestionRowMappingTest {

    private static final UUID VIEWER = UUID.randomUUID();
    private static final UUID CANDIDATE = UUID.randomUUID();

    @Mock private UserSuggestionRepository userSuggestionRepository;
    @Mock private SuggestionDismissalRepository suggestionDismissalRepository;
    @Mock private GorseNeighbourSource gorseNeighbourSource;
    @Mock private UserSummaryService userSummaryService;
    @Mock private SocialService socialService;

    private SuggestionServiceImpl service;

    /**
     * One projection row.
     *
     * <p>Built through a list rather than {@code List.of(new Object[] {...})}, which infers {@code
     * List<Object>} and spreads the array across two elements instead of holding it as one row.
     */
    private static List<Object[]> oneRow(UUID id, String value) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {id, value});
        return rows;
    }

    /** One profile-card projection row: [id, banner_url, follower_count]. */
    private static List<Object[]> oneCardRow(UUID id, String banner, int followerCount) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {id, banner, followerCount});
        return rows;
    }

    @BeforeEach
    void setUp() {
        service =
                new SuggestionServiceImpl(
                        userSuggestionRepository,
                        suggestionDismissalRepository,
                        gorseNeighbourSource,
                        userSummaryService,
                        socialService);
        when(userSuggestionRepository.findVisibleSuggestions(any(), anyInt()))
                .thenReturn(List.of(CANDIDATE));
        when(userSummaryService.loadSummaries(List.of(CANDIDATE)))
                .thenReturn(
                        Map.of(
                                CANDIDATE,
                                new UserSummaryResponse(CANDIDATE, "nadia", "Nadia", null, false)));
        when(socialService.loadRelationships(VIEWER, List.of(CANDIDATE)))
                .thenReturn(Map.of(CANDIDATE, ViewerRelationshipResponse.NONE));
    }

    @Test
    void suggestionsFor_accountHasBanner_carriesBannerUrl() {
        when(userSuggestionRepository.findProfileCardFields(List.of(CANDIDATE)))
                .thenReturn(oneCardRow(CANDIDATE, "https://cdn.example/banner.jpg", 1240));
        when(userSuggestionRepository.findSourcesFor(VIEWER, List.of(CANDIDATE)))
                .thenReturn(oneRow(CANDIDATE, "graph"));

        List<SuggestedUserResponse> rows = service.suggestionsFor(VIEWER, 10);

        assertThat(rows)
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.user().id()).isEqualTo(CANDIDATE);
                            assertThat(row.bannerUrl()).isEqualTo("https://cdn.example/banner.jpg");
                        });
    }

    @Test
    void suggestionsFor_accountHasNoBanner_reportsNullBanner() {
        when(userSuggestionRepository.findProfileCardFields(List.of(CANDIDATE)))
                .thenReturn(oneCardRow(CANDIDATE, null, 7));
        when(userSuggestionRepository.findSourcesFor(VIEWER, List.of(CANDIDATE)))
                .thenReturn(oneRow(CANDIDATE, "graph"));

        List<SuggestedUserResponse> rows = service.suggestionsFor(VIEWER, 10);

        assertThat(rows).singleElement().satisfies(row -> assertThat(row.bannerUrl()).isNull());
    }

    @Test
    void suggestionsFor_severalSourceLabels_carriesThemAll() {
        when(userSuggestionRepository.findProfileCardFields(List.of(CANDIDATE)))
                .thenReturn(oneCardRow(CANDIDATE, null, 7));
        when(userSuggestionRepository.findSourcesFor(VIEWER, List.of(CANDIDATE)))
                .thenReturn(oneRow(CANDIDATE, "affinity,graph"));

        List<SuggestedUserResponse> rows = service.suggestionsFor(VIEWER, 10);

        assertThat(rows)
                .singleElement()
                .satisfies(row -> assertThat(row.sources()).isEqualTo("affinity,graph"));
    }

    @Test
    void suggestionsFor_anyRow_carriesTheFollowerCount() {
        when(userSuggestionRepository.findProfileCardFields(List.of(CANDIDATE)))
                .thenReturn(oneCardRow(CANDIDATE, null, 1240));
        when(userSuggestionRepository.findSourcesFor(VIEWER, List.of(CANDIDATE)))
                .thenReturn(oneRow(CANDIDATE, "graph"));

        List<SuggestedUserResponse> rows = service.suggestionsFor(VIEWER, 10);

        assertThat(rows)
                .singleElement()
                .satisfies(row -> assertThat(row.followerCount()).isEqualTo(1240));
    }

    @Test
    void suggestionsFor_coldStartRow_reportsNullSources() {
        when(userSuggestionRepository.findProfileCardFields(List.of(CANDIDATE)))
                .thenReturn(oneCardRow(CANDIDATE, null, 7));
        // No row at all: a cold-start candidate was never precomputed, so no reason was recorded.
        when(userSuggestionRepository.findSourcesFor(VIEWER, List.of(CANDIDATE)))
                .thenReturn(List.of());

        List<SuggestedUserResponse> rows = service.suggestionsFor(VIEWER, 10);

        assertThat(rows).singleElement().satisfies(row -> assertThat(row.sources()).isNull());
    }
}
