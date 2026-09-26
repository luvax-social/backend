package com.app.modules.admin.service.impl;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.modules.admin.enums.AdminActionType;
import com.app.modules.admin.repository.AdminUserRepository;
import com.app.modules.admin.service.AdminActionRecorder;
import com.app.modules.admin.service.SuspensionExpiryService;
import com.app.modules.users.enums.UserStatus;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SuspensionExpiryServiceImpl implements SuspensionExpiryService {

    private static final String AUTOMATIC_REASON =
            "Fixed-term suspension lapsed; account returned to active automatically";
    private static final String TARGET_ENTITY_TYPE = "user";

    private final AdminUserRepository adminUserRepository;
    private final AdminActionRecorder adminActionRecorder;

    public SuspensionExpiryServiceImpl(
            AdminUserRepository adminUserRepository, AdminActionRecorder adminActionRecorder) {
        this.adminUserRepository = adminUserRepository;
        this.adminActionRecorder = adminActionRecorder;
    }

    @Override
    @Transactional
    public UserStatus reinstateIfExpired(UUID userId) {
        if (adminUserRepository.reinstateExpiredSuspension(userId, nowUtc()) == 1) {
            recordReinstatement(userId);
        }
        // Read back unconditionally. On the zero-row path another transaction or an administrator
        // changed the row, and the caller's authorization decision must use what the row says now,
        // not the status it held before this attempt.
        String status = adminUserRepository.findStatusIncludingDeleted(userId);
        return status == null ? null : UserStatus.fromJson(status);
    }

    @Override
    @Transactional
    public int reinstateExpiredBatch(int limit) {
        // One cutoff for the whole pass, so the select and the updates that follow it cannot
        // disagree about what "expired" means because time moved between them.
        java.time.OffsetDateTime cutoff = nowUtc();
        List<UUID> candidates = adminUserRepository.findExpiredSuspensionIds(limit, cutoff);
        int reinstated = 0;
        for (UUID userId : candidates) {
            // Same conditional update the authentication path runs, so the two cannot disagree
            // about what "expired" means. A candidate that path repaired between the select above
            // and this update simply updates zero rows and is skipped.
            if (adminUserRepository.reinstateExpiredSuspension(userId, cutoff) == 1) {
                recordReinstatement(userId);
                reinstated++;
            }
        }
        return reinstated;
    }

    // suspended_until is written from the JVM clock, so it is compared against the JVM clock too.
    // Letting the statement read the database's own now() put one column in two clock domains,
    // which decides whether a suspension is over by whichever clock happens to be ahead.
    private static java.time.OffsetDateTime nowUtc() {
        return java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
    }

    private void recordReinstatement(UUID userId) {
        // Null actor: no administrator took this action. The audit row exists so a reinstatement is
        // never invisible, and a null admin_id is how the log says the system did it.
        adminActionRecorder.record(
                null,
                AdminActionType.UNSUSPEND_USER,
                userId,
                TARGET_ENTITY_TYPE,
                userId,
                null,
                AUTOMATIC_REASON,
                null);
        log.info("Suspension lapsed; account returned to active: userId={}", userId);
    }
}
