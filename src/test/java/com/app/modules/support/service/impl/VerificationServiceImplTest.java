package com.app.modules.support.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
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
import com.app.common.response.UserSummaryResponse;
import com.app.modules.admin.dto.response.AdminActionResponse;
import com.app.modules.admin.enums.AdminActionType;
import com.app.modules.admin.service.AdminActionRecorder;
import com.app.modules.admin.service.impl.AdminAuthorizationServiceImpl;
import com.app.modules.notification.service.NotificationService;
import com.app.modules.support.dto.request.CreateVerificationRequest;
import com.app.modules.support.dto.request.VerificationDecisionRequest;
import com.app.modules.support.entity.SupportTicket;
import com.app.modules.support.entity.VerificationCategory;
import com.app.modules.support.entity.VerificationRequest;
import com.app.modules.support.enums.SupportCategory;
import com.app.modules.support.enums.SupportTicketStatus;
import com.app.modules.support.repository.SupportTicketRepository;
import com.app.modules.support.repository.VerificationCategoryRepository;
import com.app.modules.support.repository.VerificationRequestRepository;
import com.app.modules.support.service.SupportAuthorizationService;
import com.app.modules.users.entity.User;
import com.app.modules.users.entity.UserVerification;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.enums.VerificationRevocationActor;
import com.app.modules.users.repository.UserRepository;
import com.app.modules.users.repository.UserVerificationRepository;
import com.app.modules.users.service.UserSummaryService;

@ExtendWith(MockitoExtension.class)
class VerificationServiceImplTest {

    private static final UUID REQUESTER = UUID.randomUUID();
    private static final UUID MODERATOR = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID PLAIN_USER = UUID.randomUUID();
    private static final UUID TICKET = UUID.randomUUID();
    private static final UUID AUDIT = UUID.randomUUID();

    @Mock private SupportTicketRepository supportTicketRepository;
    @Mock private VerificationRequestRepository verificationRequestRepository;
    @Mock private VerificationCategoryRepository verificationCategoryRepository;
    @Mock private UserVerificationRepository userVerificationRepository;
    @Mock private SupportAuthorizationService supportAuthorizationService;
    @Mock private AdminActionRecorder adminActionRecorder;
    @Mock private NotificationService notificationService;
    @Mock private UserRepository userRepository;
    @Mock private UserSummaryService userSummaryService;
    @Mock private OutboxService outboxService;

    private VerificationServiceImpl service;

    @BeforeEach
    void setUp() {
        // The real authorization implementation rather than a mock: the point of several of these
        // tests is which roles it admits, and a stubbed evaluator would only assert the stub.
        service =
                new VerificationServiceImpl(
                        supportTicketRepository,
                        verificationRequestRepository,
                        verificationCategoryRepository,
                        userVerificationRepository,
                        supportAuthorizationService,
                        new AdminAuthorizationServiceImpl(userRepository),
                        adminActionRecorder,
                        notificationService,
                        userRepository,
                        userSummaryService,
                        outboxService);
    }

    @Test
    void submit_twoEvidenceFields_refusesWithItsOwnCode() {
        stubUser(REQUESTER, UserRole.USER, UserStatus.ACTIVE);

        assertThatThrownBy(() -> service.submit(REQUESTER, request("music", "Site", "Press", null)))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ApiErrorCode.VERIFICATION_INSUFFICIENT_EVIDENCE);

