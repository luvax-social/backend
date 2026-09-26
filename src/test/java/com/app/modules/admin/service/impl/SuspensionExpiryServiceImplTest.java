package com.app.modules.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.modules.admin.enums.AdminActionType;
import com.app.modules.admin.repository.AdminUserRepository;
import com.app.modules.admin.service.AdminActionRecorder;
import com.app.modules.users.enums.UserStatus;

@ExtendWith(MockitoExtension.class)
class SuspensionExpiryServiceImplTest {

    @Mock private AdminUserRepository adminUserRepository;
    @Mock private AdminActionRecorder adminActionRecorder;

    private SuspensionExpiryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SuspensionExpiryServiceImpl(adminUserRepository, adminActionRecorder);
    }

    @Test
    void reinstateIfExpired_updateApplied_recordsAuditAndReturnsActive() {
        UUID userId = UUID.randomUUID();
        when(adminUserRepository.reinstateExpiredSuspension(eq(userId), any())).thenReturn(1);
        when(adminUserRepository.findStatusIncludingDeleted(userId)).thenReturn("active");

        assertThat(service.reinstateIfExpired(userId)).isEqualTo(UserStatus.ACTIVE);

        verify(adminActionRecorder)
                .record(
                        isNull(),
                        eq(AdminActionType.UNSUSPEND_USER),
                        eq(userId),
                        eq("user"),
                        eq(userId),
                        isNull(),
                        any(String.class),
                        isNull());
    }

    // Zero rows means a concurrent caller applied the same repair first, or an administrator has
    // since changed the status. Both are success: the row is read back and the caller decides on
    // that, and no second audit row is written for work this call did not do.
    @Test
    void reinstateIfExpired_zeroRowsButRowNowActive_isSuccessWithoutASecondAuditRow() {
        UUID userId = UUID.randomUUID();
        when(adminUserRepository.reinstateExpiredSuspension(eq(userId), any())).thenReturn(0);
        when(adminUserRepository.findStatusIncludingDeleted(userId)).thenReturn("active");

        assertThat(service.reinstateIfExpired(userId)).isEqualTo(UserStatus.ACTIVE);

        verify(adminActionRecorder, never())
                .record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    // An administrator who banned the account between the expiry check and this update wins: the
    // read-back reports banned, so the authentication path refuses the login rather than admitting
    // it on the strength of a stale suspended-and-expired reading.
    @Test
    void reinstateIfExpired_zeroRowsBecauseAdministratorBanned_returnsBanned() {
        UUID userId = UUID.randomUUID();
        when(adminUserRepository.reinstateExpiredSuspension(eq(userId), any())).thenReturn(0);
        when(adminUserRepository.findStatusIncludingDeleted(userId)).thenReturn("banned");

        assertThat(service.reinstateIfExpired(userId)).isEqualTo(UserStatus.BANNED);
    }

    @Test
    void reinstateIfExpired_rowGone_returnsNull() {
        UUID userId = UUID.randomUUID();
        when(adminUserRepository.reinstateExpiredSuspension(eq(userId), any())).thenReturn(0);
        when(adminUserRepository.findStatusIncludingDeleted(userId)).thenReturn(null);

        assertThat(service.reinstateIfExpired(userId)).isNull();
    }

    @Test
    void reinstateExpiredBatch_countsOnlyRowsThisPassUpdated() {
        UUID applied = UUID.randomUUID();
        UUID alreadyHandled = UUID.randomUUID();
        when(adminUserRepository.findExpiredSuspensionIds(eq(500), any()))
                .thenReturn(List.of(applied, alreadyHandled));
        when(adminUserRepository.reinstateExpiredSuspension(eq(applied), any())).thenReturn(1);
        when(adminUserRepository.reinstateExpiredSuspension(eq(alreadyHandled), any()))
                .thenReturn(0);

        assertThat(service.reinstateExpiredBatch(500)).isEqualTo(1);

        verify(adminActionRecorder)
                .record(
                        isNull(),
                        eq(AdminActionType.UNSUSPEND_USER),
                        eq(applied),
                        eq("user"),
                        eq(applied),
                        isNull(),
                        any(String.class),
                        isNull());
        verify(adminActionRecorder, never())
                .record(
                        isNull(),
                        eq(AdminActionType.UNSUSPEND_USER),
                        eq(alreadyHandled),
                        eq("user"),
                        eq(alreadyHandled),
                        isNull(),
                        any(String.class),
                        isNull());
    }

    @Test
    void reinstateExpiredBatch_noCandidates_reinstatesNothing() {
        when(adminUserRepository.findExpiredSuspensionIds(eq(500), any())).thenReturn(List.of());

        assertThat(service.reinstateExpiredBatch(500)).isZero();

        verify(adminUserRepository, never()).reinstateExpiredSuspension(any(), any());
    }
}
