package com.app.modules.support.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.security.service.RateLimiterService;
import com.app.common.turnstile.TurnstileOutcome;
import com.app.common.turnstile.TurnstileVerifier;
import com.app.modules.admin.entity.AdminAction;
import com.app.modules.admin.enums.AdminActionType;
import com.app.modules.admin.repository.AdminActionRepository;
import com.app.modules.admin.service.AdminActionRecorder;
import com.app.modules.notification.service.NotificationService;
import com.app.modules.support.dto.request.CreateSupportTicketRequest;
import com.app.modules.support.dto.request.InProductAppealRequest;
import com.app.modules.support.dto.request.PublicSupportTicketRequest;
import com.app.modules.support.dto.request.SignedAppealRequest;
import com.app.modules.support.dto.response.SupportTicketResponse;
import com.app.modules.support.entity.SupportTicket;
import com.app.modules.support.enums.SupportCategory;
import com.app.modules.support.enums.SupportSource;
import com.app.modules.support.enums.SupportTicketStatus;
import com.app.modules.support.mapper.SupportTicketMapper;
import com.app.modules.support.repository.SupportTicketRepository;
import com.app.modules.support.service.SupportAuthorizationService;
import com.app.modules.support.service.SupportConfirmationMailer;
import com.app.modules.support.service.SupportTokenService;
import com.app.modules.users.entity.User;
import com.app.modules.users.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class SupportTicketServiceImplTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ACTION_ID = UUID.randomUUID();
    private static final String EMAIL = "user@example.com";

    @Mock private SupportTicketRepository supportTicketRepository;
    @Mock private SupportAuthorizationService supportAuthorizationService;
    @Mock private SupportTokenService supportTokenService;
    @Mock private TurnstileVerifier turnstileVerifier;
    @Mock private SupportConfirmationMailer confirmationMailer;
    @Mock private UserRepository userRepository;
    @Mock private AdminActionRepository adminActionRepository;
    @Mock private AdminActionRecorder adminActionRecorder;
    @Mock private NotificationService notificationService;
    @Mock private RateLimiterService rateLimiterService;
    @Mock private com.app.common.vocabulary.service.VocabularyService vocabularyService;

    private SupportTicketServiceImpl service;

    @BeforeEach
    void setUp() {
        service =
                new SupportTicketServiceImpl(
                        supportTicketRepository,
                        supportAuthorizationService,
                        supportTokenService,
                        turnstileVerifier,
                        confirmationMailer,
                        new SupportTicketMapper(),
                        userRepository,
                        adminActionRepository,
                        adminActionRecorder,
                        notificationService,
                        rateLimiterService,
                        vocabularyService);
        lenient()
                .when(supportTicketRepository.save(any()))
                .thenAnswer(
                        invocation -> {
                            SupportTicket ticket = invocation.getArgument(0);
                            if (ticket.getId() == null) {
                                ticket.setId(UUID.randomUUID());
                            }
                            return ticket;
                        });
        // The config table is the authority and permits the ordinary categories by default; the
        // tests that care about a category being refused override this or rely on the
        // defence-in-depth assertion behind it.
        lenient().when(vocabularyService.allowsPublicForm(anyString())).thenReturn(true);
        lenient()
                .when(supportTicketRepository.saveAndFlush(any()))
                .thenAnswer(
                        invocation -> {
                            SupportTicket ticket = invocation.getArgument(0);
                            if (ticket.getId() == null) {
                                ticket.setId(UUID.randomUUID());
                            }
                            return ticket;
                        });
    }

    @Test
    void createAuthenticated_firstTicket_isCreatedOpen() {
        stubUser();
        when(supportTicketRepository.hasOpenTicket(USER_ID)).thenReturn(false);

        SupportTicketResponse response =
                service.createAuthenticated(
                        USER_ID,
                        new CreateSupportTicketRequest(
                                SupportCategory.BUG_REPORT, "Subject", "Body"));

        assertThat(response.status()).isEqualTo(SupportTicketStatus.OPEN);
        assertThat(response.source()).isEqualTo(SupportSource.AUTHENTICATED);
    }

    // The service half of the one-open-ticket rule. The partial unique index is what holds under a
    // concurrent double submit; this exists so the ordinary case gets a named code.
    @Test
    void createAuthenticated_secondOpenTicket_isRefused() {
        when(supportTicketRepository.hasOpenTicket(USER_ID)).thenReturn(true);
        stubUser();

        assertThatThrownBy(
                        () ->
                                service.createAuthenticated(
                                        USER_ID,
                                        new CreateSupportTicketRequest(
                                                SupportCategory.OTHER, "Subject", "Body")))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.SUPPORT_TICKET_ALREADY_OPEN);

        verify(supportTicketRepository, never()).save(any());
    }

    // The critical security property of this module. Redeeming an appeal token creates one ticket
    // and mints nothing: no session, no token pair, no refresh token row.
    @Test
    void createFromSignedLink_mintsNoSession() {
        stubUser();
        when(supportTicketRepository.hasOpenTicket(USER_ID)).thenReturn(false);
        when(supportTokenService.peekAppealToken("tok"))
                .thenReturn(
                        new SupportTokenService.AppealGrant(
                                USER_ID, ACTION_ID, SupportCategory.APPEAL_BAN));

        SupportTicketResponse response =
                service.createFromSignedLink(new SignedAppealRequest("tok", "Subject", "Body"));

        assertThat(response.source()).isEqualTo(SupportSource.SIGNED_LINK);
        assertThat(capturedFlushedTicket().getAdminActionId()).isEqualTo(ACTION_ID);
        // The collaborators that would mint a session are not even wired into this service, so the
        // property is structural: there is nothing here that could issue one.
        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    // The category travels inside the token, never from the request body. Otherwise the submitter
    // could appeal something the token never authorised.
    @Test
    void createFromSignedLink_takesTheCategoryFromTheToken() {
        stubUser();
        when(supportTicketRepository.hasOpenTicket(USER_ID)).thenReturn(false);
        when(supportTokenService.peekAppealToken("tok"))
                .thenReturn(
                        new SupportTokenService.AppealGrant(
                                USER_ID, ACTION_ID, SupportCategory.APPEAL_CONTENT_REMOVAL));

        service.createFromSignedLink(new SignedAppealRequest("tok", "Subject", "Body"));

        assertThat(capturedFlushedTicket().getCategory())
                .isEqualTo(SupportCategory.APPEAL_CONTENT_REMOVAL);
    }

    @Test
    void createFromSignedLink_alreadyRedeemedToken_writesNothing() {
        when(supportTokenService.peekAppealToken("tok"))
                .thenThrow(new AppException(ApiErrorCode.SUPPORT_TOKEN_INVALID));

        assertThatThrownBy(
                        () ->
                                service.createFromSignedLink(
                                        new SignedAppealRequest("tok", "Subject", "Body")))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.SUPPORT_TOKEN_INVALID);

        verify(supportTicketRepository, never()).save(any());
    }

    // P7-BE-004. support_category_configs is the authority for what the public form may carry.
    // Before this, the endpoint filtered on is_enabled and allows_public_form while the submit path
    // read Java enum properties and neither column, so disabling a category hid it from the form
    // and left it accepted by any client that posted the key directly.
    @Test
    void createPublic_categoryTheConfigTableDisallows_isRefusedBeforeTurnstile() {
        when(vocabularyService.allowsPublicForm("bug_report")).thenReturn(false);

        assertThatThrownBy(
                        () ->
                                service.createPublic(
                                        publicRequest(SupportCategory.BUG_REPORT), "1.2.3.4"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.SUPPORT_CATEGORY_NOT_PUBLIC);

        verify(supportTicketRepository, never()).save(any());
        verify(turnstileVerifier, never()).verify(any(), any(), any());
    }

    // Turnstile runs before anything is written, so a failed challenge leaves no row behind.
    @Test
    void createPublic_invalidTurnstileToken_writesNothing() {
        when(turnstileVerifier.verify(anyString(), any(), any()))
                .thenReturn(TurnstileOutcome.REJECTED);

        assertThatThrownBy(
                        () -> service.createPublic(publicRequest(SupportCategory.OTHER), "1.2.3.4"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.SUPPORT_CAPTCHA_FAILED);

        verify(supportTicketRepository, never()).save(any());
        verify(confirmationMailer, never()).sendConfirmation(any(), any());
    }

    // An appeal needs an audit row to appeal against, which only a signed link supplies.
    @Test
    void createPublic_appealCategory_isRefusedBeforeTurnstile() {
        assertThatThrownBy(
                        () ->
                                service.createPublic(
                                        publicRequest(SupportCategory.APPEAL_BAN), "1.2.3.4"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.SUPPORT_CATEGORY_NOT_PUBLIC);

        verify(turnstileVerifier, never()).verify(any(), any(), any());
        verify(supportTicketRepository, never()).save(any());
    }

    // The submission exists but is invisible to staff until the address is confirmed.
    @Test
    void createPublic_validSubmission_isHeldPendingConfirmation() {
        when(turnstileVerifier.verify(anyString(), any(), any()))
                .thenReturn(TurnstileOutcome.VERIFIED);
        when(rateLimiterService.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(true);
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());
        when(supportTokenService.createConfirmationToken(any())).thenReturn("confirm-tok");

        service.createPublic(publicRequest(SupportCategory.BUG_REPORT), "1.2.3.4");

        assertThat(capturedTicket().getStatus())
                .isEqualTo(SupportTicketStatus.PENDING_CONFIRMATION);
        verify(confirmationMailer).sendConfirmation(EMAIL, "confirm-tok");
    }

    @Test
    void createPublic_dailyLimitReached_writesNothing() {
        when(turnstileVerifier.verify(anyString(), any(), any()))
                .thenReturn(TurnstileOutcome.VERIFIED);
        when(rateLimiterService.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(false);

        assertThatThrownBy(
                        () ->
                                service.createPublic(
                                        publicRequest(SupportCategory.BUG_REPORT), "1.2.3.4"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.SUPPORT_DAILY_LIMIT_REACHED);

        verify(supportTicketRepository, never()).save(any());
    }

    // The daily bound is ten per address per day, alongside the hourly three per IP the filter
    // applies.
    @Test
    void createPublic_appliesTenPerAddressPerDay() {
        when(turnstileVerifier.verify(anyString(), any(), any()))
                .thenReturn(TurnstileOutcome.VERIFIED);
        when(rateLimiterService.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(true);
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());
        when(supportTokenService.createConfirmationToken(any())).thenReturn("t");

        service.createPublic(publicRequest(SupportCategory.BUG_REPORT), "1.2.3.4");

        verify(rateLimiterService).isAllowed(anyString(), eq(10), eq(86400L));
    }

    // VERIFICATION_REQUEST is deliberately not an appeal, so isAppeal() does not exclude it and it
    // has to be named separately. Admitted here it would create a verification ticket with no
    // verification_requests row behind it: no evidence, no public-figure category, nothing the
    // moderator console can render or any decision path can act on. It would also be the first
    // verification ticket in PENDING_CONFIRMATION, which every staff-facing query hides.
    @Test
    void createPublic_verificationRequest_isRefused() {
        assertThatThrownBy(
                        () ->
                                service.createPublic(
                                        publicRequest(SupportCategory.VERIFICATION_REQUEST),
                                        "1.2.3.4"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.SUPPORT_CATEGORY_NOT_PUBLIC);

        verify(supportTicketRepository, never()).save(any());
        // Refused before the challenge is spent, like the appeal guard above it.
        verify(turnstileVerifier, never()).verify(any(), any(), any());
    }

    // P7-BE-003. The appeal link is the only credential a banned account holds and arrives in a
    // mail they cannot cause to be resent, so a refusal the submitter can act on must leave the
    // token spendable. This asserts the ordering rule, not one refusal: any check that can refuse
    // must run while the token is still intact.
    @Test
    void createFromSignedLink_refusedByAnOpenTicket_leavesTheTokenUnspent() {
        stubUser();
        when(supportTicketRepository.hasOpenTicket(USER_ID)).thenReturn(true);
        when(supportTokenService.peekAppealToken("tok"))
                .thenReturn(
                        new SupportTokenService.AppealGrant(
                                USER_ID, ACTION_ID, SupportCategory.APPEAL_BAN));

        assertThatThrownBy(
                        () ->
                                service.createFromSignedLink(
                                        new SignedAppealRequest("tok", "Subject", "Body")))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.SUPPORT_TICKET_ALREADY_OPEN);

        verify(supportTokenService, never()).consumeAppealToken(any());
        verify(supportTicketRepository, never()).saveAndFlush(any());
    }

    // The other half of the same rule: the success path must still spend it exactly once, which is
    // the property single-use exists to protect.
    @Test
    void createFromSignedLink_success_spendsTheTokenAfterTheWrite() {
        stubUser();
        when(supportTicketRepository.hasOpenTicket(USER_ID)).thenReturn(false);
        when(supportTokenService.peekAppealToken("tok"))
                .thenReturn(
                        new SupportTokenService.AppealGrant(
                                USER_ID, ACTION_ID, SupportCategory.APPEAL_BAN));

        service.createFromSignedLink(new SignedAppealRequest("tok", "Subject", "Body"));

        InOrder order = inOrder(supportTicketRepository, supportTokenService);
        order.verify(supportTicketRepository).saveAndFlush(any());
        order.verify(supportTokenService).consumeAppealToken("tok");
    }

    // Same rule on the confirmation lane: a replayed or spent link must answer with a reason
    // rather than silently burning the only thing that can confirm the address.
    @Test
    void confirmPublic_refusedByThePredicate_leavesTheTokenUnspent() {
        when(supportTokenService.peekConfirmationToken("tok")).thenReturn(ACTION_ID);
        when(supportTicketRepository.confirmIfPending(ACTION_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.confirmPublic("tok")).isInstanceOf(AppException.class);

        verify(supportTokenService, never()).consumeConfirmationToken(any());
    }

    @Test
    void confirmPublic_success_spendsTheTokenAfterTheUpdate() {
        when(supportTokenService.peekConfirmationToken("tok")).thenReturn(ACTION_ID);
        when(supportTicketRepository.confirmIfPending(ACTION_ID)).thenReturn(1);

        service.confirmPublic("tok");

        InOrder order = inOrder(supportTicketRepository, supportTokenService);
        order.verify(supportTicketRepository).confirmIfPending(ACTION_ID);
        order.verify(supportTokenService).consumeConfirmationToken("tok");
    }

    @Test
    void confirmPublic_replayedToken_isRefused() {
        when(supportTokenService.peekConfirmationToken("tok")).thenReturn(ACTION_ID);
        when(supportTicketRepository.confirmIfPending(ACTION_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.confirmPublic("tok"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.SUPPORT_TOKEN_INVALID);
    }

    // internal_note is staff-only. The owner-facing record has no component for it at all, so the
    // omission cannot be undone by a mapper edit.
    @Test
    void ownerResponse_neverCarriesTheInternalNote() {
        SupportTicket ticket = new SupportTicket();
        ticket.setId(UUID.randomUUID());
        ticket.setCategory(SupportCategory.BUG_REPORT);
        ticket.setStatus(SupportTicketStatus.ANSWERED);
        ticket.setSource(SupportSource.AUTHENTICATED);
        ticket.setStaffResponse("Here is our answer.");
        ticket.setInternalNote("Known abuser, do not escalate.");

        SupportTicketResponse response = new SupportTicketMapper().toOwnerResponse(ticket);

        assertThat(response.toString()).doesNotContain("Known abuser");
        assertThat(response.staffResponse()).isEqualTo("Here is our answer.");
    }

    @Test
    void createInProductAppeal_owner_opensTheAppealAndDerivesTheCategory() {
        stubUser();
        stubAction(AdminActionType.REMOVE_COMMENT, USER_ID);
        when(supportTicketRepository.hasAppealForAction(ACTION_ID)).thenReturn(false);
        when(supportTicketRepository.hasOpenTicket(USER_ID)).thenReturn(false);

        SupportTicketResponse response =
                service.createInProductAppeal(
                        USER_ID, new InProductAppealRequest(ACTION_ID, "Subject", "Body"));

        assertThat(response.status()).isEqualTo(SupportTicketStatus.OPEN);
        // AUTHENTICATED because it arrived on a session. admin_action_id being set is what makes
        // it an appeal, exactly as it is for the signed-link ticket.
        assertThat(response.source()).isEqualTo(SupportSource.AUTHENTICATED);
        SupportTicket written = capturedFlushedTicket();
        assertThat(written.getAdminActionId()).isEqualTo(ACTION_ID);
        // Derived server-side from the action type, never taken from the request.
        assertThat(written.getCategory()).isEqualTo(SupportCategory.APPEAL_CONTENT_REMOVAL);
    }

    // The identifier in the request is not a credential. A caller who is not the target of the row
    // must get exactly the answer an unknown identifier gets, or the endpoint becomes an oracle
    // for whether a given id names a real moderation decision.
    @Test
    void createInProductAppeal_foreignAction_answersExactlyAsAnUnknownOneDoes() {
        stubAction(AdminActionType.BAN_USER, UUID.randomUUID());

        ApiErrorCode foreign =
                errorCodeOf(
                        () ->
                                service.createInProductAppeal(
                                        USER_ID,
                                        new InProductAppealRequest(ACTION_ID, "Subject", "Body")));

        UUID unknownId = UUID.randomUUID();
        when(adminActionRepository.findById(unknownId)).thenReturn(Optional.empty());
        ApiErrorCode unknown =
                errorCodeOf(
                        () ->
                                service.createInProductAppeal(
                                        USER_ID,
                                        new InProductAppealRequest(unknownId, "Subject", "Body")));

        assertThat(foreign).isEqualTo(ApiErrorCode.SUPPORT_APPEAL_ACTION_NOT_FOUND);
        assertThat(unknown).isEqualTo(foreign);
        verify(supportTicketRepository, never()).saveAndFlush(any());
    }

    @Test
    void createInProductAppeal_unmappedActionType_isRefused() {
        // RESTORE_POST is reinstating. There is nothing to contest about being reinstated, so
        // AppealCategories maps it to nothing.
        stubAction(AdminActionType.RESTORE_POST, USER_ID);

        assertThatThrownBy(
                        () ->
                                service.createInProductAppeal(
                                        USER_ID,
                                        new InProductAppealRequest(ACTION_ID, "Subject", "Body")))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.SUPPORT_APPEAL_NOT_APPEALABLE);

        verify(supportTicketRepository, never()).saveAndFlush(any());
    }

    // One decision, one appeal. The open-ticket guard does not cover this: a rejected first appeal
    // is terminal, so without this check the same row could be appealed again indefinitely.
    @Test
    void createInProductAppeal_secondAppealAgainstTheSameDecision_isRefused() {
        stubUser();
        stubAction(AdminActionType.WARN_USER, USER_ID);
        when(supportTicketRepository.hasAppealForAction(ACTION_ID)).thenReturn(true);

        assertThatThrownBy(
                        () ->
                                service.createInProductAppeal(
                                        USER_ID,
                                        new InProductAppealRequest(ACTION_ID, "Subject", "Body")))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.SUPPORT_APPEAL_ALREADY_FILED);

        verify(supportTicketRepository, never()).saveAndFlush(any());
    }

    // The two entry paths lead to one ticket. Leaving a signed link live after the in-product
    // appeal is filed would hand its holder a credential that can only ever be refused, and one
    // that would outlive a rejection of this very appeal.
    @Test
    void createInProductAppeal_revokesAnyUnspentSignedLinkForTheSameDecision() {
        stubUser();
        stubAction(AdminActionType.SUSPEND_USER, USER_ID);
        when(supportTicketRepository.hasAppealForAction(ACTION_ID)).thenReturn(false);
        when(supportTicketRepository.hasOpenTicket(USER_ID)).thenReturn(false);

        service.createInProductAppeal(
                USER_ID, new InProductAppealRequest(ACTION_ID, "Subject", "Body"));

        // Revoked after the write, so a refusal above leaves the link intact.
        InOrder ordered = inOrder(supportTicketRepository, supportTokenService);
        ordered.verify(supportTicketRepository).saveAndFlush(any());
        ordered.verify(supportTokenService).revokeAppealToken(ACTION_ID);
    }

    @Test
    void createInProductAppeal_accountAlreadyHoldsAnOpenTicket_isRefused() {
        stubUser();
        stubAction(AdminActionType.BAN_USER, USER_ID);
        when(supportTicketRepository.hasAppealForAction(ACTION_ID)).thenReturn(false);
        when(supportTicketRepository.hasOpenTicket(USER_ID)).thenReturn(true);

        assertThatThrownBy(
                        () ->
                                service.createInProductAppeal(
                                        USER_ID,
                                        new InProductAppealRequest(ACTION_ID, "Subject", "Body")))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.SUPPORT_TICKET_ALREADY_OPEN);

        verify(supportTicketRepository, never()).saveAndFlush(any());
        verify(supportTokenService, never()).revokeAppealToken(any());
    }

    // Both entries run the same guards because they run the same implementation. If this drifts,
    // the shared path has been split in two.
    @Test
    void bothAppealEntries_refuseASecondAppealAgainstTheSameDecision() {
        stubUser();
        stubAction(AdminActionType.REMOVE_STORY, USER_ID);
        when(supportTicketRepository.hasAppealForAction(ACTION_ID)).thenReturn(true);
        when(supportTokenService.peekAppealToken("tok"))
                .thenReturn(
                        new SupportTokenService.AppealGrant(
                                USER_ID, ACTION_ID, SupportCategory.APPEAL_CONTENT_REMOVAL));

        ApiErrorCode viaLink =
                errorCodeOf(
                        () ->
                                service.createFromSignedLink(
                                        new SignedAppealRequest("tok", "Subject", "Body")));
        ApiErrorCode viaSession =
                errorCodeOf(
                        () ->
                                service.createInProductAppeal(
                                        USER_ID,
                                        new InProductAppealRequest(ACTION_ID, "Subject", "Body")));

        assertThat(viaLink).isEqualTo(ApiErrorCode.SUPPORT_APPEAL_ALREADY_FILED);
        assertThat(viaSession).isEqualTo(viaLink);
        // The signed link survives its refusal, which is the whole reason the token is peeked
        // rather than consumed.
        verify(supportTokenService, never()).consumeAppealToken(anyString());
    }

    private void stubAction(AdminActionType actionType, UUID targetUserId) {
        when(adminActionRepository.findById(ACTION_ID))
                .thenReturn(
                        Optional.of(
                                AdminAction.builder()
                                        .id(ACTION_ID)
                                        .actionType(actionType)
                                        .targetUserId(targetUserId)
                                        .build()));
    }

    private static ApiErrorCode errorCodeOf(org.junit.jupiter.api.function.Executable call) {
        try {
            call.execute();
        } catch (Throwable thrown) {
            return ((AppException) thrown).getErrorCode();
        }
        throw new AssertionError("expected the call to be refused");
    }

    private void stubUser() {
        User user = new User();
        user.setId(USER_ID);
        user.setEmail(EMAIL);
        user.setUsername("user");
        lenient()
                .when(userRepository.findByIdAndDeletedAtIsNull(USER_ID))
                .thenReturn(Optional.of(user));
    }

    private SupportTicket capturedTicket() {
        ArgumentCaptor<SupportTicket> captor = ArgumentCaptor.forClass(SupportTicket.class);
        verify(supportTicketRepository).save(captor.capture());
        return captor.getValue();
    }

    // The signed-link path flushes rather than saves, so a constraint violation surfaces while the
    // token is still unspent.
    private SupportTicket capturedFlushedTicket() {
        ArgumentCaptor<SupportTicket> captor = ArgumentCaptor.forClass(SupportTicket.class);
        verify(supportTicketRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private static PublicSupportTicketRequest publicRequest(SupportCategory category) {
        return new PublicSupportTicketRequest(
                EMAIL, category, "Subject", "Body", "turnstile-token");
    }
}
