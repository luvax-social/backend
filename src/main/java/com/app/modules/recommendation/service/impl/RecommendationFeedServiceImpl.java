package com.app.modules.recommendation.service.impl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.app.common.response.CursorPageResponse;
import com.app.modules.post.dto.response.FeedPostResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.enums.FeedSurface;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.service.PostFeedbackService;
import com.app.modules.post.service.PostLookupService;
import com.app.modules.post.service.PostService;
import com.app.modules.post.service.PostVisibilityService;
import com.app.modules.recommendation.client.dto.GorseScore;
import com.app.modules.recommendation.config.GorseProperties;
import com.app.modules.recommendation.observability.RecommendationMetrics;
import com.app.modules.recommendation.service.RecommendationFeedService;
import com.app.modules.recommendation.service.impl.feed.RecommendationSource;
import com.app.modules.recommendation.service.impl.feed.RecommendationSource.SourceBatch;
import com.app.modules.social.service.SocialService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class RecommendationFeedServiceImpl implements RecommendationFeedService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    // Bounds forged cursors; also matches the depth at which Gorse's cached list is exhausted
    private static final int MAX_OFFSET = 10_000;
    // Bounds source round trips per page so a heavily-filtered candidate stream cannot stall the
    // request; the page is simply served short instead.
    private static final int MAX_SOURCE_ROUNDS = 5;

    private final RecommendationSource recommendationSource;
    private final PostLookupService postLookupService;
    private final PostVisibilityService postVisibilityService;
    private final PostFeedbackService postFeedbackService;
    private final PostService postService;
    private final SocialService socialService;
    private final GorseProperties gorseProperties;
    private final RecommendationMetrics recommendationMetrics;

    public RecommendationFeedServiceImpl(
            RecommendationSource recommendationSource,
            PostLookupService postLookupService,
            PostVisibilityService postVisibilityService,
            PostFeedbackService postFeedbackService,
            PostService postService,
            SocialService socialService,
            GorseProperties gorseProperties,
            RecommendationMetrics recommendationMetrics) {
        this.recommendationSource = recommendationSource;
        this.postLookupService = postLookupService;
        this.postVisibilityService = postVisibilityService;
        this.postFeedbackService = postFeedbackService;
        this.postService = postService;
        this.socialService = socialService;
        this.gorseProperties = gorseProperties;
        this.recommendationMetrics = recommendationMetrics;
    }

    @Override
    public CursorPageResponse<FeedPostResponse> getRecommendedFeed(
            UUID viewerId, String cursor, int limit, boolean excludeFollowed) {
        int pageSize = normalizeLimit(limit);
        FeedCursor decoded = FeedCursor.decode(cursor);
        if (decoded == null) {
            if (excludeFollowed) {
                // A non-ranked cursor means the previous page came from the chronological
                // following feed, which by definition shows exactly the accounts Explore exists
                // to exclude - so it must never be entered here.
                return emptyPage();
            }
            // Not a ranked cursor: the previous page came from the chronological fallback, whose
            // timestamp cursors the post module owns. Delegate so pagination continues seamlessly.
            return postService.getFeed(viewerId, cursor, pageSize, FeedSurface.FOR_YOU_FALLBACK);
        }

        char source = decoded.source();
        int gorseOffset = decoded.gorseOffset();
        int trendingOffset = decoded.trendingOffset();
        List<FeedPostResponse> content = new ArrayList<>(pageSize);
        Set<UUID> seenPostIds = new HashSet<>();
        int fetchSize = Math.max(pageSize * gorseProperties.getRecommendMultiplier(), pageSize);
        // Computed once per request, not per round: the follow graph does not change mid-request,
        // and this keeps a heavy-follow viewer's page from issuing the query once per round.
        Set<UUID> excludedOwnerIds =
                excludeFollowed
                        ? new HashSet<>(socialService.getAcceptedFollowingExcludingBlocks(viewerId))
                        : Set.of();

        int roundsRun = 0;
        for (int round = 0; round < MAX_SOURCE_ROUNDS && content.size() < pageSize; round++) {
            roundsRun++;
            SourceBatch batch =
                    recommendationSource.fetch(
                            viewerId, source, fetchSize, gorseOffset, trendingOffset);
            // A degraded batch flips the cursor source so later pages keep reading the same list
            source = batch.source();
            List<GorseScore> scores = batch.scores();
            if (scores.isEmpty()) {
                break;
            }
            int contentSizeBeforeRound = content.size();
            int consumed =
                    appendVisible(
                            viewerId, scores, content, seenPostIds, pageSize, excludedOwnerIds);
            // A round that consumed real candidates but accepted none of them is worth flagging:
            // usually every id Gorse returned failed to resolve to a live, visible post (the
            // id-drift failure mode), and occasionally a duplicate already accepted earlier in
            // this request. Neither is a recommender outage (that degrades earlier, in
            // RecommendationSource) or a cold-start empty response (that is scores.isEmpty()
            // above), and nothing else on this path logs it, even though the response still comes
            // back 200.
            if (content.size() == contentSizeBeforeRound) {
                log.warn(
                        "Recommendation source round added zero posts from {} candidates for"
                                + " viewer {} (round {}, source '{}')",
                        scores.size(),
                        viewerId,
                        round,
                        source);
                recommendationMetrics.zeroAcceptRound();
            }
            // The batch does not report a ready-made next offset: appendVisible may stop before
            // reaching the end of scores once the page fills, and only that consumed count says
            // how far into each underlying list this round actually looked. Advancing gorseOffset
            // by the full batch size regardless of consumption would silently skip whatever was
            // never inspected - the bug this precise accounting exists to avoid.
            gorseOffset += Math.min(consumed, batch.primaryCount());
            if (consumed > batch.primaryCount()) {
                // The topup portion was reached this round, so its whole fetched chunk - not just
                // the candidates taken from it - is considered spent (see RecommendationSource).
                trendingOffset += batch.trendingChunkFetched();
            }
        }

        if (content.isEmpty() && decoded.isFirstPage()) {
            if (excludeFollowed) {
                log.warn(
                        "Recommendation pipeline produced no candidates for viewer {} after {}"
                                + " round(s); serving an empty Explore page (excludeFollowed=true)",
                        viewerId,
                        roundsRun);
                return emptyPage();
            }
            // Both ranked sources empty or unavailable: serve the chronological following feed
            // (its own cursor scheme takes over on subsequent pages).
            log.warn(
                    "Recommendation pipeline produced no candidates for viewer {} after {}"
                            + " round(s); falling back to the chronological following feed",
                    viewerId,
                    roundsRun);
            recommendationMetrics.chronologicalFallback();
            return postService.getFeed(viewerId, null, pageSize, FeedSurface.FOR_YOU_FALLBACK);
        }
        String startCursor =
                content.isEmpty()
                        ? null
                        : FeedCursor.encode(
                                source, decoded.gorseOffset(), decoded.trendingOffset());
        String endCursor =
                content.isEmpty() ? null : FeedCursor.encode(source, gorseOffset, trendingOffset);
        // A full page implies the source still had candidates left when the selector stopped.
        boolean hasNextPage = content.size() == pageSize;
        return CursorPageResponse.of(
                content, hasNextPage, startCursor, endCursor, !decoded.isFirstPage());
    }

    private static CursorPageResponse<FeedPostResponse> emptyPage() {
        return CursorPageResponse.of(List.of(), false, null, null, false);
    }

    /**
     * Hydrates a candidate batch, filters it down to posts the viewer may see and has not asked to
     * be spared, and appends up to the page capacity. Returns how many raw candidates were consumed
     * (accepted or rejected); candidates beyond a filled page are not counted so the next page
     * re-reads them.
     */
    private int appendVisible(
            UUID viewerId,
            List<GorseScore> scores,
            List<FeedPostResponse> content,
            Set<UUID> seenPostIds,
            int pageSize,
            Set<UUID> excludedOwnerIds) {
        Map<UUID, Post> postsById =
                postLookupService.findActiveByIds(parseIds(scores)).stream()
                        .collect(Collectors.toMap(Post::getId, Function.identity()));
        // One fixed-cost batched visibility check for every candidate post in this round, instead
        // of a separate block/owner/follow query set per candidate post. It is per post rather than
        // per owner because a block with a co-author hides a post whose owner the viewer may see.
        Map<UUID, UUID> ownerByPostId =
                postsById.values().stream().collect(Collectors.toMap(Post::getId, Post::getUserId));
        Set<UUID> visiblePostIds =
                postVisibilityService.filterVisiblePostIds(viewerId, ownerByPostId);
        Map<UUID, List<UUID>> coAuthorsByPostId =
                postLookupService.findAcceptedCoAuthors(postsById.keySet());
        // The viewer's own preferences are applied here in the application and never through the
        // recommender's IsHidden flag, which is global to an item and would hide the post from
        // everyone. Both reads are one batched query per round, whatever the candidate source: the
        // personalized list, the trending topup and the popularity list all arrive through this
        // method. They add to the visibility filters above and never replace them.
        Set<UUID> notInterestedPostIds =
                postFeedbackService.findNotInterested(viewerId, postsById.keySet());
        Set<UUID> suppressedAuthorIds =
                socialService.findAllFeedSuppressedAuthors(viewerId, FeedSurface.FOR_YOU);

        List<Post> accepted = new ArrayList<>();
        List<Double> acceptedScores = new ArrayList<>();
        int consumed = 0;
        for (GorseScore score : scores) {
            consumed++;
            UUID postId = tryParseUuid(score.id());
            if (postId == null || !seenPostIds.add(postId)) {
                continue;
            }
            Post post = postsById.get(postId);
            if (post == null
                    || post.getStatus() != PostStatus.PUBLISHED
                    || viewerId.equals(post.getUserId())
                    || !visiblePostIds.contains(postId)
                    || notInterestedPostIds.contains(postId)) {
                continue;
            }
            // Every author of the post counts: a viewer who follows, muted or asked for fewer posts
            // from a co-author sees the post exactly as if that account owned it.
            List<UUID> authorIds = new ArrayList<>();
            authorIds.add(post.getUserId());
            authorIds.addAll(coAuthorsByPostId.getOrDefault(postId, List.of()));
            if (authorIds.contains(viewerId)
                    || authorIds.stream().anyMatch(excludedOwnerIds::contains)
                    || authorIds.stream().anyMatch(suppressedAuthorIds::contains)) {
                continue;
            }
            accepted.add(post);
            acceptedScores.add(score.score());
            if (content.size() + accepted.size() >= pageSize) {
                break;
            }
        }
        if (!accepted.isEmpty()) {
            List<FeedPostResponse> assembled = postLookupService.assembleFeed(viewerId, accepted);
            for (int i = 0; i < assembled.size(); i++) {
                content.add(assembled.get(i).withRankingScore(acceptedScores.get(i)));
            }
        }
        return consumed;
    }

    private static List<UUID> parseIds(List<GorseScore> scores) {
        List<UUID> ids = new ArrayList<>(scores.size());
        for (GorseScore score : scores) {
            UUID id = tryParseUuid(score.id());
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static UUID tryParseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            // Foreign ids (e.g. manually inserted test items) are skipped rather than failing the
            // whole page.
            return null;
        }
    }

    private static int normalizeLimit(int limit) {
        return limit > MAX_PAGE_SIZE ? MAX_PAGE_SIZE : (limit < 1 ? DEFAULT_PAGE_SIZE : limit);
    }

    /**
     * Opaque ranked-feed cursor: base64 of {@code <source>:<gorseOffset>:<trendingOffset>} where
     * source is 'g' (Gorse recommend) or 'p' (popularity fallback). {@code gorseOffset} positions
     * Gorse's personalized or popularity list; {@code trendingOffset} independently positions the
     * trending list the exhaustion topup reads from. Both must be carried so revisiting a page with
     * the same cursor always reproduces the same composition - a single shared offset cannot
     * represent a position in two independently-paginated lists at once. {@code decode} returns
     * null for any other cursor shape, including the two-field cursors this format replaces, which
     * the service treats as a chronological-feed cursor to delegate.
     */
    record FeedCursor(char source, int gorseOffset, int trendingOffset) {

        boolean isFirstPage() {
            return gorseOffset == 0 && trendingOffset == 0;
        }

        static String encode(char source, int gorseOffset, int trendingOffset) {
            return Base64.getEncoder()
                    .encodeToString(
                            (source + ":" + gorseOffset + ":" + trendingOffset)
                                    .getBytes(StandardCharsets.UTF_8));
        }

        static FeedCursor decode(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return new FeedCursor(RecommendationSource.SOURCE_GORSE, 0, 0);
            }
            String raw;
            try {
                raw = new String(Base64.getDecoder().decode(cursor), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
            String[] parts = raw.split(":", -1);
            if (parts.length != 3
                    || parts[0].length() != 1
                    || (parts[0].charAt(0) != RecommendationSource.SOURCE_GORSE
                            && parts[0].charAt(0) != RecommendationSource.SOURCE_POPULAR)) {
                return null;
            }
            try {
                int gorseOffset = clampOffset(Integer.parseInt(parts[1]));
                int trendingOffset = clampOffset(Integer.parseInt(parts[2]));
                return new FeedCursor(parts[0].charAt(0), gorseOffset, trendingOffset);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static int clampOffset(int offset) {
            return offset < 0 || offset > MAX_OFFSET ? 0 : offset;
        }
    }
}
