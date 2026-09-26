package com.app.modules.admin.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import com.app.common.messaging.exception.PermanentMessageException;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.modules.admin.enums.AdminActionType;
import com.app.modules.auth.entity.UserCredential;
import com.app.modules.auth.repository.UserCredentialRepository;
import com.app.modules.mail.config.MailProperties;
import com.app.modules.mail.entity.EmailDelivery;
import com.app.modules.mail.enums.EmailDeliveryStatus;
import com.app.modules.mail.enums.ModerationMailTemplate;
import com.app.modules.mail.repository.EmailDeliveryRepository;
import com.app.modules.mail.service.ModerationMailThrottle;
import com.app.modules.mail.service.impl.AbstractTemplateMailSender;
import com.app.modules.support.service.SupportTokenService;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class ModerationMailEventHandlerTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMIN_ACTION_ID = UUID.randomUUID();
    private static final String EMAIL = "target@example.com";
    private static final OffsetDateTime OCCURRED_AT =
            OffsetDateTime.of(2026, 3, 14, 9, 0, 0, 0, ZoneOffset.UTC);

    @Mock private UserRepository userRepository;
    @Mock private UserCredentialRepository userCredentialRepository;
    @Mock private AbstractTemplateMailSender mailSender;
    @Mock private ModerationMailThrottle throttle;
    @Mock private EmailDeliveryRepository emailDeliveryRepository;
    @Mock private SupportTokenService supportTokenService;

    private ModerationMailEventHandler handler;

    @BeforeEach
    void setUp() {
        MailProperties mailProperties = new MailProperties();
        mailProperties.setAppName("Luvax");
        mailProperties.setFrontendBaseUrl("https://app.example.com");
        handler =
                new ModerationMailEventHandler(
                        userRepository,
                        userCredentialRepository,
                        mailSender,
                        throttle,
                        emailDeliveryRepository,
                        mailProperties,
                        supportTokenService);
        lenient()
                .when(emailDeliveryRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // The point of this whole path. AuthMailEventHandler refuses any account that is not ACTIVE,
    // which would suppress every notice that matters, because a banned account is exactly who a ban
    // notice is for.
    @Test
    void handle_bannedAccount_stillSendsTheNotice() {
        stubEligibleUser(UserStatus.BANNED);

        handler.handle(event(AdminActionType.BAN_USER, null));

        verify(mailSender)
                .sendModerationNotice(eq(ModerationMailTemplate.ACCOUNT_BANNED), any(), eq(EMAIL));
    }

    @Test
    void handle_suspendedAccount_stillSendsTheNotice() {
        stubEligibleUser(UserStatus.SUSPENDED);

        handler.handle(event(AdminActionType.SUSPEND_USER, OCCURRED_AT.plusDays(7).toString()));

        verify(mailSender)
                .sendModerationNotice(
                        eq(ModerationMailTemplate.ACCOUNT_SUSPENDED), any(), eq(EMAIL));
    }

    @Test
    void handle_deactivatedAccount_stillSendsTheNotice() {
        stubEligibleUser(UserStatus.DEACTIVATED);

        handler.handle(event(AdminActionType.REMOVE_POST, null));

        verify(mailSender)
                .sendModerationNotice(eq(ModerationMailTemplate.POST_REMOVED), any(), eq(EMAIL));
    }

    @Test
    void handle_suspensionNotice_carriesTheEndDate() {
        stubEligibleUser(UserStatus.SUSPENDED);

        handler.handle(
                event(
                        AdminActionType.SUSPEND_USER,
                        OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC).toString()));

        assertThat(capturedVariables()).containsEntry("suspendedUntil", "1 April 2026");
    }

    @Test
    void handle_anyNotice_carriesTheDateItHappened() {
        stubEligibleUser(UserStatus.BANNED);

        handler.handle(event(AdminActionType.BAN_USER, null));

        assertThat(capturedVariables()).containsEntry("occurredAt", "14 March 2026");
    }

    // The reason on the audit row is written for colleagues. It never reaches the recipient, and
    // the
    // payload never carries it in the first place.
    @Test
    void handle_anyNotice_neverCarriesAReasonOrAnActor() {
        stubEligibleUser(UserStatus.BANNED);

        handler.handle(event(AdminActionType.BAN_USER, null));

        assertThat(capturedVariables()).doesNotContainKeys("reason", "actorId", "reportId");
    }

    @Test
    void handle_reinstatement_suppressesTheCommunityStandardsLine() {
        stubEligibleUser(UserStatus.ACTIVE);

        handler.handle(event(AdminActionType.UNBAN_USER, null));

        assertThat(capturedVariables()).containsEntry("showStandardsLine", false);
    }

    @Test
    void handle_unverifiedEmail_skipsSendAndRecordsIt() {
        stubUser(UserStatus.BANNED);
        stubCredential(false);

        handler.handle(event(AdminActionType.BAN_USER, null));

        verify(mailSender, never()).sendModerationNotice(any(), any(), anyString());
        assertThat(capturedDeliveries())
                .extracting(EmailDelivery::getStatus)
                .containsExactly(EmailDeliveryStatus.SKIPPED);
    }

    // An OAuth-only account has no credential row. The provider verified the address before it
    // reached this system, so the notice is sent rather than skipped.
    @Test
    void handle_oauthAccountWithNoCredentialRow_sendsTheNotice() {
        stubUser(UserStatus.BANNED);
        when(userCredentialRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(throttle.tryAcquire(EMAIL)).thenReturn(true);

        handler.handle(event(AdminActionType.BAN_USER, null));

        verify(mailSender).sendModerationNotice(any(), any(), eq(EMAIL));
    }

    @Test
    void handle_throttledRecipient_skipsSendAndRecordsIt() {
        stubUser(UserStatus.BANNED);
        stubCredential(true);
        when(throttle.tryAcquire(EMAIL)).thenReturn(false);

        handler.handle(event(AdminActionType.BAN_USER, null));

        verify(mailSender, never()).sendModerationNotice(any(), any(), anyString());
        assertThat(capturedDeliveries())
                .extracting(EmailDelivery::getStatus)
                .containsExactly(EmailDeliveryStatus.THROTTLED);
    }

    @Test
    void handle_successfulSend_recordsProviderMessageId() {
        stubEligibleUser(UserStatus.BANNED);
        when(mailSender.sendModerationNotice(any(), any(), anyString())).thenReturn("prov-123");

        handler.handle(event(AdminActionType.BAN_USER, null));

        assertThat(capturedDeliveries())
                .last()
                .satisfies(
                        delivery -> {
                            assertThat(delivery.getStatus()).isEqualTo(EmailDeliveryStatus.SENT);
                            assertThat(delivery.getProviderMessageId()).isEqualTo("prov-123");
                            assertThat(delivery.getAttemptCount()).isEqualTo(1);
                            assertThat(delivery.getAdminActionId()).isEqualTo(ADMIN_ACTION_ID);
                        });
    }

    @Test
    void handle_providerFailure_recordsFailureAndRethrows() {
        stubEligibleUser(UserStatus.BANNED);
        when(mailSender.sendModerationNotice(any(), any(), anyString()))
                .thenThrow(new IllegalStateException("provider down"));

        assertThatThrownBy(() -> handler.handle(event(AdminActionType.BAN_USER, null)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(capturedDeliveries())
                .last()
                .satisfies(
                        delivery -> {
                            assertThat(delivery.getStatus()).isEqualTo(EmailDeliveryStatus.FAILED);
                            assertThat(delivery.getErrorText()).isEqualTo("provider down");
                        });
    }

    // P7-BE-002. The suppression branch had no test at all, which is how the three mail lanes
    // drifted into three different answers for the same refusal. This pins the contract that all
    // of them now share: recorded as SKIPPED, and never rethrown, because rethrowing dead-letters
    // a message the deployment was configured never to send.
    @Test
    void handle_suppressedRecipient_recordsSkippedAndDoesNotRethrow() {
        stubEligibleUser(UserStatus.BANNED);
        when(mailSender.sendModerationNotice(any(), any(), anyString()))
                .thenThrow(new AppException(ApiErrorCode.MAIL_RECIPIENT_NOT_ALLOWED));

        handler.handle(event(AdminActionType.BAN_USER, null));

        assertThat(capturedDeliveries())
                .last()
                .satisfies(
                        delivery ->
                                assertThat(delivery.getStatus())
                                        .isEqualTo(EmailDeliveryStatus.SKIPPED));
    }

    @Test
    void handle_softDeletedAccount_isPermanentFailure() {
        when(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(event(AdminActionType.BAN_USER, null)))
                .isInstanceOf(PermanentMessageException.class);

        verify(mailSender, never()).sendModerationNotice(any(), any(), anyString());
    }

    @Test
    void handle_actionThatDoesNotMail_isPermanentFailure() {
        assertThatThrownBy(() -> handler.handle(event(AdminActionType.ISSUE_STRIKE, null)))
                .isInstanceOf(PermanentMessageException.class);

        verify(mailSender, never()).sendModerationNotice(any(), any(), anyString());
    }

    @Test
    void handle_missingUserId_isPermanentFailure() {
        DomainEventEnvelope event =
                new DomainEventEnvelope(
                        UUID.randomUUID(),
                        AdminEventTypes.MODERATION_NOTICE_REQUESTED_V1,
                        OCCURRED_AT,
                        null,
                        "user",
                        USER_ID,
                        Map.of("actionType", AdminActionType.BAN_USER.name()));

        assertThatThrownBy(() -> handler.handle(event))
                .isInstanceOf(PermanentMessageException.class);
    }

    // The six punitive actions carry a single-use appeal link. Minted at send time, so a notice
    // that
    // is throttled or skipped never leaves a live token behind.
    @Test
    void handle_banNotice_carriesAnAppealLink() {
        stubEligibleUser(UserStatus.BANNED);
        when(supportTokenService.createAppealToken(any(), any(), any())).thenReturn("tok-123");

        handler.handle(event(AdminActionType.BAN_USER, null));

        assertThat(capturedVariables())
                .containsEntry("appealUrl", "https://app.example.com/support/appeal?token=tok-123");
    }

    // A reinstatement has nothing to contest, so no token is minted for one.
    @Test
    void handle_reinstatement_carriesNoAppealLinkAndMintsNoToken() {
        stubEligibleUser(UserStatus.ACTIVE);

        handler.handle(event(AdminActionType.UNBAN_USER, null));

        assertThat(capturedVariables()).containsEntry("appealUrl", null);
        verify(supportTokenService, never()).createAppealToken(any(), any(), any());
    }

    // Otherwise a rejected appeal could be appealed again without bound.
    @Test
    void handle_supportTicketReply_carriesNoAppealLink() {
        stubEligibleUser(UserStatus.BANNED);

        handler.handle(event(AdminActionType.RESPOND_SUPPORT_TICKET, null));

        assertThat(capturedVariables()).containsEntry("appealUrl", null);
        verify(supportTokenService, never()).createAppealToken(any(), any(), any());
    }

    // internal_note is never placed in the event payload, so it cannot reach a template at all.
    @Test
    void handle_supportTicketReply_carriesTheResponseAndNothingElse() {
        stubEligibleUser(UserStatus.BANNED);

        handler.handle(event(AdminActionType.RESPOND_SUPPORT_TICKET, null));

        assertThat(capturedVariables()).doesNotContainKeys("internalNote", "internal_note");
    }

    private void stubEligibleUser(UserStatus status) {
        stubUser(status);
        stubCredential(true);
        when(throttle.tryAcquire(EMAIL)).thenReturn(true);
    }

    private void stubUser(UserStatus status) {
        User user = new User();
        user.setId(USER_ID);
        user.setEmail(EMAIL);
        user.setUsername("target");
        user.setStatus(status);
        when(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.of(user));
    }

    private void stubCredential(boolean verified) {
        UserCredential credential = new UserCredential();
        credential.setEmailVerified(verified);
        when(userCredentialRepository.findByUserId(USER_ID)).thenReturn(Optional.of(credential));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedVariables() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(mailSender).sendModerationNotice(any(), captor.capture(), anyString());
        return captor.getValue();
    }

    private java.util.List<EmailDelivery> capturedDeliveries() {
        ArgumentCaptor<EmailDelivery> captor = ArgumentCaptor.forClass(EmailDelivery.class);
        verify(emailDeliveryRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private static DomainEventEnvelope event(AdminActionType actionType, String suspendedUntil) {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("userId", USER_ID.toString());
        data.put("actionType", actionType.name());
        data.put("adminActionId", ADMIN_ACTION_ID.toString());
        if (suspendedUntil != null) {
            data.put("suspendedUntil", suspendedUntil);
        }
        return new DomainEventEnvelope(
                UUID.randomUUID(),
                AdminEventTypes.MODERATION_NOTICE_REQUESTED_V1,
                OCCURRED_AT,
                null,
                "user",
                USER_ID,
                data);
    }
}
