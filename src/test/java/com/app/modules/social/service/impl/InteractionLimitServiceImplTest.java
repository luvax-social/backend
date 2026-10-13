package com.app.modules.social.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.modules.social.dto.response.InteractionLimitResponse;
import com.app.modules.social.entity.InteractionLimit;
import com.app.modules.social.messaging.SocialEventTypes;
import com.app.modules.social.repository.InteractionLimitRepository;

@ExtendWith(MockitoExtension.class)
class InteractionLimitServiceImplTest {

    @Mock private InteractionLimitRepository repository;
    @Mock private OutboxService outboxService;

    private InteractionLimitServiceImpl service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new InteractionLimitServiceImpl(repository, outboxService);
    }

    @Test
    void get_noRow_reportsInactiveWithBothTogglesOff() {
        when(repository.findById(userId)).thenReturn(Optional.empty());

        InteractionLimitResponse response = service.get(userId);

        assertThat(response).isEqualTo(new InteractionLimitResponse(false, false, null, false));
    }

    @Test
    void get_expiryInThePast_isInactiveButKeepsItsToggles() {
        OffsetDateTime expired = OffsetDateTime.now().minusMinutes(1);
        when(repository.findById(userId)).thenReturn(Optional.of(limit(true, false, expired)));

        InteractionLimitResponse response = service.get(userId);

        assertThat(response.active()).isFalse();
        assertThat(response.limitNonFollowers()).isTrue();
        assertThat(response.expiresAt()).isEqualTo(expired);
    }

    @Test
    void get_expiryInTheFuture_isActive() {
        when(repository.findById(userId))
                .thenReturn(Optional.of(limit(false, true, OffsetDateTime.now().plusMinutes(1))));

        assertThat(service.get(userId).active()).isTrue();
    }

    @Test
    void set_nonPresetDuration_isRejectedBeforeAnyWrite() {
        for (int days : new int[] {0, 2, 5, 29, -1}) {
            assertThatThrownBy(() -> service.set(userId, true, false, days))
                    .as("%s days", days)
                    .isInstanceOf(AppException.class)
                    .extracting(ex -> ((AppException) ex).getErrorCode())
                    .isEqualTo(ApiErrorCode.VALIDATION_ERROR);
        }
        verifyNoInteractions(repository, outboxService);
    }

    @Test
    void set_bothTogglesOff_isRejectedBeforeAnyWrite() {
        assertThatThrownBy(() -> service.set(userId, false, false, 7))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(repository, outboxService);
    }

    @Test
    void set_validRequest_upsertsWithAServerComputedExpiryAndEnqueuesTheChange() {
        OffsetDateTime before = OffsetDateTime.now();

        InteractionLimitResponse response = service.set(userId, true, false, 14);

        OffsetDateTime after = OffsetDateTime.now();
        ArgumentCaptor<OffsetDateTime> expiry = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).upsert(eq(userId), eq(true), eq(false), expiry.capture());
        assertThat(expiry.getValue())
                .isBetween(before.plusDays(14).minusSeconds(1), after.plusDays(14).plusSeconds(1));
        assertThat(response.expiresAt()).isEqualTo(expiry.getValue());
        assertThat(response.active()).isTrue();
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(outboxService)
                .enqueue(
                        eq(SocialEventTypes.USER_INTERACTION_LIMIT_CHANGED_V1),
                        eq(SocialEventTypes.USER_INTERACTION_LIMIT_CHANGED_V1),
                        eq("user"),
                        eq(userId),
                        eq(userId),
                        data.capture());
        assertThat(data.getValue())
                .containsEntry("limitNonFollowers", true)
                .containsEntry("limitRecentFollowers", false)
                .containsEntry("expiresAt", expiry.getValue().toString());
    }

    @Test
    void clear_rowRemoved_enqueuesAChangeWithBothTogglesOffAndNoExpiry() {
        when(repository.deleteByUserId(userId)).thenReturn(1);

        service.clear(userId);

        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(outboxService)
                .enqueue(
                        eq(SocialEventTypes.USER_INTERACTION_LIMIT_CHANGED_V1),
                        eq(SocialEventTypes.USER_INTERACTION_LIMIT_CHANGED_V1),
                        eq("user"),
                        eq(userId),
                        eq(userId),
                        data.capture());
        assertThat(data.getValue())
                .containsEntry("limitNonFollowers", false)
                .containsEntry("limitRecentFollowers", false)
                .doesNotContainKey("expiresAt");
    }

    @Test
    void clear_nothingToRemove_enqueuesNothing() {
        when(repository.deleteByUserId(userId)).thenReturn(0);

        service.clear(userId);

        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), any());
    }

    private InteractionLimit limit(boolean nonFollowers, boolean recent, OffsetDateTime expiresAt) {
        return InteractionLimit.builder()
                .userId(userId)
                .limitNonFollowers(nonFollowers)
                .limitRecentFollowers(recent)
                .expiresAt(expiresAt)
                .build();
    }
}
