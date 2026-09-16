package com.app.modules.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.security.jwt.JwtProperties;
import com.app.common.security.jwt.JwtTokenProvider;
import com.app.common.security.service.RefreshTokenService;
import com.app.common.security.service.TokenBlacklistService;
import com.app.common.security.util.IpExtractor;
import com.app.common.turnstile.AuthTurnstileGuard;
import com.app.common.turnstile.TurnstileOutcome;
import com.app.common.turnstile.TurnstileProperties;
import com.app.common.turnstile.TurnstileSurface;
import com.app.common.turnstile.TurnstileVerifier;
import com.app.modules.auth.dto.request.ForgotPasswordRequest;
import com.app.modules.auth.dto.request.LoginRequest;
import com.app.modules.auth.dto.request.RegisterRequest;
import com.app.modules.auth.dto.request.ResendVerificationRequest;
import com.app.modules.auth.dto.request.ResetPasswordRequest;
import com.app.modules.auth.exception.TokenNotFoundException;
import com.app.modules.auth.mapper.AuthMapper;
import com.app.modules.auth.repository.UserCredentialRepository;
import com.app.modules.auth.service.AuthForgotPasswordEventService;
import com.app.modules.auth.service.AuthMailEventService;
import com.app.modules.auth.service.AuthResendVerificationEventService;
import com.app.modules.auth.service.OAuth2ExchangeCodeService;
import com.app.modules.auth.service.TokenService;
import com.app.modules.auth.validation.UserStateValidator;
import com.app.modules.recommendation.service.UserEventRecorder;
import com.app.modules.users.entity.User;
import com.app.modules.users.repository.UserRepository;
import com.app.modules.users.repository.UserSettingsRepository;

