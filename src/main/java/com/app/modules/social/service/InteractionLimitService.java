package com.app.modules.social.service;

import java.util.List;
import java.util.UUID;

import com.app.modules.social.dto.response.InteractionLimitResponse;

/**
 * A temporary restriction an account places on who may interact with it.
 *
 * <p>An account has at most one limit. It is in force while its expiry is in the future, and an
 * expired row simply reads as inactive, so no job has to run for it to stop applying. Every change
 * enqueues {@code user.interaction-limit.changed.v1} in the same transaction.
 */
public interface InteractionLimitService {

    /** The durations, in days, an account may choose from. */
    List<Integer> DURATION_PRESET_DAYS = List.of(1, 3, 7, 14, 28);

    /**
     * Reads the account's limit.
     *
     * @param userId the account that owns the limit
     * @return the stored limit with its in-force flag, or both toggles off and no expiry when the
     *     account never set one
     */
    InteractionLimitResponse get(UUID userId);

    /**
     * Turns the limit on, replacing any earlier one, for a preset number of days from now.
     *
     * @param userId the account that owns the limit
     * @param limitNonFollowers whether people who do not follow the account are limited
     * @param limitRecentFollowers whether people who followed it in the last 7 days are limited
     * @param durationDays one of {@link #DURATION_PRESET_DAYS}
     * @return the stored limit
     * @throws com.app.common.exception.AppException with {@code VALIDATION_ERROR} when both toggles
     *     are off or the duration is not a preset
     */
    InteractionLimitResponse set(
            UUID userId, boolean limitNonFollowers, boolean limitRecentFollowers, int durationDays);

    /**
     * Turns the limit off early.
     *
     * <p>Idempotent. A change event is enqueued only when a limit was actually removed.
     *
     * @param userId the account that owns the limit
     */
    void clear(UUID userId);
}
