package com.app.modules.recommendation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.response.CursorPageResponse;
import com.app.modules.post.dto.response.FeedPostResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.enums.FeedSurface;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.enums.PostType;
import com.app.modules.post.service.PostFeedbackService;
import com.app.modules.post.service.PostLookupService;
import com.app.modules.post.service.PostService;
import com.app.modules.post.service.PostVisibilityService;
import com.app.modules.recommendation.client.dto.GorseScore;
import com.app.modules.recommendation.config.GorseProperties;
import com.app.modules.recommendation.observability.RecommendationMetrics;
import com.app.modules.recommendation.service.impl.feed.RecommendationSource;
import com.app.modules.recommendation.service.impl.feed.RecommendationSource.SourceBatch;
import com.app.modules.social.service.SocialService;

@ExtendWith(MockitoExtension.class)
class RecommendationFeedServiceImplTest {

    @Mock private RecommendationSource recommendationSource;
    @Mock private PostLookupService postLookupService;
    @Mock private PostVisibilityService postVisibilityService;
    @Mock private PostFeedbackService postFeedbackService;
    @Mock private PostService postService;
    @Mock private SocialService socialService;
    @Mock private RecommendationMetrics recommendationMetrics;

    private GorseProperties gorseProperties;
    private RecommendationFeedServiceImpl service;

