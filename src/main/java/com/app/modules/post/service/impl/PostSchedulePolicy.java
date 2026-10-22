package com.app.modules.post.service.impl;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.settings.service.SystemSettingService;
import com.app.modules.post.repository.PostRepository;

/**
 * The rules for scheduling a post and for how many unpublished posts one account may hold.
 *
 * <p>Kept apart from the services that apply them so create, reschedule and draft replacement agree
 * on one definition of a valid time and one cap.
 */
@Component
public class PostSchedulePolicy {

    private static final String MAX_DRAFTS_AND_SCHEDULED = "max_drafts_and_scheduled_posts";
    private static final String HORIZON_DAYS = "scheduled_post_horizon_days";
    private static final Duration MIN_LEAD = Duration.ofMinutes(1);

    private final SystemSettingService systemSettingService;
    private final PostRepository postRepository;

    public PostSchedulePolicy(
            SystemSettingService systemSettingService, PostRepository postRepository) {
        this.systemSettingService = systemSettingService;
        this.postRepository = postRepository;
    }

    /**
     * Validates a requested publication time and returns it floored to the minute.
     *
     * <p>The time must be strictly later than one minute from now and no further than the horizon
     * (thirty days by default). It is held as a UTC instant, so a daylight-saving change in the
     * author's zone cannot move it.
     *
     * @param requested the time the author chose; may be null
     * @return the instant to store
     * @throws AppException with {@code POST_SCHEDULE_INVALID} when it is null or out of range
     */
    public OffsetDateTime validate(OffsetDateTime requested) {
        if (requested == null) {
            throw new AppException(ApiErrorCode.POST_SCHEDULE_INVALID);
        }
        OffsetDateTime floored =
                requested.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long horizonDays = systemSettingService.getRequiredLong(HORIZON_DAYS);
        if (!floored.isAfter(now.plus(MIN_LEAD)) || floored.isAfter(now.plusDays(horizonDays))) {
            throw new AppException(ApiErrorCode.POST_SCHEDULE_INVALID);
        }
        return floored;
    }

    /**
     * Refuses a new draft or scheduled post when the owner already holds the maximum.
     *
     * <p>Counts drafts and scheduled posts together, so a scheduled post that fails and returns to
     * drafts can always fit. Takes an advisory lock first, so concurrent creates serialise.
     *
     * @param ownerId the owner
     * @throws AppException with {@code POST_DRAFT_LIMIT_REACHED} when there is no room
     */
    public void requireRoomForUnpublished(UUID ownerId) {
        postRepository.lockOwnerDraftSlots(ownerId);
        long limit = limit();
        if (postRepository.countDraftsAndScheduled(ownerId) >= limit) {
            throw new AppException(ApiErrorCode.POST_DRAFT_LIMIT_REACHED);
        }
    }

    /**
     * Returns the cap on drafts and scheduled posts held together.
     *
     * @return the cap
     */
    public long limit() {
        return systemSettingService.getRequiredLong(MAX_DRAFTS_AND_SCHEDULED);
    }
}
