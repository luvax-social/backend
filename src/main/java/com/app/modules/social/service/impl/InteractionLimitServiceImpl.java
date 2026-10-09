package com.app.modules.social.service.impl;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.modules.social.dto.response.InteractionLimitResponse;
import com.app.modules.social.entity.InteractionLimit;
import com.app.modules.social.messaging.SocialEventTypes;
import com.app.modules.social.repository.InteractionLimitRepository;
import com.app.modules.social.service.InteractionLimitService;

@Service
public class InteractionLimitServiceImpl implements InteractionLimitService {

    private static final String AGGREGATE_TYPE_USER = "user";

    private final InteractionLimitRepository repository;
    private final OutboxService outboxService;

    public InteractionLimitServiceImpl(
            InteractionLimitRepository repository, OutboxService outboxService) {
        this.repository = repository;
        this.outboxService = outboxService;
    }

    @Override
    @Transactional(readOnly = true)
    public InteractionLimitResponse get(UUID userId) {
        return repository
                .findById(userId)
                .map(InteractionLimitServiceImpl::toResponse)
                .orElseGet(() -> new InteractionLimitResponse(false, false, null, false));
    }

    @Override
    @Transactional
    public InteractionLimitResponse set(
            UUID userId,
            boolean limitNonFollowers,
            boolean limitRecentFollowers,
            int durationDays) {
        if (!limitNonFollowers && !limitRecentFollowers) {
            throw new AppException(
                    ApiErrorCode.VALIDATION_ERROR, "At least one audience must be limited");
        }
        if (!DURATION_PRESET_DAYS.contains(durationDays)) {
            throw new AppException(
                    ApiErrorCode.VALIDATION_ERROR,
                    "durationDays must be one of " + DURATION_PRESET_DAYS);
        }
        // The end is computed here, never taken from the client, and cut to the microsecond
        // PostgreSQL stores so the response and the row agree exactly.
        OffsetDateTime expiresAt =
                OffsetDateTime.now(ZoneOffset.UTC)
                        .plusDays(durationDays)
                        .truncatedTo(ChronoUnit.MICROS);
        repository.upsert(userId, limitNonFollowers, limitRecentFollowers, expiresAt);
        enqueueChange(userId, limitNonFollowers, limitRecentFollowers, expiresAt);
        return new InteractionLimitResponse(
                limitNonFollowers, limitRecentFollowers, expiresAt, true);
    }

    @Override
    @Transactional
    public void clear(UUID userId) {
        if (repository.deleteByUserId(userId) > 0) {
            enqueueChange(userId, false, false, null);
        }
    }

    // Same transaction as the write, so a change that rolls back never announces itself and one
    // that commits always does. A null end time is left out rather than sent as a null, which the
    // outbox payload does not accept; the consumer reads an absent key as "no limit".
    private void enqueueChange(
            UUID userId,
            boolean limitNonFollowers,
            boolean limitRecentFollowers,
            OffsetDateTime expiresAt) {
        Map<String, Object> data = new HashMap<>();
        data.put("limitNonFollowers", limitNonFollowers);
        data.put("limitRecentFollowers", limitRecentFollowers);
        if (expiresAt != null) {
            data.put("expiresAt", expiresAt.toString());
        }
        outboxService.enqueue(
                SocialEventTypes.USER_INTERACTION_LIMIT_CHANGED_V1,
                SocialEventTypes.USER_INTERACTION_LIMIT_CHANGED_V1,
                AGGREGATE_TYPE_USER,
                userId,
                userId,
                data);
    }

    private static InteractionLimitResponse toResponse(InteractionLimit limit) {
        return new InteractionLimitResponse(
                limit.isLimitNonFollowers(),
                limit.isLimitRecentFollowers(),
                limit.getExpiresAt(),
                limit.getExpiresAt().isAfter(OffsetDateTime.now()));
    }
}