    private final UUID viewerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        gorseProperties = new GorseProperties();
        gorseProperties.setRecommendMultiplier(2);
        service =
                new RecommendationFeedServiceImpl(
                        recommendationSource,
                        postLookupService,
                        postVisibilityService,
                        postFeedbackService,
                        postService,
                        socialService,
                        gorseProperties,
                        recommendationMetrics);
        // Feed assembly is a pass-through in these tests; only ranking-score attachment matters.
        // Fallback-path tests never reach the assembler, so this stub is lenient.
        lenient()
                .when(postLookupService.assembleFeed(any(UUID.class), anyList()))
                .thenAnswer(
                        inv -> {
                            List<Post> posts = inv.getArgument(1);
                            return posts.stream().map(p -> feedResponse(p.getId())).toList();
                        });
        // Only the excludeFollowed tests care about this; every other test passes false and never
        // reaches the call.
        lenient()
                .when(socialService.getAcceptedFollowingExcludingBlocks(viewerId))
                .thenReturn(List.of());
    }

    private Post publishedPost(UUID id, UUID ownerId) {
        return Post.builder().id(id).userId(ownerId).status(PostStatus.PUBLISHED).build();
    }

    private FeedPostResponse feedResponse(UUID id) {
        return new FeedPostResponse(
                id,
                null,
                "caption",
                PostType.TEXT,
                PostStatus.PUBLISHED,
                0,
                0,
                0,
                false,
                false,
                false,
                0,
                null,
                null,
                null,
                List.of(),
                List.of(),
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                null);
    }

    private static String decodeCursor(String cursor) {
        return new String(Base64.getDecoder().decode(cursor));
    }

    @Test
    void getRecommendedFeed_nullCursor_fetchesGorseFromOffsetZero() {
        // limit=1 so the single returned candidate fills the page exactly - otherwise the
        // pipeline would fetch a second round that this test does not stub.
        UUID postId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Post post = publishedPost(postId, ownerId);
        when(recommendationSource.fetch(eq(viewerId), eq('g'), eq(2), eq(0), eq(0)))
                .thenReturn(
                        new SourceBatch(
                                'g', List.of(new GorseScore(postId.toString(), 5.0)), 1, 0));
        when(postLookupService.findActiveByIds(anyList())).thenReturn(List.of(post));
        when(postVisibilityService.filterVisibleOwnerIds(viewerId, Set.of(ownerId)))
                .thenReturn(Set.of(ownerId));

        CursorPageResponse<FeedPostResponse> page =
                service.getRecommendedFeed(viewerId, null, 1, false);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).rankingScore()).isEqualTo(5.0);
        verify(recommendationSource).fetch(viewerId, 'g', 2, 0, 0);
        verify(postService, never()).getFeed(any(), any(), anyInt(), any());
    }

    @Test
    void getRecommendedFeed_malformedRankedCursor_delegatesToChronologicalFeedUnchanged() {
        String garbageCursor = "not-a-valid-ranked-cursor!!!";
        CursorPageResponse<FeedPostResponse> chronoPage =
                CursorPageResponse.of(List.of(), false, null, null, true);
        when(postService.getFeed(viewerId, garbageCursor, 20, FeedSurface.FOR_YOU_FALLBACK))
                .thenReturn(chronoPage);

        CursorPageResponse<FeedPostResponse> page =
                service.getRecommendedFeed(viewerId, garbageCursor, 20, false);

        assertThat(page).isSameAs(chronoPage);
        verify(recommendationSource, never()).fetch(any(), anyChar(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void getRecommendedFeed_ownedAndInvisiblePostsFiltered_secondRoundFillsPage() {
        UUID ownedByViewer = UUID.randomUUID();
        UUID blockedPost = UUID.randomUUID();
        UUID okPost1 = UUID.randomUUID();
        UUID okPost2 = UUID.randomUUID();
        UUID blockedOwnerId = UUID.randomUUID();
        UUID ok1OwnerId = UUID.randomUUID();
        UUID ok2OwnerId = UUID.randomUUID();
        Post viewerOwned = publishedPost(ownedByViewer, viewerId);
        Post blocked = publishedPost(blockedPost, blockedOwnerId);
        Post ok1 = publishedPost(okPost1, ok1OwnerId);
        Post ok2 = publishedPost(okPost2, ok2OwnerId);

        // First round: 3 candidates, but only ok1 survives (owned + blocked filtered) - page of 2
        // is not yet full, so a second round must run. All 3 are primary (no topup), so gorseOffset
        // advances by all 3 and trendingOffset stays at 0.
        when(recommendationSource.fetch(eq(viewerId), eq('g'), eq(4), eq(0), eq(0)))
                .thenReturn(
                        new SourceBatch(
                                'g',
                                List.of(
                                        new GorseScore(ownedByViewer.toString(), 9.0),
                                        new GorseScore(blockedPost.toString(), 8.0),
                                        new GorseScore(okPost1.toString(), 7.0)),
                                3,
                                0));
        when(postLookupService.findActiveByIds(List.of(ownedByViewer, blockedPost, okPost1)))
                .thenReturn(List.of(viewerOwned, blocked, ok1));
        // The batch visibility check runs once for the round's distinct owner set; blockedOwnerId
        // is excluded from the visible result, ok1OwnerId (and the viewer's own id) is included.
        when(postVisibilityService.filterVisibleOwnerIds(
                        viewerId, Set.of(viewerId, blockedOwnerId, ok1OwnerId)))
                .thenReturn(Set.of(viewerId, ok1OwnerId));

        // Second round starts at gorseOffset 3 (all 3 raw candidates from round 1 were consumed);
        // trendingOffset stays 0 since topup was never reached.
        when(recommendationSource.fetch(eq(viewerId), eq('g'), eq(4), eq(3), eq(0)))
                .thenReturn(
                        new SourceBatch(
                                'g', List.of(new GorseScore(okPost2.toString(), 6.0)), 1, 0));
        when(postLookupService.findActiveByIds(List.of(okPost2))).thenReturn(List.of(ok2));
        when(postVisibilityService.filterVisibleOwnerIds(viewerId, Set.of(ok2OwnerId)))
                .thenReturn(Set.of(ok2OwnerId));

        CursorPageResponse<FeedPostResponse> page =
                service.getRecommendedFeed(viewerId, null, 2, false);

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).id()).isEqualTo(okPost1);
        assertThat(page.getContent().get(1).id()).isEqualTo(okPost2);
        verify(recommendationSource).fetch(viewerId, 'g', 4, 0, 0);
        verify(recommendationSource).fetch(viewerId, 'g', 4, 3, 0);
    }

    @Test
    void getRecommendedFeed_bothSourcesEmptyOnFirstPage_fallsBackToChronologicalFeed() {
        when(recommendationSource.fetch(eq(viewerId), eq('g'), eq(10), eq(0), eq(0)))
                .thenReturn(new SourceBatch('g', List.of(), 0, 0));
        CursorPageResponse<FeedPostResponse> chronoPage =
                CursorPageResponse.of(
                        List.of(feedResponse(UUID.randomUUID())), false, "a", "b", false);
        when(postService.getFeed(viewerId, null, 5, FeedSurface.FOR_YOU_FALLBACK))
                .thenReturn(chronoPage);

        CursorPageResponse<FeedPostResponse> page =
                service.getRecommendedFeed(viewerId, null, 5, false);

        assertThat(page).isSameAs(chronoPage);
        verify(postLookupService, never()).findActiveByIds(anyList());
        verify(recommendationMetrics).chronologicalFallback();
    }

    @Test
    void getRecommendedFeed_roundConsumesCandidatesAcceptsNone_recordsZeroAcceptRoundMetric() {
        // A single round returns two real candidates, but both fail to resolve to a live post -
        // the id-drift failure mode this metric exists to surface. The pipeline then exhausts its
        // remaining rounds (each stubbed the same way) and falls back to chronological.
        UUID staleId1 = UUID.randomUUID();
        UUID staleId2 = UUID.randomUUID();
        when(recommendationSource.fetch(eq(viewerId), eq('g'), eq(10), anyInt(), eq(0)))
                .thenReturn(
                        new SourceBatch(
                                'g',
                                List.of(
                                        new GorseScore(staleId1.toString(), 9.0),
                                        new GorseScore(staleId2.toString(), 8.0)),
                                2,
                                0));
        when(postLookupService.findActiveByIds(anyList())).thenReturn(List.of());
        CursorPageResponse<FeedPostResponse> chronoPage =
                CursorPageResponse.of(List.of(), false, null, null, false);
        when(postService.getFeed(viewerId, null, 5, FeedSurface.FOR_YOU_FALLBACK))
                .thenReturn(chronoPage);

        CursorPageResponse<FeedPostResponse> page =
                service.getRecommendedFeed(viewerId, null, 5, false);

        assertThat(page).isSameAs(chronoPage);
        // MAX_SOURCE_ROUNDS = 5, and every round in this test accepts nothing.
        verify(recommendationMetrics, times(5)).zeroAcceptRound();
        verify(recommendationMetrics).chronologicalFallback();
    }

    @Test
    void getRecommendedFeed_sourceDegradesToPopular_cursorReflectsPopularSource() {
        UUID postId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Post post = publishedPost(postId, ownerId);
        // Gorse degraded mid-round-trip: RecommendationSource itself flips the tagged source.
        when(recommendationSource.fetch(eq(viewerId), eq('g'), eq(2), eq(0), eq(0)))
                .thenReturn(
                        new SourceBatch(
                                'p', List.of(new GorseScore(postId.toString(), null)), 1, 0));
        when(postLookupService.findActiveByIds(anyList())).thenReturn(List.of(post));
        when(postVisibilityService.filterVisibleOwnerIds(viewerId, Set.of(ownerId)))
                .thenReturn(Set.of(ownerId));

        CursorPageResponse<FeedPostResponse> page =
                service.getRecommendedFeed(viewerId, null, 1, false);

        assertThat(page.getContent()).hasSize(1);
        assertThat(decodeCursor(page.getPageInfo().getEndCursor())).startsWith("p:");
    }

    @Test
    void getRecommendedFeed_gorseReturnsNoScore_rankingScoreIsNull() {
        UUID postId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Post post = publishedPost(postId, ownerId);
        when(recommendationSource.fetch(eq(viewerId), eq('g'), eq(2), eq(0), eq(0)))
                .thenReturn(
                        new SourceBatch(
                                'g', List.of(new GorseScore(postId.toString(), null)), 1, 0));
        when(postLookupService.findActiveByIds(anyList())).thenReturn(List.of(post));
        when(postVisibilityService.filterVisibleOwnerIds(viewerId, Set.of(ownerId)))
                .thenReturn(Set.of(ownerId));

        CursorPageResponse<FeedPostResponse> page =
                service.getRecommendedFeed(viewerId, null, 1, false);

        assertThat(page.getContent().get(0).rankingScore()).isNull();
    }

    @Test
    void getRecommendedFeed_previousPagePopularCursor_continuesFromPopularSource() {
        String popularCursor = Base64.getEncoder().encodeToString("p:7:0".getBytes());
        when(recommendationSource.fetch(eq(viewerId), eq('p'), eq(10), eq(7), eq(0)))
                .thenReturn(new SourceBatch('p', List.of(), 0, 0));

        // Not the first page (offset 7 > 0), so an empty result returns as an empty ranked page
        // rather than restarting the chronological feed from page one.
        CursorPageResponse<FeedPostResponse> page =
                service.getRecommendedFeed(viewerId, popularCursor, 5, false);

        assertThat(page.getContent()).isEmpty();
        verify(postService, never()).getFeed(any(), any(), anyInt(), any());
    }

    @Test
    void getRecommendedFeed_topupReached_advancesGorseAndTrendingOffsetsIndependently() {
        UUID gorseOnlyPost = UUID.randomUUID();
        UUID gorseOnlyOwner = UUID.randomUUID();
        UUID topUpPost = UUID.randomUUID();
        UUID topUpOwner = UUID.randomUUID();
        Post gorsePost = publishedPost(gorseOnlyPost, gorseOnlyOwner);
        Post topUpPostEntity = publishedPost(topUpPost, topUpOwner);
        // Batch has 1 primary (gorse) entry and 1 topup entry; both get consumed filling the
        // page, so the topup portion is reached and its whole fetched chunk (3) is spent, while
        // gorseOffset only advances by the 1 primary entry actually consumed. fetchSize for
        // limit=2 with multiplier 2 is 4 - gorse and topup together fell short of it, which is
        // exactly the exhaustion case topup exists for.
        when(recommendationSource.fetch(eq(viewerId), eq('g'), eq(4), eq(0), eq(0)))
                .thenReturn(
                        new SourceBatch(
                                'g',
                                List.of(
                                        new GorseScore(gorseOnlyPost.toString(), 9.0),
                                        new GorseScore(topUpPost.toString(), 5.0)),
                                1,
                                3));
        when(postLookupService.findActiveByIds(anyList()))
                .thenReturn(List.of(gorsePost, topUpPostEntity));
        when(postVisibilityService.filterVisibleOwnerIds(
                        viewerId, Set.of(gorseOnlyOwner, topUpOwner)))
                .thenReturn(Set.of(gorseOnlyOwner, topUpOwner));

        CursorPageResponse<FeedPostResponse> page =
                service.getRecommendedFeed(viewerId, null, 2, false);

        assertThat(page.getContent()).hasSize(2);
        assertThat(decodeCursor(page.getPageInfo().getEndCursor())).isEqualTo("g:1:3");
    }

    @Test
    void getRecommendedFeed_topupFetchedButNeverReached_trendingOffsetDoesNotAdvance() {
        UUID onlyPost = UUID.randomUUID();
        UUID onlyOwner = UUID.randomUUID();
        Post post = publishedPost(onlyPost, onlyOwner);
        // primaryCount 1 equals scores.size() consumed (page fills on the single primary entry),
        // so the topup portion carried in the batch - if any - is never reached and
        // trendingOffset must stay put even though the batch reports a non-zero chunk fetched.
        // fetchSize for limit=1 with multiplier 2 is 2.
        when(recommendationSource.fetch(eq(viewerId), eq('g'), eq(2), eq(0), eq(0)))
                .thenReturn(
                        new SourceBatch(
                                'g', List.of(new GorseScore(onlyPost.toString(), 9.0)), 1, 4));
        when(postLookupService.findActiveByIds(anyList())).thenReturn(List.of(post));
        when(postVisibilityService.filterVisibleOwnerIds(viewerId, Set.of(onlyOwner)))
                .thenReturn(Set.of(onlyOwner));

        CursorPageResponse<FeedPostResponse> page =
                service.getRecommendedFeed(viewerId, null, 1, false);

        assertThat(page.getContent()).hasSize(1);
        assertThat(decodeCursor(page.getPageInfo().getEndCursor())).isEqualTo("g:1:0");
    }

    @Test
    void getRecommendedFeed_excludeFollowed_removesFollowedOwnersCandidates() {
        UUID followedOwnerPost = UUID.randomUUID();
        UUID otherOwnerPost = UUID.randomUUID();
        UUID followedOwnerId = UUID.randomUUID();
        UUID otherOwnerId = UUID.randomUUID();
        Post followedPost = publishedPost(followedOwnerPost, followedOwnerId);
        Post otherPost = publishedPost(otherOwnerPost, otherOwnerId);
        when(socialService.getAcceptedFollowingExcludingBlocks(viewerId))
                .thenReturn(List.of(followedOwnerId));
        // fetchSize for limit=2 with multiplier 2 is 4.
        when(recommendationSource.fetch(eq(viewerId), eq('g'), eq(4), eq(0), eq(0)))
                .thenReturn(
                        new SourceBatch(
                                'g',
                                List.of(
                                        new GorseScore(followedOwnerPost.toString(), 9.0),
                                        new GorseScore(otherOwnerPost.toString(), 8.0)),
                                2,
                                0));
        when(postLookupService.findActiveByIds(anyList()))
                .thenReturn(List.of(followedPost, otherPost));
        when(postVisibilityService.filterVisibleOwnerIds(
                        viewerId, Set.of(followedOwnerId, otherOwnerId)))
                .thenReturn(Set.of(followedOwnerId, otherOwnerId));
        // Excluding the followed post leaves the page short of its limit of 2, so a second round
        // runs at the advanced gorseOffset; an empty batch ends it there.
        when(recommendationSource.fetch(eq(viewerId), eq('g'), eq(4), eq(2), eq(0)))
                .thenReturn(new SourceBatch('g', List.of(), 0, 0));

        CursorPageResponse<FeedPostResponse> page =
                service.getRecommendedFeed(viewerId, null, 2, true);

        assertThat(page.getContent())
                .extracting(FeedPostResponse::id)
                .containsExactly(otherOwnerPost);
    }

    @Test
    void getRecommendedFeed_notInterestedAndSuppressedAuthorCandidates_areDroppedFromTheRound() {
        UUID keptPost = UUID.randomUUID();
        UUID notInterestedPost = UUID.randomUUID();
        UUID suppressedAuthorPost = UUID.randomUUID();
        UUID keptOwner = UUID.randomUUID();
        UUID notInterestedOwner = UUID.randomUUID();
        UUID suppressedOwner = UUID.randomUUID();
        when(recommendationSource.fetch(eq(viewerId), eq('g'), eq(4), eq(0), eq(0)))
                .thenReturn(
                        new SourceBatch(
                                'g',
                                List.of(
                                        new GorseScore(notInterestedPost.toString(), 9.0),
                                        new GorseScore(suppressedAuthorPost.toString(), 8.0),
                                        new GorseScore(keptPost.toString(), 7.0)),
                                3,
                                0));
        when(postLookupService.findActiveByIds(anyList()))
                .thenReturn(
                        List.of(
                                publishedPost(notInterestedPost, notInterestedOwner),
                                publishedPost(suppressedAuthorPost, suppressedOwner),
                                publishedPost(keptPost, keptOwner)));
        when(postVisibilityService.filterVisibleOwnerIds(any(), any()))
                .thenReturn(Set.of(keptOwner, notInterestedOwner, suppressedOwner));
        when(postFeedbackService.findNotInterested(eq(viewerId), any()))
                .thenReturn(Set.of(notInterestedPost));
        when(socialService.findFeedSuppressedAuthors(eq(viewerId), any(), eq(FeedSurface.FOR_YOU)))
                .thenReturn(Set.of(suppressedOwner));
        // The page of 2 is not full after the round, so a second one runs and finds nothing.
        when(recommendationSource.fetch(eq(viewerId), eq('g'), eq(4), eq(3), eq(0)))
                .thenReturn(new SourceBatch('g', List.of(), 0, 0));

        CursorPageResponse<FeedPostResponse> page =
                service.getRecommendedFeed(viewerId, null, 2, false);

        assertThat(page.getContent()).extracting(FeedPostResponse::id).containsExactly(keptPost);
        // Dropped candidates are still consumed, so the next page does not read them again.
        assertThat(decodeCursor(page.getPageInfo().getEndCursor())).isEqualTo("g:3:0");
    }

    @Test
    void getRecommendedFeed_preferenceLookups_runOncePerRoundOverTheWholeCandidateSet() {
        UUID postA = UUID.randomUUID();
        UUID postB = UUID.randomUUID();
        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        when(recommendationSource.fetch(eq(viewerId), eq('g'), eq(4), eq(0), eq(0)))
                .thenReturn(
                        new SourceBatch(
                                'g',
                                List.of(
                                        new GorseScore(postA.toString(), 9.0),
                                        new GorseScore(postB.toString(), 8.0)),
                                2,
                                0));
        when(postLookupService.findActiveByIds(anyList()))
                .thenReturn(List.of(publishedPost(postA, ownerA), publishedPost(postB, ownerB)));
        when(postVisibilityService.filterVisibleOwnerIds(any(), any()))
                .thenReturn(Set.of(ownerA, ownerB));

        service.getRecommendedFeed(viewerId, null, 2, false);

        verify(postFeedbackService, times(1)).findNotInterested(viewerId, Set.of(postA, postB));
        verify(socialService, times(1))
                .findFeedSuppressedAuthors(viewerId, Set.of(ownerA, ownerB), FeedSurface.FOR_YOU);
    }

    @Test
    void getRecommendedFeed_trendingTopUpCandidates_areFilteredLikeThePrimaryOnes() {
        UUID primaryPost = UUID.randomUUID();
        UUID topUpKept = UUID.randomUUID();
        UUID topUpNotInterested = UUID.randomUUID();
        UUID topUpSuppressed = UUID.randomUUID();
        UUID primaryOwner = UUID.randomUUID();
        UUID keptOwner = UUID.randomUUID();
        UUID notInterestedOwner = UUID.randomUUID();
        UUID suppressedOwner = UUID.randomUUID();
        // One primary candidate followed by three that came from the trending topup.
        when(recommendationSource.fetch(eq(viewerId), eq('g'), eq(6), eq(0), eq(0)))
                .thenReturn(
                        new SourceBatch(
                                'g',
                                List.of(
                                        new GorseScore(primaryPost.toString(), 9.0),
                                        new GorseScore(topUpNotInterested.toString(), 5.0),
                                        new GorseScore(topUpSuppressed.toString(), 4.0),
                                        new GorseScore(topUpKept.toString(), 3.0)),
                                1,
                                10));
        when(postLookupService.findActiveByIds(anyList()))
                .thenReturn(
                        List.of(
                                publishedPost(primaryPost, primaryOwner),
                                publishedPost(topUpNotInterested, notInterestedOwner),
                                publishedPost(topUpSuppressed, suppressedOwner),
                                publishedPost(topUpKept, keptOwner)));
        when(postVisibilityService.filterVisibleOwnerIds(any(), any()))
                .thenReturn(Set.of(primaryOwner, keptOwner, notInterestedOwner, suppressedOwner));
        when(postFeedbackService.findNotInterested(eq(viewerId), any()))
                .thenReturn(Set.of(topUpNotInterested));
        when(socialService.findFeedSuppressedAuthors(eq(viewerId), any(), eq(FeedSurface.FOR_YOU)))
                .thenReturn(Set.of(suppressedOwner));
        // Four candidates were consumed, three past the primary one, so the topup chunk is spent.
        when(recommendationSource.fetch(eq(viewerId), eq('g'), eq(6), eq(1), eq(10)))
                .thenReturn(new SourceBatch('g', List.of(), 0, 0));

        CursorPageResponse<FeedPostResponse> page =
                service.getRecommendedFeed(viewerId, null, 3, false);

        assertThat(page.getContent())
                .extracting(FeedPostResponse::id)
                .containsExactly(primaryPost, topUpKept);
    }

    @Test
    void getRecommendedFeed_popularityFallbackCandidates_areFilteredLikeTheRankedOnes() {
        String popularCursor = Base64.getEncoder().encodeToString("p:0:0".getBytes());
        UUID keptPost = UUID.randomUUID();
        UUID notInterestedPost = UUID.randomUUID();
        UUID suppressedPost = UUID.randomUUID();
        UUID keptOwner = UUID.randomUUID();
        UUID notInterestedOwner = UUID.randomUUID();
        UUID suppressedOwner = UUID.randomUUID();
        when(recommendationSource.fetch(eq(viewerId), eq('p'), eq(6), eq(0), eq(0)))
                .thenReturn(
                        new SourceBatch(
                                'p',
                                List.of(
                                        new GorseScore(notInterestedPost.toString(), 9.0),
                                        new GorseScore(suppressedPost.toString(), 8.0),
                                        new GorseScore(keptPost.toString(), 7.0)),
                                3,
                                0));
        when(postLookupService.findActiveByIds(anyList()))
                .thenReturn(
                        List.of(
                                publishedPost(notInterestedPost, notInterestedOwner),
                                publishedPost(suppressedPost, suppressedOwner),
                                publishedPost(keptPost, keptOwner)));
        when(postVisibilityService.filterVisibleOwnerIds(any(), any()))
                .thenReturn(Set.of(keptOwner, notInterestedOwner, suppressedOwner));
        when(postFeedbackService.findNotInterested(eq(viewerId), any()))
                .thenReturn(Set.of(notInterestedPost));
        when(socialService.findFeedSuppressedAuthors(eq(viewerId), any(), eq(FeedSurface.FOR_YOU)))
                .thenReturn(Set.of(suppressedOwner));
        // The page of 3 is not full after the round, so a second one runs and finds nothing.
        when(recommendationSource.fetch(eq(viewerId), eq('p'), eq(6), eq(3), eq(0)))
                .thenReturn(new SourceBatch('p', List.of(), 0, 0));

        CursorPageResponse<FeedPostResponse> page =
                service.getRecommendedFeed(viewerId, popularCursor, 3, false);

        assertThat(page.getContent()).extracting(FeedPostResponse::id).containsExactly(keptPost);
    }

    @Test
    void getRecommendedFeed_excludeFollowed_appliesTheForYouPreferences() {
        UUID keptPost = UUID.randomUUID();
        UUID suppressedPost = UUID.randomUUID();
        UUID keptOwner = UUID.randomUUID();
        UUID suppressedOwner = UUID.randomUUID();
        when(recommendationSource.fetch(eq(viewerId), eq('g'), eq(4), eq(0), eq(0)))
                .thenReturn(
                        new SourceBatch(
                                'g',
                                List.of(
                                        new GorseScore(suppressedPost.toString(), 9.0),
                                        new GorseScore(keptPost.toString(), 8.0)),
                                2,
                                0));
        when(postLookupService.findActiveByIds(anyList()))
                .thenReturn(
                        List.of(
                                publishedPost(suppressedPost, suppressedOwner),
                                publishedPost(keptPost, keptOwner)));
        when(postVisibilityService.filterVisibleOwnerIds(any(), any()))
                .thenReturn(Set.of(keptOwner, suppressedOwner));
        when(socialService.findFeedSuppressedAuthors(eq(viewerId), any(), eq(FeedSurface.FOR_YOU)))
                .thenReturn(Set.of(suppressedOwner));
        when(recommendationSource.fetch(eq(viewerId), eq('g'), eq(4), eq(2), eq(0)))
                .thenReturn(new SourceBatch('g', List.of(), 0, 0));

        CursorPageResponse<FeedPostResponse> page =
                service.getRecommendedFeed(viewerId, null, 2, true);

        assertThat(page.getContent()).extracting(FeedPostResponse::id).containsExactly(keptPost);
    }

    @Test
    void
            getRecommendedFeed_excludeFollowedAndBothSourcesEmpty_returnsEmptyPageInsteadOfFollowingFeed() {
        when(recommendationSource.fetch(eq(viewerId), eq('g'), eq(10), eq(0), eq(0)))
                .thenReturn(new SourceBatch('g', List.of(), 0, 0));

        CursorPageResponse<FeedPostResponse> page =
                service.getRecommendedFeed(viewerId, null, 5, true);

        assertThat(page.getContent()).isEmpty();
        verify(postService, never()).getFeed(any(), any(), anyInt(), any());
        // The chronological fallback is a distinct, real feed being served in place of the ranked
        // one; an honest empty Explore page under excludeFollowed is a different outcome and must
        // not be counted the same way.
        verify(recommendationMetrics, never()).chronologicalFallback();
    }

    @Test
    void
            getRecommendedFeed_excludeFollowedWithMalformedCursor_returnsEmptyPageInsteadOfFollowingFeed() {
        String garbageCursor = "not-a-valid-ranked-cursor!!!";

        CursorPageResponse<FeedPostResponse> page =
                service.getRecommendedFeed(viewerId, garbageCursor, 20, true);

        assertThat(page.getContent()).isEmpty();
        verify(postService, never()).getFeed(any(), any(), anyInt(), any());
    }
}
