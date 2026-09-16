package com.app.modules.support.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
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
import com.app.common.turnstile.TurnstileOutcome;
import com.app.common.turnstile.TurnstileSurface;
import com.app.common.turnstile.TurnstileVerifier;
import com.app.modules.admin.entity.AdminAction;
import com.app.modules.admin.enums.AdminActionType;
import com.app.modules.admin.repository.AdminActionRepository;
import com.app.modules.auth.config.ForgotPasswordTimingProperties;
import com.app.modules.auth.entity.UserCredential;
import com.app.modules.auth.repository.UserCredentialRepository;
import com.app.modules.auth.service.impl.ForgotPasswordTimingEqualizer;
import com.app.modules.mail.config.MailProperties;
import com.app.modules.support.dto.request.ResendAppealLinkRequest;
import com.app.modules.support.enums.SupportCategory;
import com.app.modules.support.service.AppealLinkMailer;
import com.app.modules.support.service.SupportTokenService;
import com.app.modules.users.entity.User;
import com.app.modules.users.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class AppealRecoveryServiceImplTest {

    private static final String EMAIL = "banned@example.com";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ACTION_ID = UUID.randomUUID();

    @Mock private UserRepository userRepository;
    @Mock private UserCredentialRepository userCredentialRepository;
    @Mock private AdminActionRepository adminActionRepository;
    @Mock private SupportTokenService supportTokenService;
    @Mock private AppealLinkMailer appealLinkMailer;
    @Mock private TurnstileVerifier turnstileVerifier;
    @Mock private MailProperties mailProperties;

    private RecordingEqualizer equalizer;
    private AppealRecoveryServiceImpl service;

    @BeforeEach
    void setUp() {
        equalizer = new RecordingEqualizer();
        service =
                new AppealRecoveryServiceImpl(
                        userRepository,
                        userCredentialRepository,
                        adminActionRepository,
                        supportTokenService,
                        appealLinkMailer,
                        turnstileVerifier,
                        equalizer,
                        mailProperties);
        lenient().when(mailProperties.getFrontendBaseUrl()).thenReturn("https://luvax.test");
    }

    @Test
    void resendAppealLink_matchingAddress_mintsALinkAndMailsIt() {
        stubVerifiedChallenge();
        stubAccount();
        stubVerifiedEmail(true);
        stubMostRecentAppealable(AdminActionType.BAN_USER);
        when(supportTokenService.createAppealToken(USER_ID, ACTION_ID, SupportCategory.APPEAL_BAN))
                .thenReturn("fresh-token");

        service.resendAppealLink(request(), "203.0.113.9");

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        // Mailed to the account's own stored address, never to the string the caller typed.
        verify(appealLinkMailer).sendAppealLink(eq(EMAIL), url.capture());
        assertThat(url.getValue()).isEqualTo("https://luvax.test/support/appeal?token=fresh-token");
    }

    @Test
    void resendAppealLink_unknownAddress_mailsNothingAndMintsNothing() {
        stubVerifiedChallenge();
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());

        service.resendAppealLink(request(), "203.0.113.9");

        verify(appealLinkMailer, never()).sendAppealLink(anyString(), anyString());
        verify(supportTokenService, never()).createAppealToken(any(), any(), any());
    }

    // The property the whole endpoint exists to preserve. Both outcomes must be one answer.
    @Test
    void resendAppealLink_matchingAndNonMatchingAddresses_areIndistinguishable() {
        stubVerifiedChallenge();
        stubAccount();
        stubVerifiedEmail(true);
        stubMostRecentAppealable(AdminActionType.SUSPEND_USER);
        when(supportTokenService.createAppealToken(any(), any(), any())).thenReturn("tok");

        service.resendAppealLink(request(), "203.0.113.9");
        long matchedSleeps = equalizer.calls;

        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());
        service.resendAppealLink(request(), "203.0.113.9");

        // Neither path throws, neither returns anything, and both are equalized. There is no value
        // the caller could compare, which is what makes the two indistinguishable.
        assertThat(matchedSleeps).isEqualTo(1L);
        assertThat(equalizer.calls).isEqualTo(2L);
    }

    // The equalizer must run on every exit, including the refusal. Otherwise a rejected challenge
    // returns faster than an accepted one and the floor covers only half the traffic.
    @Test
    void resendAppealLink_failedChallenge_isRefusedAndStillEqualized() {
        when(turnstileVerifier.verify(
                        anyString(), anyString(), eq(TurnstileSurface.RESEND_APPEAL_LINK)))
                .thenReturn(TurnstileOutcome.REJECTED);

        assertThatThrownBy(() -> service.resendAppealLink(request(), "203.0.113.9"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.SUPPORT_CAPTCHA_FAILED);

        assertThat(equalizer.calls).isEqualTo(1L);
        // Writes nothing and sends nothing: the account is never even resolved.
        verify(userRepository, never()).findByEmailIgnoreCase(anyString());
        verify(supportTokenService, never()).createAppealToken(any(), any(), any());
        verify(appealLinkMailer, never()).sendAppealLink(anyString(), anyString());
    }

    // Fail-closed, unlike the auth surfaces. An outage must stop this route rather than open it:
    // it has no per-caller identity behind it and it triggers mail.
    @Test
    void resendAppealLink_turnstileUnavailable_failsClosed() {
        when(turnstileVerifier.verify(
                        anyString(), anyString(), eq(TurnstileSurface.RESEND_APPEAL_LINK)))
                .thenReturn(TurnstileOutcome.UNAVAILABLE);

        assertThatThrownBy(() -> service.resendAppealLink(request(), "203.0.113.9"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.SUPPORT_CAPTCHA_FAILED);

        verify(appealLinkMailer, never()).sendAppealLink(anyString(), anyString());
    }

    // The challenge is verified inside the measured window. If it ran before the window opened, its
    // variable latency would sit outside the floor and the channel would be back.
    @Test
    void resendAppealLink_verifiesTheChallengeInsideTheEqualizedWindow() {
        when(turnstileVerifier.verify(
                        anyString(), anyString(), eq(TurnstileSurface.RESEND_APPEAL_LINK)))
                .thenAnswer(
                        invocation -> {
                            assertThat(equalizer.calls)
                                    .as("the window must still be open during siteverify")
                                    .isZero();
                            return TurnstileOutcome.VERIFIED;
                        });
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());

        service.resendAppealLink(request(), "203.0.113.9");

        assertThat(equalizer.calls).isEqualTo(1L);
        assertThat(equalizer.startNanos).isLessThanOrEqualTo(System.nanoTime());
    }

    // Same refusal ModerationMailEventHandler applies, and worse here: an anonymous caller chooses
    // the address, so mailing an unproven one discloses to a third party on demand.
    @Test
    void resendAppealLink_unverifiedAddress_mailsNothing() {
        stubVerifiedChallenge();
        stubAccount();
        stubVerifiedEmail(false);

        service.resendAppealLink(request(), "203.0.113.9");

        verify(adminActionRepository, never()).findMostRecentAppealable(any(), anyCollection());
        verify(appealLinkMailer, never()).sendAppealLink(anyString(), anyString());
    }

    @Test
    void resendAppealLink_noUnappealedDecision_mailsNothing() {
        stubVerifiedChallenge();
        stubAccount();
        stubVerifiedEmail(true);
        when(adminActionRepository.findMostRecentAppealable(eq(USER_ID), anyCollection()))
                .thenReturn(Optional.empty());

        service.resendAppealLink(request(), "203.0.113.9");

        verify(supportTokenService, never()).createAppealToken(any(), any(), any());
        verify(appealLinkMailer, never()).sendAppealLink(anyString(), anyString());
    }

    // Re-minting goes through the ordinary issue path, whose reverse key destroys the previous
    // token for the same subject. One decision never holds two live links.
    @Test
    void resendAppealLink_reMintsThroughTheOrdinaryIssuePath() {
        stubVerifiedChallenge();
        stubAccount();
        stubVerifiedEmail(true);
        stubMostRecentAppealable(AdminActionType.REMOVE_COMMENT);
        when(supportTokenService.createAppealToken(any(), any(), any())).thenReturn("tok");

        service.resendAppealLink(request(), "203.0.113.9");

        verify(supportTokenService)
                .createAppealToken(USER_ID, ACTION_ID, SupportCategory.APPEAL_CONTENT_REMOVAL);
    }

    @Test
    void resendAppealLink_normalisesTheAddressBeforeResolvingIt() {
        stubVerifiedChallenge();
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());

        service.resendAppealLink(
                new ResendAppealLinkRequest("  BANNED@Example.COM  ", "cf-token"), "203.0.113.9");

        verify(userRepository).findByEmailIgnoreCase(EMAIL);
    }

    private static ResendAppealLinkRequest request() {
        return new ResendAppealLinkRequest(EMAIL, "cf-token");
    }

    private void stubVerifiedChallenge() {
        when(turnstileVerifier.verify(
                        anyString(), anyString(), eq(TurnstileSurface.RESEND_APPEAL_LINK)))
                .thenReturn(TurnstileOutcome.VERIFIED);
    }

    private void stubAccount() {
        User user = new User();
        user.setId(USER_ID);
        user.setEmail(EMAIL);
        user.setUsername("banned");
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
    }

    private void stubVerifiedEmail(boolean verified) {
        UserCredential credential = new UserCredential();
        credential.setEmailVerified(verified);
        when(userCredentialRepository.findByUserId(USER_ID)).thenReturn(Optional.of(credential));
    }

    private void stubMostRecentAppealable(AdminActionType actionType) {
        when(adminActionRepository.findMostRecentAppealable(eq(USER_ID), anyCollection()))
                .thenReturn(
                        Optional.of(
                                AdminAction.builder()
                                        .id(ACTION_ID)
                                        .actionType(actionType)
                                        .targetUserId(USER_ID)
                                        .build()));
    }

    /**
     * Records that the window was closed, without sleeping.
     *
     * <p>A real sleep would make the suite pay the response floor on every case, and the property
     * under test is that the equalizer is invoked on every exit path, not how long it sleeps.
     */
    private static final class RecordingEqualizer extends ForgotPasswordTimingEqualizer {

        private long calls;
        private long startNanos;

        private RecordingEqualizer() {
            super(zeroFloor());
        }

        private static ForgotPasswordTimingProperties zeroFloor() {
            ForgotPasswordTimingProperties properties = new ForgotPasswordTimingProperties();
            properties.setMinResponseTime(Duration.ZERO);
            properties.setMaxJitter(Duration.ZERO);
            return properties;
        }

        // Records rather than delegating. Calling through would sleep for the configured floor on
        // every case and make the suite pay it; what is under test is that the window is closed on
        // every exit path, not how long the close takes. ForgotPasswordTimingEqualizerTest already
        // covers the sleeping itself.
        @Override
        public void equalizeFrom(long startNanos) {
            this.calls++;
            this.startNanos = startNanos;
        }
    }
}