/**
 * Drives each protected auth service method through a real {@link AuthTurnstileGuard} backed by a
 * stubbed verifier, so the rejection, fail-open and kill-switch behaviours are exercised end to end
 * rather than asserted against a stubbed policy.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTurnstileTest {

    private static final String TOKEN = "turnstile-token";
    private static final String CLIENT_IP = "4.5.6.7";
    private static final String PASSWORD = "S3cur3P@ssword";

    @Mock private UserRepository userRepository;
    @Mock private UserCredentialRepository credentialRepository;
    @Mock private UserSettingsRepository settingsRepository;
    @Mock private TokenService tokenService;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthMailEventService authMailEventService;
    @Mock private AuthForgotPasswordEventService authForgotPasswordEventService;
    @Mock private AuthResendVerificationEventService authResendVerificationEventService;
    @Mock private ForgotPasswordTimingEqualizer forgotPasswordTimingEqualizer;
    @Mock private AuthMapper authMapper;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private IpExtractor ipExtractor;
    @Mock private UserStateValidator userStateValidator;
    @Mock private OAuth2ExchangeCodeService oauth2ExchangeCodeService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private UserEventRecorder userEventRecorder;
    @Mock private TurnstileVerifier verifier;

    private TurnstileProperties turnstileProperties;
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        turnstileProperties = new TurnstileProperties();
        lenient().when(ipExtractor.extract(any(HttpServletRequest.class))).thenReturn(CLIENT_IP);
        lenient()
                .doAnswer(
                        inv -> {
                            Consumer<TransactionStatus> cb = inv.getArgument(0);
                            cb.accept(null);
                            return null;
                        })
                .when(transactionTemplate)
                .executeWithoutResult(any());
        lenient()
                .when(transactionTemplate.execute(any(TransactionCallback.class)))
                .thenAnswer(
                        inv -> {
                            TransactionCallback<?> cb = inv.getArgument(0);
                            return cb.doInTransaction(null);
                        });
        service =
                new AuthServiceImpl(
                        userRepository,
                        credentialRepository,
                        settingsRepository,
                        tokenService,
                        refreshTokenService,
                        jwtTokenProvider,
                        new JwtProperties(
                                "test-secret-32-chars-test-secret-", "iss", "App", 900, 3600),
                        passwordEncoder,
                        authMailEventService,
                        authForgotPasswordEventService,
                        authResendVerificationEventService,
                        forgotPasswordTimingEqualizer,
                        authMapper,
                        tokenBlacklistService,
                        ipExtractor,
                        userStateValidator,
                        oauth2ExchangeCodeService,
                        transactionTemplate,
                        userEventRecorder,
                        new AuthTurnstileGuard(verifier, turnstileProperties));
    }

    private void cloudflareAnswers(TurnstileOutcome outcome) {
        lenient().when(verifier.verify(any(), any(), any())).thenReturn(outcome);
    }

    private static MockHttpServletRequest servletRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(CLIENT_IP);
        req.addHeader("User-Agent", "JUnit");
        return req;
    }

    private void stubSuccessfulRegistration() {
        when(userRepository.existsByEmail("a@b.c")).thenReturn(false);
        when(userRepository.existsByUsername("user1")).thenReturn(false);
        when(userRepository.save(any(User.class)))
                .thenAnswer(
                        inv -> {
                            User u = inv.getArgument(0);
                            u.setId(UUID.randomUUID());
                            return u;
                        });
    }

    @Test
    void register_rejected_refusesAndWritesNothing() {
        cloudflareAnswers(TurnstileOutcome.REJECTED);

        assertThatThrownBy(
                        () ->
                                service.register(
                                        new RegisterRequest(
                                                "user1", "a@b.c", PASSWORD, null, "bad"),
                                        servletRequest()))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_CAPTCHA_FAILED);

        verify(userRepository, never()).existsByEmail(anyString());
        verify(userRepository, never()).save(any());
        verify(credentialRepository, never()).save(any());
        verify(settingsRepository, never()).save(any());
        verify(authMailEventService, never()).publishUserRegistered(any());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void register_unavailable_proceedsNormally() {
        cloudflareAnswers(TurnstileOutcome.UNAVAILABLE);
        stubSuccessfulRegistration();

        service.register(
                new RegisterRequest("user1", "a@b.c", PASSWORD, null, TOKEN), servletRequest());

        verify(userRepository).save(any(User.class));
        verify(credentialRepository).save(any());
    }

    @Test
    void register_killSwitchDisabled_acceptsABlankTokenWithoutVerifying() {
        turnstileProperties.getAuth().setEnabled(false);
        stubSuccessfulRegistration();

        service.register(
                new RegisterRequest("user1", "a@b.c", PASSWORD, null, null), servletRequest());

        verify(verifier, never()).verify(any(), any(), any());
        verify(userRepository).save(any(User.class));
    }

    @Test
    void login_rejected_refusesBeforeAnyCredentialLookup() {
        cloudflareAnswers(TurnstileOutcome.REJECTED);

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest("a@b.c", PASSWORD, "bad"),
                                        servletRequest()))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_CAPTCHA_FAILED);

        verify(userRepository, never()).findByEmailAndDeletedAtIsNull(anyString());
        verify(userRepository, never()).findByUsernameAndDeletedAtIsNull(anyString());
        verify(passwordEncoder, never()).matches(any(), any());
        verify(refreshTokenService, never()).issue(any(), any(), any(), any());
    }

    @Test
    void login_unavailable_proceedsToTheCredentialCheck() {
        cloudflareAnswers(TurnstileOutcome.UNAVAILABLE);
        when(userRepository.findByEmailAndDeletedAtIsNull("a@b.c")).thenReturn(Optional.empty());
        when(passwordEncoder.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest("a@b.c", PASSWORD, TOKEN),
                                        servletRequest()))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    @Test
    void login_killSwitchDisabled_acceptsABlankTokenWithoutVerifying() {
        turnstileProperties.getAuth().setEnabled(false);
        when(userRepository.findByEmailAndDeletedAtIsNull("a@b.c")).thenReturn(Optional.empty());
        when(passwordEncoder.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest("a@b.c", PASSWORD, ""), servletRequest()))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_INVALID_CREDENTIALS);

        verify(verifier, never()).verify(any(), any(), any());
    }

    @Test
    void forgotPassword_rejected_enqueuesNothingAndStillEqualizesTiming() {
        cloudflareAnswers(TurnstileOutcome.REJECTED);

        assertThatThrownBy(
                        () ->
                                service.forgotPassword(
                                        new ForgotPasswordRequest("a@b.c", "bad"), CLIENT_IP))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_CAPTCHA_FAILED);

        verify(authForgotPasswordEventService, never()).recordForgotPasswordRequest(anyString());
        // The refusal leaves through the same finally the success path does, so a rejected
        // challenge takes the same wall time as an accepted one and reopens no timing channel.
        verify(forgotPasswordTimingEqualizer).equalizeFrom(anyLong());
    }

    @Test
    void forgotPassword_unavailable_proceedsNormally() {
        cloudflareAnswers(TurnstileOutcome.UNAVAILABLE);

        service.forgotPassword(new ForgotPasswordRequest("a@b.c", TOKEN), CLIENT_IP);

        verify(authForgotPasswordEventService).recordForgotPasswordRequest("a@b.c");
        verify(forgotPasswordTimingEqualizer).equalizeFrom(anyLong());
    }

    @Test
    void forgotPassword_killSwitchDisabled_acceptsABlankTokenWithoutVerifying() {
        turnstileProperties.getAuth().setEnabled(false);

        service.forgotPassword(new ForgotPasswordRequest("a@b.c", null), CLIENT_IP);

        verify(verifier, never()).verify(any(), any(), any());
        verify(authForgotPasswordEventService).recordForgotPasswordRequest("a@b.c");
    }

    @Test
    void resendVerification_rejected_enqueuesNothingAndStillEqualizesTiming() {
        cloudflareAnswers(TurnstileOutcome.REJECTED);

        assertThatThrownBy(
                        () ->
                                service.resendVerification(
                                        new ResendVerificationRequest("a@b.c", "bad"), CLIENT_IP))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_CAPTCHA_FAILED);

        verify(authResendVerificationEventService, never())
                .recordResendVerificationRequest(anyString());
        verify(forgotPasswordTimingEqualizer).equalizeFrom(anyLong());
    }

    @Test
    void resendVerification_unavailable_proceedsNormally() {
        cloudflareAnswers(TurnstileOutcome.UNAVAILABLE);

        service.resendVerification(new ResendVerificationRequest("a@b.c", TOKEN), CLIENT_IP);

        verify(authResendVerificationEventService).recordResendVerificationRequest("a@b.c");
    }

    @Test
    void resendVerification_killSwitchDisabled_acceptsABlankTokenWithoutVerifying() {
        turnstileProperties.getAuth().setEnabled(false);

        service.resendVerification(new ResendVerificationRequest("a@b.c", ""), CLIENT_IP);

        verify(verifier, never()).verify(any(), any(), any());
        verify(authResendVerificationEventService).recordResendVerificationRequest("a@b.c");
    }

    @Test
    void resetPassword_rejected_leavesTheSingleUseTokenUnspent() {
        cloudflareAnswers(TurnstileOutcome.REJECTED);

        assertThatThrownBy(
                        () ->
                                service.resetPassword(
                                        new ResetPasswordRequest(
                                                "RESET-RAW", "newPassword1", "bad"),
                                        CLIENT_IP))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_CAPTCHA_FAILED);

        verify(tokenService, never()).consumePasswordResetToken(anyString());
        verify(credentialRepository, never()).save(any());
        verify(refreshTokenService, never()).revokeAllForUser(any());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void resetPassword_unavailable_proceedsToConsumeTheToken() {
        cloudflareAnswers(TurnstileOutcome.UNAVAILABLE);
        when(tokenService.consumePasswordResetToken("RESET-RAW"))
                .thenThrow(new TokenNotFoundException("gone"));

        assertThatThrownBy(
                        () ->
                                service.resetPassword(
                                        new ResetPasswordRequest(
                                                "RESET-RAW", "newPassword1", TOKEN),
                                        CLIENT_IP))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_RESET_TOKEN_INVALID);
    }

    @Test
    void resetPassword_killSwitchDisabled_acceptsABlankTokenWithoutVerifying() {
        turnstileProperties.getAuth().setEnabled(false);
        when(tokenService.consumePasswordResetToken("RESET-RAW"))
                .thenThrow(new TokenNotFoundException("gone"));

        assertThatThrownBy(
                        () ->
                                service.resetPassword(
                                        new ResetPasswordRequest("RESET-RAW", "newPassword1", null),
                                        CLIENT_IP))
                .isInstanceOf(AppException.class);

        verify(verifier, never()).verify(any(), any(), any());
    }

    @Test
    void eachProtectedSurface_carriesItsOwnMetricTag() {
        // The surface tag is what makes an outage attributable to one form rather than to
        // "authentication" as a whole.
        assertThat(TurnstileSurface.values())
                .extracting(TurnstileSurface::tag)
                .contains(
                        "login",
                        "register",
                        "forgot_password",
                        "reset_password",
                        "resend_verification",
                        "report",
                        "public_support");
    }
}
