package com.app.modules.post.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.settings.service.SystemSettingService;
import com.app.modules.post.repository.PostRepository;

@ExtendWith(MockitoExtension.class)
class PostSchedulePolicyTest {

    @Mock private SystemSettingService systemSettingService;
    @Mock private PostRepository postRepository;

    private PostSchedulePolicy policy;

    private final UUID ownerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        policy = new PostSchedulePolicy(systemSettingService, postRepository);
        lenient()
                .when(systemSettingService.getRequiredLong("scheduled_post_horizon_days"))
                .thenReturn(30L);
        lenient()
                .when(systemSettingService.getRequiredLong("max_drafts_and_scheduled_posts"))
                .thenReturn(50L);
    }

    private static AppException failureOf(Runnable action) {
        try {
            action.run();
        } catch (AppException e) {
            return e;
        }
        throw new AssertionError("expected an AppException");
    }

    @Test
    void validate_laterToday_isFlooredToTheMinute() {
        OffsetDateTime requested =
                OffsetDateTime.now(ZoneOffset.UTC)
                        .plusHours(2)
                        .withSecond(37)
                        .withNano(123_000_000);

        OffsetDateTime stored = policy.validate(requested);

        assertThat(stored).isEqualTo(requested.truncatedTo(ChronoUnit.MINUTES));
        assertThat(stored.getSecond()).isZero();
        assertThat(stored.getNano()).isZero();
    }

    @Test
    void validate_nullTime_isRefused() {
        assertThat(failureOf(() -> policy.validate(null)).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_SCHEDULE_INVALID);
    }

    @Test
    void validate_inThePastOrWithinTheNextMinute_isRefused() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        for (OffsetDateTime requested :
                new OffsetDateTime[] {now.minusMinutes(5), now, now.plusSeconds(30)}) {
            assertThat(failureOf(() -> policy.validate(requested)).getErrorCode())
                    .as("requested %s", requested)
                    .isEqualTo(ApiErrorCode.POST_SCHEDULE_INVALID);
        }
    }

    @Test
    void validate_justBeyondTheHorizon_isRefused() {
        OffsetDateTime beyond = OffsetDateTime.now(ZoneOffset.UTC).plusDays(30).plusMinutes(2);

        assertThat(failureOf(() -> policy.validate(beyond)).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_SCHEDULE_INVALID);
    }

    @Test
    void validate_justInsideTheHorizon_isAccepted() {
        OffsetDateTime inside = OffsetDateTime.now(ZoneOffset.UTC).plusDays(29).plusHours(23);

        assertThat(policy.validate(inside)).isEqualTo(inside.truncatedTo(ChronoUnit.MINUTES));
    }

    @Test
    void validate_anInstantGivenInAnotherOffset_isHeldAsUtc() {
        // 03:00 in Ho Chi Minh City is 20:00 UTC the day before; the instant is what counts, so
        // a daylight-saving rule in the author's zone can never move the publication.
        ZonedDateTime local =
                ZonedDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(3).withHour(3);

        OffsetDateTime stored = policy.validate(local.toOffsetDateTime());

        assertThat(stored.getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(stored.toInstant()).isEqualTo(local.toInstant().truncatedTo(ChronoUnit.MINUTES));
    }

    @Test
    void requireRoomForUnpublished_takesTheOwnersLockBeforeCounting() {
        when(postRepository.countDraftsAndScheduled(ownerId)).thenReturn(49L);

        policy.requireRoomForUnpublished(ownerId);

        InOrder order = inOrder(postRepository);
        order.verify(postRepository).lockOwnerDraftSlots(ownerId);
        order.verify(postRepository).countDraftsAndScheduled(ownerId);
    }

    @Test
    void requireRoomForUnpublished_atTheCap_isRefused() {
        when(postRepository.countDraftsAndScheduled(ownerId)).thenReturn(50L);

        assertThat(failureOf(() -> policy.requireRoomForUnpublished(ownerId)).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_DRAFT_LIMIT_REACHED);
    }

    @Test
    void limit_readsTheSystemSetting() {
        assertThat(policy.limit()).isEqualTo(50L);
    }
}
