package com.app.modules.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
import com.app.modules.admin.dto.request.AdminActionRequest;
import com.app.modules.admin.dto.request.AdminWarnUserRequest;
import com.app.modules.admin.dto.response.AdminActionResponse;
import com.app.modules.admin.entity.UserStrike;
import com.app.modules.admin.entity.UserWarning;
import com.app.modules.admin.enums.AdminActionType;
import com.app.modules.admin.mapper.UserDisciplineMapper;
import com.app.modules.admin.repository.AdminUserRepository;
import com.app.modules.admin.repository.ReportReasonConfigReader;
import com.app.modules.admin.repository.UserStrikeRepository;
import com.app.modules.admin.repository.UserWarningRepository;
import com.app.modules.admin.service.AdminActionRecorder;
import com.app.modules.admin.service.AdminAuthorizationService;
import com.app.modules.support.service.VerificationService;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class UserDisciplineServiceImplTest {

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID TARGET_ID = UUID.randomUUID();
    private static final AdminWarnUserRequest REQUEST =
            new AdminWarnUserRequest("spam", "Bulk direct messages");

    @Mock private UserWarningRepository userWarningRepository;
    @Mock private UserStrikeRepository userStrikeRepository;
    @Mock private AdminUserRepository adminUserRepository;
    @Mock private UserRepository userRepository;
    @Mock private ReportReasonConfigReader reportReasonConfigReader;
    @Mock private AdminActionRecorder adminActionRecorder;
    @Mock private UserDisciplineMapper userDisciplineMapper;
    @Mock private OutboxService outboxService;
    @Mock private AdminAuthorizationService adminAuthorizationService;
    @Mock private VerificationService verificationService;

    private UserDisciplineServiceImpl service;

    @BeforeEach
    void setUp() {
        service =
                new UserDisciplineServiceImpl(
                        userWarningRepository,
                        userStrikeRepository,
                        adminUserRepository,
                        userRepository,
                        reportReasonConfigReader,
                        adminActionRecorder,
                        userDisciplineMapper,
                        outboxService,
                        adminAuthorizationService,
                        verificationService);
        lenient()
                .when(reportReasonConfigReader.findEnabledByReasonKey("spam"))
                .thenReturn(Optional.of(true));
        lenient()
                .when(
                        adminActionRecorder.record(
                                any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(auditResponse());
        lenient()
                .when(userWarningRepository.saveAndFlush(any()))
                .thenAnswer(
                        invocation -> {
                            UserWarning warning = invocation.getArgument(0);
                            warning.setId(UUID.randomUUID());
                            return warning;
                        });
        lenient()
                .when(userStrikeRepository.saveAndFlush(any()))
                .thenAnswer(
                        invocation -> {
                            UserStrike strike = invocation.getArgument(0);
                            strike.setId(UUID.randomUUID());
                            return strike;
                        });
        lenient()
                .when(userWarningRepository.findActiveWarningIds(any(), any(), any()))
                .thenReturn(List.of());
    }

    @Test
    void issueWarning_selfTarget_throwsSelfActionNotAllowed() {
        assertThatThrownBy(() -> service.issueWarning(ACTOR_ID, ACTOR_ID, REQUEST))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.ADMIN_SELF_ACTION_NOT_ALLOWED);
        verify(adminUserRepository, never()).lockForDiscipline(any());
    }

    @Test
    void issueWarning_disabledReason_throwsReasonDisabled() {
        when(reportReasonConfigReader.findEnabledByReasonKey("nudity"))
                .thenReturn(Optional.of(false));

        assertThatThrownBy(
                        () ->
                                service.issueWarning(
                                        ACTOR_ID,
                                        TARGET_ID,
                                        new AdminWarnUserRequest("nudity", "note")))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.WARNING_REASON_DISABLED);
    }

    @Test
    void issueWarning_unknownReason_throwsReasonDisabled() {
        when(reportReasonConfigReader.findEnabledByReasonKey("invented"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.issueWarning(
                                        ACTOR_ID,
                                        TARGET_ID,
                                        new AdminWarnUserRequest("invented", "note")))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.WARNING_REASON_DISABLED);
    }

    @Test
    void issueWarning_moderatorTarget_throwsTargetNotWarnable() {
        stubTarget(UserRole.MODERATOR, UserStatus.ACTIVE, null);

        assertThatThrownBy(() -> service.issueWarning(ACTOR_ID, TARGET_ID, REQUEST))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.ADMIN_TARGET_NOT_WARNABLE);
        verify(userWarningRepository, never()).saveAndFlush(any());
    }

    @Test
    void issueWarning_administratorTarget_throwsTargetNotWarnable() {
        stubTarget(UserRole.ADMIN, UserStatus.ACTIVE, null);

        assertThatThrownBy(() -> service.issueWarning(ACTOR_ID, TARGET_ID, REQUEST))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.ADMIN_TARGET_NOT_WARNABLE);
    }

    @Test
    void issueWarning_missingAccount_throwsUserNotFound() {
        when(adminUserRepository.lockForDiscipline(TARGET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueWarning(ACTOR_ID, TARGET_ID, REQUEST))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.USER_NOT_FOUND);
    }

    @Test
    void issueWarning_belowThreshold_issuesNoStrike() {
        User target = stubTarget(UserRole.USER, UserStatus.ACTIVE, null);
        stubActiveWarnings(2);

        var result = service.issueWarning(ACTOR_ID, TARGET_ID, REQUEST);

        assertThat(result.strikeIssued()).isFalse();
        assertThat(result.activeWarningCount()).isEqualTo(2);
        assertThat(target.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(userStrikeRepository, never()).saveAndFlush(any());
    }

    @Test
    void issueWarning_thirdWarning_issuesStrikeOneAndSuspendsForSevenDays() {
        User target = stubTarget(UserRole.USER, UserStatus.ACTIVE, null);
        stubActiveWarnings(3);
        stubActiveStrikes(0);

        var result = service.issueWarning(ACTOR_ID, TARGET_ID, REQUEST);

        assertThat(result.strikeIssued()).isTrue();
        assertThat(result.activeWarningCount()).isZero();
        assertThat(target.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(target.getSuspendedUntil()).isCloseTo(daysFromNow(7), within10Minutes());
        assertThat(capturedStrike().getStrikeNumber()).isEqualTo((short) 1);
    }

    @Test
    void issueWarning_strikeOneSuspension_withdrawsTheVerifiedBadge() {
        // The ladder is the second writer of users.status. The badge withdrawal was wired into the
        // administrator's own endpoint only, so a laddered suspension used to leave the badge
        // standing while a directly-issued one withdrew it: same account, same resulting status,
        // opposite badge outcome.
        User target = stubTarget(UserRole.USER, UserStatus.ACTIVE, null);
        stubActiveWarnings(3);
        stubActiveStrikes(0);

        service.issueWarning(ACTOR_ID, TARGET_ID, REQUEST);

        verify(verificationService).applyStatusChange(TARGET_ID, UserStatus.SUSPENDED);
    }

    @Test
    void issueWarning_thirdStrikeBan_withdrawsTheVerifiedBadge() {
        // A three-strike ban is the strongest action in the system and was the one leaving the
        // platform's identity claim intact.
        User target = stubTarget(UserRole.USER, UserStatus.ACTIVE, null);
        stubActiveWarnings(3);
        stubActiveStrikes(2);

        service.issueWarning(ACTOR_ID, TARGET_ID, REQUEST);

        verify(verificationService).applyStatusChange(TARGET_ID, UserStatus.BANNED);
    }

    @Test
    void issueWarning_consequenceNotStrongerThanCurrent_leavesTheBadgeAlone() {
        // No status transition happened, so there is nothing for the badge to follow. Calling the
        // revocation here would withdraw a badge on an account whose penalty did not change.
        OffsetDateTime existingUntil = daysFromNow(30);
        User target = stubTarget(UserRole.USER, UserStatus.SUSPENDED, existingUntil);
        stubActiveWarnings(3);
        stubActiveStrikes(0);

        service.issueWarning(ACTOR_ID, TARGET_ID, REQUEST);

        verify(verificationService, never()).applyStatusChange(any(), any());
    }

    @Test
    void issueWarning_secondStrike_suspendsForThirtyDays() {
        User target = stubTarget(UserRole.USER, UserStatus.ACTIVE, null);
        stubActiveWarnings(3);
        stubActiveStrikes(1);

        service.issueWarning(ACTOR_ID, TARGET_ID, REQUEST);

        assertThat(target.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(target.getSuspendedUntil()).isCloseTo(daysFromNow(30), within10Minutes());
        assertThat(capturedStrike().getStrikeNumber()).isEqualTo((short) 2);
    }

    @Test
    void issueWarning_thirdStrike_bansPermanently() {
        User target = stubTarget(UserRole.USER, UserStatus.ACTIVE, null);
        stubActiveWarnings(3);
        stubActiveStrikes(2);

        service.issueWarning(ACTOR_ID, TARGET_ID, REQUEST);

        assertThat(target.getStatus()).isEqualTo(UserStatus.BANNED);
        assertThat(target.getSuspendedUntil()).isNull();
        assertThat(capturedStrike().getStrikeNumber()).isEqualTo((short) 3);
    }

    @Test
    void issueWarning_fourthStrike_insertsAndBansPermanently() {
        User target = stubTarget(UserRole.USER, UserStatus.ACTIVE, null);
        stubActiveWarnings(3);
        stubActiveStrikes(3);

        service.issueWarning(ACTOR_ID, TARGET_ID, REQUEST);

        assertThat(target.getStatus()).isEqualTo(UserStatus.BANNED);
        assertThat(capturedStrike().getStrikeNumber()).isEqualTo((short) 4);
    }

    @Test
    void issueWarning_strikeNumberFollowsActiveStrikesOnly() {
        stubTarget(UserRole.USER, UserStatus.ACTIVE, null);
        stubActiveWarnings(3);
        // Two strikes exist but one was revoked, so the count is one and the next number is two.
        stubActiveStrikes(1);

        service.issueWarning(ACTOR_ID, TARGET_ID, REQUEST);

        assertThat(capturedStrike().getStrikeNumber()).isEqualTo((short) 2);
    }

    @Test
    void issueWarning_strikeOneOnLongerSuspension_leavesTheStrongerPenalty() {
        OffsetDateTime existingUntil = daysFromNow(30);
        User target = stubTarget(UserRole.USER, UserStatus.SUSPENDED, existingUntil);
        stubActiveWarnings(3);
        stubActiveStrikes(0);

        var result = service.issueWarning(ACTOR_ID, TARGET_ID, REQUEST);

        assertThat(result.strikeIssued()).isTrue();
        assertThat(target.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(target.getSuspendedUntil()).isEqualTo(existingUntil);
        assertThat(result.resultingStatus()).isEqualTo("suspended");
        verify(userStrikeRepository).saveAndFlush(any());
    }

    @Test
    void issueWarning_strikeTwoOnShorterSuspension_extendsIt() {
        User target = stubTarget(UserRole.USER, UserStatus.SUSPENDED, daysFromNow(3));
        stubActiveWarnings(3);
        stubActiveStrikes(1);

        service.issueWarning(ACTOR_ID, TARGET_ID, REQUEST);

        assertThat(target.getSuspendedUntil()).isCloseTo(daysFromNow(30), within10Minutes());
    }

    @Test
    void issueWarning_strikeOneOnIndefiniteSuspension_leavesItIndefinite() {
        User target = stubTarget(UserRole.USER, UserStatus.SUSPENDED, null);
        stubActiveWarnings(3);
        stubActiveStrikes(0);

        var result = service.issueWarning(ACTOR_ID, TARGET_ID, REQUEST);

        assertThat(target.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(target.getSuspendedUntil()).isNull();
        assertThat(result.strikeIssued()).isTrue();
    }

    @Test
    void issueWarning_strikeOneOnBannedAccount_leavesTheBan() {
        User target = stubTarget(UserRole.USER, UserStatus.BANNED, null);
        stubActiveWarnings(3);
        stubActiveStrikes(0);

        var result = service.issueWarning(ACTOR_ID, TARGET_ID, REQUEST);

        assertThat(target.getStatus()).isEqualTo(UserStatus.BANNED);
        assertThat(result.resultingStatus()).isEqualTo("banned");
        verify(userStrikeRepository).saveAndFlush(any());
    }

    @Test
    void issueWarning_strikeThreeOnIndefiniteSuspension_upgradesToBan() {
        User target = stubTarget(UserRole.USER, UserStatus.SUSPENDED, null);
        stubActiveWarnings(3);
        stubActiveStrikes(2);

        service.issueWarning(ACTOR_ID, TARGET_ID, REQUEST);

        assertThat(target.getStatus()).isEqualTo(UserStatus.BANNED);
    }

    @Test
    void issueWarning_strikeThreeOnBannedAccount_leavesTheBan() {
        User target = stubTarget(UserRole.USER, UserStatus.BANNED, null);
        stubActiveWarnings(3);
        stubActiveStrikes(2);

        service.issueWarning(ACTOR_ID, TARGET_ID, REQUEST);

        assertThat(target.getStatus()).isEqualTo(UserStatus.BANNED);
        verify(adminUserRepository, never()).save(any());
    }

    @Test
    void issueWarning_strikeOneOnDeactivatedAccount_doesNotReactivateIt() {
        User target = stubTarget(UserRole.USER, UserStatus.DEACTIVATED, null);
        stubActiveWarnings(3);
        stubActiveStrikes(0);

        service.issueWarning(ACTOR_ID, TARGET_ID, REQUEST);

        assertThat(target.getStatus()).isEqualTo(UserStatus.DEACTIVATED);
    }

    @Test
    void issueWarning_strike_writesASecondAuditRowWithNoActor() {
        stubTarget(UserRole.USER, UserStatus.ACTIVE, null);
        stubActiveWarnings(3);
        stubActiveStrikes(0);

        service.issueWarning(ACTOR_ID, TARGET_ID, REQUEST);

        ArgumentCaptor<UUID> actor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<AdminActionType> type = ArgumentCaptor.forClass(AdminActionType.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(adminActionRecorder, org.mockito.Mockito.times(2))
                .record(
                        actor.capture(),
                        type.capture(),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        metadata.capture());

        assertThat(type.getAllValues())
                .containsExactly(AdminActionType.WARN_USER, AdminActionType.ISSUE_STRIKE);
        assertThat(actor.getAllValues().get(0)).isEqualTo(ACTOR_ID);
        assertThat(actor.getAllValues().get(1)).isNull();
        assertThat(metadata.getAllValues().get(1))
                .containsEntry("triggeredByModeratorId", ACTOR_ID.toString())
                .containsEntry("strikeNumber", (short) 1)
                .containsEntry("consequenceApplied", true);
    }

    @Test
    void issueWarning_always_enqueuesTheNotificationInTheSameTransaction() {
        stubTarget(UserRole.USER, UserStatus.ACTIVE, null);
        stubActiveWarnings(1);

        service.issueWarning(ACTOR_ID, TARGET_ID, REQUEST);

        verify(outboxService)
                .enqueue(
                        eq("user.warned.v1"),
                        eq("user.warned.v1"),
                        eq("user"),
                        eq(TARGET_ID),
                        eq(null),
                        any());
    }

    private User stubTarget(UserRole role, UserStatus status, OffsetDateTime suspendedUntil) {
        User target =
                User.builder()
                        .id(TARGET_ID)
                        .role(role)
                        .status(status)
                        .suspendedUntil(suspendedUntil)
                        .build();
        when(adminUserRepository.lockForDiscipline(TARGET_ID)).thenReturn(Optional.of(target));
        return target;
    }

    private void stubActiveWarnings(long count) {
        when(userWarningRepository.countActiveWarnings(eq(TARGET_ID), any(), any()))
                .thenReturn(count);
    }

    private void stubActiveStrikes(long count) {
        when(userStrikeRepository.countActiveStrikes(TARGET_ID)).thenReturn(count);
    }

    private UserStrike capturedStrike() {
        ArgumentCaptor<UserStrike> captor = ArgumentCaptor.forClass(UserStrike.class);
        verify(userStrikeRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private static OffsetDateTime daysFromNow(int days) {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(days);
    }

    private static org.assertj.core.data.TemporalUnitOffset within10Minutes() {
        return org.assertj.core.api.Assertions.within(10, java.time.temporal.ChronoUnit.MINUTES);
    }

    private static AdminActionResponse auditResponse() {
        return new AdminActionResponse(
                UUID.randomUUID(),
                null,
                AdminActionType.WARN_USER,
                TARGET_ID,
                "user",
                TARGET_ID,
                null,
                "reason",
                Map.of(),
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    // Both revocations previously read no actor role at all: their only gate was the per-method
    // annotation narrowing the controller's wider moderator-and-administrator class annotation.
    @Test
    void revokeWarning_actorNotAdministrator_refusedBeforeAnyRead() {
        UUID warningId = UUID.randomUUID();
        doThrow(new AppException(ApiErrorCode.FORBIDDEN))
                .when(adminAuthorizationService)
                .assertActorIsAdministrator(ACTOR_ID);

        assertThatThrownBy(
                        () ->
                                service.revokeWarning(
                                        ACTOR_ID, warningId, new AdminActionRequest("note", null)))
                .isInstanceOf(AppException.class)
                .satisfies(
                        e ->
                                assertThat(((AppException) e).getErrorCode())
                                        .isEqualTo(ApiErrorCode.FORBIDDEN));

        verify(userWarningRepository, never()).findById(any());
    }

    @Test
    void revokeStrike_actorNotAdministrator_refusedBeforeAnyRead() {
        UUID strikeId = UUID.randomUUID();
        doThrow(new AppException(ApiErrorCode.FORBIDDEN))
                .when(adminAuthorizationService)
                .assertActorIsAdministrator(ACTOR_ID);

        assertThatThrownBy(
                        () ->
                                service.revokeStrike(
                                        ACTOR_ID, strikeId, new AdminActionRequest("note", null)))
                .isInstanceOf(AppException.class)
                .satisfies(
                        e ->
                                assertThat(((AppException) e).getErrorCode())
                                        .isEqualTo(ApiErrorCode.FORBIDDEN));

        verify(userStrikeRepository, never()).findById(any());
    }
}
