package com.app.modules.social.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.modules.social.messaging.SocialEventTypes;
import com.app.modules.social.repository.AccountShowFewerRepository;
import com.app.modules.social.repository.BlockRepository;
import com.app.modules.social.repository.SocialUserRepository;

@ExtendWith(MockitoExtension.class)
class FeedPreferenceServiceImplTest {

    @Mock private AccountShowFewerRepository showFewerRepository;
    @Mock private SocialUserRepository socialUserRepository;
    @Mock private BlockRepository blockRepository;
    @Mock private OutboxService outboxService;

    private FeedPreferenceServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final UUID authorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service =
                new FeedPreferenceServiceImpl(
                        showFewerRepository, socialUserRepository, blockRepository, outboxService);
        lenient()
                .when(socialUserRepository.existsByIdAndDeletedAtIsNull(authorId))
                .thenReturn(true);
        lenient().when(blockRepository.existsBetween(userId, authorId)).thenReturn(false);
    }

    @Test
    void showFewer_firstCall_storesRowAndEnqueuesEventWithAuthorOnly() {
        when(showFewerRepository.insertIfAbsent(userId, authorId)).thenReturn(1);

        service.showFewer(userId, authorId);

        verify(outboxService)
                .enqueue(
                        eq(SocialEventTypes.USER_SHOW_FEWER_V1),
                        eq(SocialEventTypes.USER_SHOW_FEWER_V1),
                        eq("user"),
                        eq(userId),
                        eq(userId),
                        eq(Map.of("authorId", authorId.toString())));
    }

    @Test
    void showFewer_repeat_isIdempotentWithoutSecondEvent() {
        when(showFewerRepository.insertIfAbsent(userId, authorId)).thenReturn(0);

        service.showFewer(userId, authorId);

        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), any());
    }

    @Test
    void showFewer_selfMissingAndBlockedTargets_throwTheIdenticalNotFound() {
        when(socialUserRepository.existsByIdAndDeletedAtIsNull(authorId)).thenReturn(false);
        AppException missing = thrownBy(() -> service.showFewer(userId, authorId));

        when(socialUserRepository.existsByIdAndDeletedAtIsNull(authorId)).thenReturn(true);
        when(blockRepository.existsBetween(userId, authorId)).thenReturn(true);
        AppException blocked = thrownBy(() -> service.showFewer(userId, authorId));

        AppException self = thrownBy(() -> service.showFewer(userId, userId));

        assertThat(missing.getErrorCode()).isEqualTo(ApiErrorCode.NOT_FOUND);
        assertThat(blocked.getErrorCode()).isEqualTo(missing.getErrorCode());
        assertThat(self.getErrorCode()).isEqualTo(missing.getErrorCode());
        assertThat(blocked.getMessage()).isEqualTo(missing.getMessage());
        assertThat(self.getMessage()).isEqualTo(missing.getMessage());
        verify(showFewerRepository, never()).insertIfAbsent(any(), any());
    }

    @Test
    void undoShowFewer_rowExisted_enqueuesUndoneEvent() {
        when(showFewerRepository.deleteByUserAndAuthor(userId, authorId)).thenReturn(1);

        service.undoShowFewer(userId, authorId);

        verify(outboxService)
                .enqueue(
                        eq(SocialEventTypes.USER_SHOW_FEWER_UNDONE_V1),
                        eq(SocialEventTypes.USER_SHOW_FEWER_UNDONE_V1),
                        eq("user"),
                        eq(userId),
                        eq(userId),
                        eq(Map.of("authorId", authorId.toString())));
    }

    @Test
    void undoShowFewer_noRow_isIdempotentWithoutEvent() {
        when(showFewerRepository.deleteByUserAndAuthor(userId, authorId)).thenReturn(0);

        service.undoShowFewer(userId, authorId);

        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), any());
    }

    private static AppException thrownBy(Runnable call) {
        return catchThrowableOfType(AppException.class, call::run);
    }
}
