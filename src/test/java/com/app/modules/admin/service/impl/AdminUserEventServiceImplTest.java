package com.app.modules.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.modules.admin.service.AdminAuthorizationService;
import com.app.modules.recommendation.repository.UserEventRepository;

@ExtendWith(MockitoExtension.class)
class AdminUserEventServiceImplTest {

    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);
    private static final UUID ACTOR = UUID.randomUUID();

    @Mock private UserEventRepository userEventRepository;
    @Mock private AdminAuthorizationService adminAuthorizationService;

    private AdminUserEventServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminUserEventServiceImpl(userEventRepository, adminAuthorizationService);
    }

    @Test
    void listUserEvents_absentFrom_rejectedBeforeAnyRead() {
        assertRejected(null, NOW);
    }

    @Test
    void listUserEvents_absentTo_rejectedBeforeAnyRead() {
        assertRejected(NOW.minusDays(1), null);
    }

    @Test
    void listUserEvents_equalBounds_rejectedBeforeAnyRead() {
        assertRejected(NOW, NOW);
    }

    @Test
    void listUserEvents_reversedBounds_rejectedBeforeAnyRead() {
        assertRejected(NOW, NOW.minusDays(1));
    }

    @Test
    void listUserEvents_windowOverThirtyDays_rejectedBeforeAnyRead() {
        assertRejected(NOW.minusDays(30).minusSeconds(1), NOW);
    }

    @Test
    void listUserEvents_windowOfExactlyThirtyDays_isAccepted() {
        when(userEventRepository.findPage(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.listUserEvents(ACTOR, null, NOW.minusDays(30), NOW, null, null, 20);

        verify(userEventRepository)
                .findPage(
                        eq(null),
                        eq(NOW.minusDays(30)),
                        eq(NOW),
                        eq(null),
                        eq(null),
                        eq(null),
                        eq(21));
    }

    @Test
    void listUserEvents_limitAboveMax_isClampedBeforeTheRead() {
        when(userEventRepository.findPage(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.listUserEvents(ACTOR, UUID.randomUUID(), NOW.minusDays(1), NOW, null, null, 5000);

        verify(userEventRepository).findPage(any(), any(), any(), any(), any(), any(), eq(101));
    }

    // The role gate runs ahead of the window validation, so a non-administrator is refused without
    // the service reaching a read at all.
    @Test
    void listUserEvents_actorNotAdministrator_refusedBeforeAnyRead() {
        doThrow(new AppException(ApiErrorCode.FORBIDDEN))
                .when(adminAuthorizationService)
                .assertActorIsAdministrator(ACTOR);

        assertThatThrownBy(
                        () ->
                                service.listUserEvents(
                                        ACTOR, null, NOW.minusDays(1), NOW, null, null, 20))
                .isInstanceOf(AppException.class)
                .satisfies(
                        e ->
                                assertThat(((AppException) e).getErrorCode())
                                        .isEqualTo(ApiErrorCode.FORBIDDEN));

        verify(userEventRepository, never())
                .findPage(any(), any(), any(), any(), any(), any(), anyInt());
    }

    private void assertRejected(OffsetDateTime from, OffsetDateTime to) {
        assertThatThrownBy(() -> service.listUserEvents(ACTOR, null, from, to, null, null, 20))
                .isInstanceOf(AppException.class)
                .satisfies(
                        e ->
                                assertThat(((AppException) e).getErrorCode())
                                        .isEqualTo(ApiErrorCode.BAD_REQUEST));

        verify(userEventRepository, never())
                .findPage(any(), any(), any(), any(), any(), any(), anyInt());
    }
}