        // Nothing is written, and the category is never even looked up: the caller's own error is
        // answered before any state is read.
        verify(supportTicketRepository, never()).save(any());
        verify(verificationRequestRepository, never()).save(any());
    }

    @Test
    void submit_whitespaceOnlyEvidence_doesNotCountTowardsTheMinimum() {
        stubUser(REQUESTER, UserRole.USER, UserStatus.ACTIVE);

        assertThatThrownBy(
                        () ->
                                service.submit(
                                        REQUESTER, request("music", "Site", "Press", "   \t  ")))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ApiErrorCode.VERIFICATION_INSUFFICIENT_EVIDENCE);
    }

    @Test
    void submit_threeEvidenceFields_writesTicketAndRequestWithTheCount() {
        stubUser(REQUESTER, UserRole.USER, UserStatus.ACTIVE);
        when(verificationCategoryRepository.findById("music")).thenReturn(Optional.of(category()));
        when(userVerificationRepository.findActive(REQUESTER)).thenReturn(Optional.empty());
        when(userVerificationRepository.findHistory(REQUESTER)).thenReturn(List.of());
        when(supportTicketRepository.hasOpenVerificationRequest(REQUESTER)).thenReturn(false);
        when(supportTicketRepository.save(any())).thenAnswer(inv -> withId(inv.getArgument(0)));
        when(supportTicketRepository.findVerificationTickets(eq(REQUESTER), any()))
                .thenReturn(List.of());

        service.submit(REQUESTER, request("music", "Site", "Press", "A note"));

        ArgumentCaptor<VerificationRequest> captor =
                ArgumentCaptor.forClass(VerificationRequest.class);
        verify(verificationRequestRepository).save(captor.capture());
        assertThat(captor.getValue().getEvidenceFieldCount()).isEqualTo((short) 3);
        assertThat(captor.getValue().getCategoryKey()).isEqualTo("music");

        ArgumentCaptor<SupportTicket> ticketCaptor = ArgumentCaptor.forClass(SupportTicket.class);
        verify(supportTicketRepository).save(ticketCaptor.capture());
        assertThat(ticketCaptor.getValue().getCategory())
                .isEqualTo(SupportCategory.VERIFICATION_REQUEST);
    }

    @Test
    void submit_alreadyVerified_refuses() {
        stubUser(REQUESTER, UserRole.USER, UserStatus.ACTIVE);
        when(verificationCategoryRepository.findById("music")).thenReturn(Optional.of(category()));
        when(userVerificationRepository.findActive(REQUESTER))
                .thenReturn(Optional.of(activeGrant()));

        assertThatThrownBy(
                        () -> service.submit(REQUESTER, request("music", "Site", "Press", "Note")))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ApiErrorCode.VERIFICATION_ALREADY_VERIFIED);
    }

    @Test
    void approve_byModerator_grantsTheBadge() {
        stubDecisionContext(MODERATOR, UserRole.MODERATOR);

        service.approve(MODERATOR, TICKET, new VerificationDecisionRequest("Looks right", null));

        // The appeal-requires-admin rule must not extend to verification. A moderator granting a
        // badge records no verdict they cannot execute.
        ArgumentCaptor<UserVerification> grant = ArgumentCaptor.forClass(UserVerification.class);
        verify(userVerificationRepository).save(grant.capture());
        assertThat(grant.getValue().getUserId()).isEqualTo(REQUESTER);
        assertThat(grant.getValue().getGrantedBy()).isEqualTo(MODERATOR);
        assertThat(grant.getValue().getCategoryKey()).isEqualTo("music");
        verify(adminActionRecorder)
                .record(
                        eq(MODERATOR),
                        eq(AdminActionType.GRANT_VERIFICATION),
                        eq(REQUESTER),
                        anyString(),
                        eq(TICKET),
                        isNull(),
                        anyString(),
                        any());
    }

    @Test
    void approve_byAdministrator_grantsTheBadge() {
        stubDecisionContext(ADMIN, UserRole.ADMIN);

        service.approve(ADMIN, TICKET, new VerificationDecisionRequest("Confirmed", null));

        verify(userVerificationRepository).save(any(UserVerification.class));
    }

    @Test
    void approve_byPlainUser_refusesAndGrantsNothing() {
        stubUser(PLAIN_USER, UserRole.USER, UserStatus.ACTIVE);

        assertThatThrownBy(
                        () ->
                                service.approve(
                                        PLAIN_USER,
                                        TICKET,
                                        new VerificationDecisionRequest("Sure", null)))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ApiErrorCode.FORBIDDEN);

        verify(userVerificationRepository, never()).save(any());
    }

    @Test
    void reject_byModerator_closesTheTicketAndGrantsNothing() {
        stubDecisionContext(MODERATOR, UserRole.MODERATOR);

        service.reject(MODERATOR, TICKET, new VerificationDecisionRequest("Not enough", null));

        verify(userVerificationRepository, never()).save(any(UserVerification.class));
        verify(adminActionRecorder)
                .record(
                        eq(MODERATOR),
                        eq(AdminActionType.REJECT_VERIFICATION),
                        eq(REQUESTER),
                        anyString(),
                        eq(TICKET),
                        isNull(),
                        anyString(),
                        any());
    }

    @Test
    void revoke_byModerator_isRecordedAsAModeratorDecision() {
        stubUser(MODERATOR, UserRole.MODERATOR, UserStatus.ACTIVE);
        stubUser(REQUESTER, UserRole.USER, UserStatus.ACTIVE);
        UserVerification grant = activeGrant();
        when(userVerificationRepository.findActive(REQUESTER)).thenReturn(Optional.of(grant));
        when(adminActionRecorder.record(
                        any(), any(), any(), anyString(), any(), isNull(), anyString(), any()))
                .thenReturn(auditResponse());

        service.revoke(MODERATOR, REQUESTER, new VerificationDecisionRequest("Misuse", null));

        assertThat(grant.getRevokedAt()).isNotNull();
        assertThat(grant.getRevocationActor()).isEqualTo(VerificationRevocationActor.MODERATOR);
        assertThat(grant.getRevokedBy()).isEqualTo(MODERATOR);
    }

    @Test
    void revoke_byPlainUser_refuses() {
        stubUser(PLAIN_USER, UserRole.USER, UserStatus.ACTIVE);

        assertThatThrownBy(
                        () ->
                                service.revoke(
                                        PLAIN_USER,
                                        REQUESTER,
                                        new VerificationDecisionRequest("Because", null)))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ApiErrorCode.FORBIDDEN);
    }

    @Test
    void applyStatusChange_suspended_revokesAutomaticallyWithNoActor() {
        UserVerification grant = activeGrant();
        when(userVerificationRepository.findActive(REQUESTER)).thenReturn(Optional.of(grant));
        when(adminActionRecorder.record(
                        isNull(), any(), any(), anyString(), any(), isNull(), anyString(), any()))
                .thenReturn(auditResponse());

        boolean revoked = service.applyStatusChange(REQUESTER, UserStatus.SUSPENDED);

        assertThat(revoked).isTrue();
        assertThat(grant.getRevocationActor()).isEqualTo(VerificationRevocationActor.SYSTEM);
        // A null actor on the audit row is what distinguishes an automatic revocation from a
        // moderator's decision, the same convention the discipline ladder already uses.
        assertThat(grant.getRevokedBy()).isNull();
        verify(adminActionRecorder)
                .record(
                        isNull(),
                        eq(AdminActionType.REVOKE_VERIFICATION),
                        eq(REQUESTER),
                        anyString(),
                        any(),
                        isNull(),
                        anyString(),
                        any());
        // A status-driven revocation is a side effect, not a request, so it must not put a row in
        // anybody's queue.
        verify(supportTicketRepository, never()).save(any());
    }

    @Test
    void applyStatusChange_banned_revokesAutomatically() {
        UserVerification grant = activeGrant();
        when(userVerificationRepository.findActive(REQUESTER)).thenReturn(Optional.of(grant));
        when(adminActionRecorder.record(
                        isNull(), any(), any(), anyString(), any(), isNull(), anyString(), any()))
                .thenReturn(auditResponse());

        assertThat(service.applyStatusChange(REQUESTER, UserStatus.BANNED)).isTrue();
        assertThat(grant.getRevocationActor()).isEqualTo(VerificationRevocationActor.SYSTEM);
    }

    @Test
    void applyStatusChange_deactivated_retainsTheBadge() {
        // Deactivation is voluntary and the account is invisible while it lasts, so withdrawing a
        // badge there would punish something that is not an offence.
        assertThat(service.applyStatusChange(REQUESTER, UserStatus.DEACTIVATED)).isFalse();
        verify(userVerificationRepository, never()).save(any());
        verify(adminActionRecorder, never())
                .record(any(), any(), any(), anyString(), any(), any(), anyString(), any());
    }

    @Test
    void applyStatusChange_active_retainsTheBadge() {
        assertThat(service.applyStatusChange(REQUESTER, UserStatus.ACTIVE)).isFalse();
        verify(userVerificationRepository, never()).save(any());
    }

    @Test
    void applyStatusChange_unverifiedAccount_doesNothing() {
        when(userVerificationRepository.findActive(REQUESTER)).thenReturn(Optional.empty());

        assertThat(service.applyStatusChange(REQUESTER, UserStatus.BANNED)).isFalse();
        verify(adminActionRecorder, never())
                .record(any(), any(), any(), anyString(), any(), any(), anyString(), any());
    }

    private void stubDecisionContext(UUID actorId, UserRole actorRole) {
        stubUser(actorId, actorRole, UserStatus.ACTIVE);
        stubUser(REQUESTER, UserRole.USER, UserStatus.ACTIVE);
        SupportTicket ticket = ticket();
        when(supportTicketRepository.findStaffVisible(TICKET)).thenReturn(Optional.of(ticket));
        when(supportTicketRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(verificationRequestRepository.findById(TICKET))
                .thenReturn(Optional.of(verificationRequest()));
        when(userVerificationRepository.findHistory(REQUESTER)).thenReturn(List.of());
        when(adminActionRecorder.record(
                        any(), any(), any(), anyString(), any(), isNull(), anyString(), any()))
                .thenReturn(auditResponse());
        lenient()
                .when(userSummaryService.loadSummaries(any()))
                .thenReturn(
                        Map.of(
                                REQUESTER,
                                new UserSummaryResponse(
                                        REQUESTER, "req", "Requester", null, false, null)));
    }

    private void stubUser(UUID id, UserRole role, UserStatus status) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setStatus(status);
        user.setEmail(id + "@example.com");
        lenient().when(userRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(user));
    }

    private static CreateVerificationRequest request(
            String category, String website, String press, String note) {
        return new CreateVerificationRequest(
                category, "Claimed Name", website, null, null, null, press, null, note);
    }

    private static VerificationCategory category() {
        VerificationCategory category = new VerificationCategory();
        category.setCategoryKey("music");
        category.setDisplayName("Music");
        category.setCovers("Singer, rapper");
        category.setIconKey("music-note");
        category.setEnabled(true);
        return category;
    }

    private static SupportTicket ticket() {
        return SupportTicket.builder()
                .id(TICKET)
                .userId(REQUESTER)
                .contactEmail("req@example.com")
                .category(SupportCategory.VERIFICATION_REQUEST)
                .subject("Verification request")
                .body("body")
                .status(SupportTicketStatus.OPEN)
                .build();
    }

    private static VerificationRequest verificationRequest() {
        return VerificationRequest.builder()
                .ticketId(TICKET)
                .userId(REQUESTER)
                .categoryKey("music")
                .claimedName("Claimed Name")
                .evidenceFieldCount((short) 3)
                .build();
    }

    private static UserVerification activeGrant() {
        return UserVerification.builder()
                .id(UUID.randomUUID())
                .userId(REQUESTER)
                .categoryKey("music")
                .grantedAt(OffsetDateTime.now())
                .build();
    }

    private static AdminActionResponse auditResponse() {
        return new AdminActionResponse(
                AUDIT,
                null,
                AdminActionType.GRANT_VERIFICATION,
                REQUESTER,
                "verification_request",
                TICKET,
                null,
                "reason",
                Map.of(),
                OffsetDateTime.now());
    }

    private static SupportTicket withId(SupportTicket ticket) {
        ticket.setId(TICKET);
        return ticket;
    }
}
