package com.app.modules.hashtag.service.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.response.PageResponse;
import com.app.modules.hashtag.dto.response.HashtagTrendingResponse;
import com.app.modules.hashtag.enums.TrendingSource;
import com.app.modules.hashtag.service.HashtagTrendingService;
import com.app.modules.hashtag.service.PersonalisedTrendingService;
import com.app.modules.recommendation.entity.UserHashtagAffinity;
import com.app.modules.recommendation.repository.UserHashtagAffinityRepository;
import com.app.modules.recommendation.service.HashtagAffinityService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalisedTrendingServiceImpl implements PersonalisedTrendingService {

    /**
     * Reciprocal-rank-fusion damping constant.
     *
     * <p>60 is the value the original RRF paper settled on and the one every later implementation
     * reuses. Its effect is to flatten the difference between adjacent top positions: without it,
     * rank 1 would score twice rank 2, which would let whichever list happens to rank a hashtag
     * first dominate the fused order outright.
     */
    private static final int RRF_K = 60;

    /** Weight on the platform list's rank contribution. */
    private static final double PLATFORM_WEIGHT = 1.0;

    /** Weight on the caller's own affinity rank contribution. */
    private static final double AFFINITY_WEIGHT = 1.5;

    /**
     * Share of slots reserved for hashtags adjacent to the user's interests but not among them.
     *
     * <p>Three in ten. Low enough that the list still reads as the user's own, high enough that at
     * a page size of ten the user always sees at least three things they have not already engaged
     * with. Without a floor like this the blend converges on the user's existing interests and
     * becomes strictly less useful than the platform list it replaced.
     */
    private static final double NOVELTY_SHARE = 0.3;

    /** How deep into each source list the fusion looks before ranking. */
    private static final int CANDIDATE_DEPTH = 50;

    /**
     * Cache key family for this surface, added to the documented Redis patterns rather than left
     * undocumented as {@code auth:ws-ticket} and {@code comment:watchers} were.
     */
    private static final String CACHE_KEY = "hashtag:trending:personalised:%s:%d:%d";

    /**
     * Ten minutes, against a source that changes on a scheduled job cycle rather than on a request.
     *
     * <p>The two inputs move on their own clocks: the trending snapshot is a periodic job and the
     * affinity model recomputes every twelve hours. A TTL shorter than this would re-run the fusion
     * for data that cannot have changed; much longer and a newly pinned hashtag would take an
     * unreasonable time to appear.
     */
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final HashtagTrendingService hashtagTrendingService;
    private final HashtagAffinityService hashtagAffinityService;
    private final UserHashtagAffinityRepository affinityRepository;
    private final StringRedisTemplate redisTemplate;

    /**
     * Private to this cache, not the application's shared mapper.
     *
     * <p>The cached payload is an internal representation, so it must not shift because a global
     * Jackson setting changed elsewhere; a stored entry has to stay readable by the code that wrote
     * it for the whole of its ten-minute life. {@code findAndRegisterModules} picks up the JSR-310
     * module the {@code OffsetDateTime} fields need.
     */
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Override
    @Transactional(readOnly = true)
    public PageResponse<HashtagTrendingResponse> getPersonalisedTrending(
            UUID viewerId, Pageable pageable) {
        String key =
                CACHE_KEY.formatted(viewerId, pageable.getPageNumber(), pageable.getPageSize());
        CachedPage cached = readCache(key);
        if (cached != null) {
            return PageResponse.from(
                    new PageImpl<>(cached.content(), pageable, cached.totalElements()));
        }
        PageResponse<HashtagTrendingResponse> computed = compute(viewerId, pageable);
        writeCache(key, computed);
        return computed;
    }

    private PageResponse<HashtagTrendingResponse> compute(UUID viewerId, Pageable pageable) {
        PageResponse<HashtagTrendingResponse> platform =
                hashtagTrendingService.getTrending(PageRequest.of(0, CANDIDATE_DEPTH));
        List<HashtagTrendingResponse> platformList = platform.getContent();

        List<UserHashtagAffinity> affinities =
                hashtagAffinityService.findTopForUser(viewerId, CANDIDATE_DEPTH);

        // Silent fallback, never an empty personalised tab. A user with no computed affinity is the
        // normal cold-start state, not an error, and the platform list is a correct answer for
        // them.
        if (affinities.isEmpty()) {
            return page(platformList, pageable);
        }

        Map<UUID, HashtagTrendingResponse> byId = new HashMap<>();
        Map<UUID, Double> fused = new HashMap<>();

        // Fusion is on rank, never on the two scores directly. An affinity score is a share of one
        // user's own decayed total, so a user with three hashtags scores about 0.33 each while a
        // user with a hundred and thirty scores about 0.007 each; a trending score is a post count
        // over a window. Adding or weighting those together compares quantities that are not the
        // same unit, and the result would swing on how broad a user's interests happen to be
        // rather than on what they are.
        for (int i = 0; i < platformList.size(); i++) {
            HashtagTrendingResponse entry = platformList.get(i);
            byId.put(entry.hashtagId(), entry);
            fused.merge(entry.hashtagId(), PLATFORM_WEIGHT / (RRF_K + i + 1.0), Double::sum);
        }
        for (int i = 0; i < affinities.size(); i++) {
            UUID hashtagId = affinities.get(i).getId().getHashtagId();
            fused.merge(hashtagId, AFFINITY_WEIGHT / (RRF_K + i + 1.0), Double::sum);
        }

        List<UUID> blendedOrder =
                fused.entrySet().stream()
                        .sorted(
                                Map.Entry.<UUID, Double>comparingByValue()
                                        .reversed()
                                        // Total order, so two hashtags with an identical fused
                                        // score cannot swap places between calls and reshuffle the
                                        // list under the reader.
                                        .thenComparing(Map.Entry.comparingByKey()))
                        .map(Map.Entry::getKey)
                        .toList();

        List<UUID> adjacent = affinityRepository.findAdjacentHashtagIds(viewerId, CANDIDATE_DEPTH);

        // The whole candidate list is built here and sliced into a page below. Selecting only a
        // page's worth and returning it for whatever page was asked for made every page of this
        // branch carry identical rows, while the total - the page's own length - reported it as the
        // only page, so a client paging on "last" never saw the duplication.
        //
        // Novelty is interleaved by running ratio rather than reserved as a block at the end. A
        // block would put every novel entry past the first page, which is the one page most callers
        // ever read: the share has to hold within each page-sized window, not merely across the
        // whole list. Drawing novel whenever the novel count has fallen behind its share of the
        // positions filled so far keeps every prefix of the list at roughly NOVELTY_SHARE, so each
        // page gets its share wherever the reader stops.
        LinkedHashSet<UUID> selected = new LinkedHashSet<>();
        int blendedCursor = 0;
        int adjacentCursor = 0;
        int novelTaken = 0;
        while (selected.size() < CANDIDATE_DEPTH
                && (blendedCursor < blendedOrder.size() || adjacentCursor < adjacent.size())) {
            boolean novelIsBehind = novelTaken < Math.round((selected.size() + 1) * NOVELTY_SHARE);
            boolean takeNovel =
                    adjacentCursor < adjacent.size()
                            && (novelIsBehind || blendedCursor >= blendedOrder.size());
            if (takeNovel) {
                // Backfill is implicit: when one pool empties the other simply supplies the rest,
                // so reserving novelty can never shorten the list. A caller who engages with nearly
                // every hashtag has almost no adjacency pool, and a full list is still correct.
                if (selected.add(adjacent.get(adjacentCursor))) {
                    novelTaken++;
                }
                adjacentCursor++;
            } else {
                selected.add(blendedOrder.get(blendedCursor));
                blendedCursor++;
            }
        }

        java.util.Set<UUID> affinityIds = new java.util.HashSet<>();
        affinities.forEach(a -> affinityIds.add(a.getId().getHashtagId()));

        List<UUID> selectedIds = new ArrayList<>(selected);
        List<UUID> missing = selectedIds.stream().filter(id -> !byId.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            hashtagTrendingService
                    .describeHashtags(missing)
                    .forEach(entry -> byId.put(entry.hashtagId(), entry));
        }

        List<HashtagTrendingResponse> content = new ArrayList<>();
        for (int i = 0; i < selectedIds.size(); i++) {
            UUID id = selectedIds.get(i);
            HashtagTrendingResponse base = byId.get(id);
            if (base == null) {
                continue;
            }
            TrendingSource source =
                    affinityIds.contains(id)
                            ? TrendingSource.AFFINITY
                            : (platformList.stream().anyMatch(p -> p.hashtagId().equals(id))
                                    ? TrendingSource.PLATFORM
                                    : TrendingSource.NOVEL);
            content.add(
                    new HashtagTrendingResponse(
                            base.hashtagId(),
                            base.name(),
                            base.postCount(),
                            i + 1,
                            base.periodStart(),
                            base.periodEnd(),
                            base.pinned(),
                            source));
        }
        // Pinned hashtags lead the personalised list too: a platform-wide pin is platform-wide.
        content.sort((a, b) -> Boolean.compare(b.pinned(), a.pinned()));
        return page(content, pageable);
    }

    // Redis is a cache tier here, so its being unavailable degrades latency and nothing else. A
    // failure to read or write the cache is swallowed and the list is computed from PostgreSQL,
    // because failing the request would turn an optional accelerator into a hard dependency.
    private CachedPage readCache(String key) {
        try {
            String raw = redisTemplate.opsForValue().get(key);
            if (raw == null) {
                return null;
            }
            return objectMapper.readValue(raw, new TypeReference<CachedPage>() {});
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("Personalised trending cache read failed for {}, computing directly", key, ex);
            return null;
        }
    }

    private void writeCache(String key, PageResponse<HashtagTrendingResponse> computed) {
        try {
            CachedPage payload = new CachedPage(computed.getContent(), computed.getTotalElements());
            redisTemplate
                    .opsForValue()
                    .set(key, objectMapper.writeValueAsString(payload), CACHE_TTL);
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("Personalised trending cache write failed for {}", key, ex);
        }
    }

    /**
     * What one cache entry holds.
     *
     * <p>The total is stored with the content because it cannot be recovered from it. Rebuilding
     * the page as {@code new PageImpl<>(cached, pageable, cached.size())} asserted that the total
     * equalled this page's own length, so the same request answered a different totalElements,
     * totalPages and last depending on whether it was served from cache - the one property a cache
     * must not have. A client paging on "last" stopped after the first page on any cached read.
     *
     * @param content the page's rows, in order
     * @param totalElements the size of the full result the page was cut from
     */
    private record CachedPage(List<HashtagTrendingResponse> content, long totalElements) {}

    private PageResponse<HashtagTrendingResponse> page(
            List<HashtagTrendingResponse> source, Pageable pageable) {
        int from = Math.min((int) pageable.getOffset(), source.size());
        int to = Math.min(from + pageable.getPageSize(), source.size());
        List<HashtagTrendingResponse> slice = source.subList(from, to);
        return PageResponse.from(new PageImpl<>(slice, pageable, source.size()));
    }
}
