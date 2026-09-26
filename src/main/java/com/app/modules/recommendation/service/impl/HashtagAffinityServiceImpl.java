package com.app.modules.recommendation.service.impl;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.modules.recommendation.config.HashtagAffinityProperties;
import com.app.modules.recommendation.entity.UserHashtagAffinity;
import com.app.modules.recommendation.repository.UserHashtagAffinityRepository;
import com.app.modules.recommendation.service.AffinityRecomputeResult;
import com.app.modules.recommendation.service.HashtagAffinityService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class HashtagAffinityServiceImpl implements HashtagAffinityService {

    private final UserHashtagAffinityRepository affinityRepository;
    private final HashtagAffinityProperties properties;

    @Override
    @Transactional
    public AffinityRecomputeResult recompute() {
        OffsetDateTime windowEnd = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime windowStart = windowEnd.minus(properties.window());
        long halfLifeSeconds = properties.halfLife().toSeconds();

        int written =
                affinityRepository.recomputeWindow(
                        windowStart, windowEnd, halfLifeSeconds, windowEnd);
        // Same transaction as the upsert on purpose: separating them would expose a moment where a
        // reader sees the previous run's rows for one user and this run's for another.
        int removed = affinityRepository.deleteStale(windowEnd);

        log.info(
                "[affinity] recompute complete | window: {} to {} | rows written: {} | stale"
                        + " removed: {}",
                windowStart,
                windowEnd,
                written,
                removed);
        return new AffinityRecomputeResult(written, removed, windowStart, windowEnd);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserHashtagAffinity> findTopForUser(UUID userId, int limit) {
        int effectiveLimit = limit > 0 ? limit : properties.topLimit();
        return affinityRepository.findTopForUser(userId, effectiveLimit);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasAffinity(UUID userId) {
        return affinityRepository.countForUser(userId) > 0;
    }
}
