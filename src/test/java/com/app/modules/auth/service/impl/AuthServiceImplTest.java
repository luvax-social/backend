package com.app.modules.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.security.jwt.JwtClaims;
import com.app.common.security.jwt.JwtProperties;
import com.app.common.security.jwt.JwtTokenProvider;
import com.app.common.security.service.RefreshTokenService;
import com.app.common.security.service.TokenBlacklistService;
import com.app.common.security.util.IpExtractor;
import com.app.common.turnstile.AuthTurnstileGuard;
import com.app.modules.auth.dto.request.ForgotPasswordRequest;
import com.app.modules.auth.dto.request.LoginRequest;
import com.app.modules.auth.dto.request.RegisterRequest;
import com.app.modules.auth.dto.request.ResendVerificationRequest;
import com.app.modules.auth.dto.request.ResetPasswordRequest;
import com.app.modules.auth.dto.response.AuthResponse;
import com.app.modules.auth.dto.response.AuthenticatedUserResponse;
import com.app.modules.auth.entity.UserCredential;
import com.app.modules.auth.exception.TokenExpiredException;
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
import com.app.modules.users.entity.UserSettings;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.repository.UserRepository;
import com.app.modules.users.repository.UserSettingsRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    private static final String TEST_PASSWORD = "S3cur3P@ssword";
    private static final String TURNSTILE_TOKEN = "turnstile-token";
    private static final String CLIENT_IP = "4.5.6.7";

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
    @Mock private AuthTurnstileGuard turnstileGuard;

    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties =
                new JwtProperties("test-secret-32-chars-test-secret-", "iss", "App", 900, 3600);
        lenient().when(ipExtractor.extract(any(HttpServletRequest.class))).thenReturn("4.5.6.7");
        lenient()
                .when(authMapper.toAuthenticatedUserResponse(any(User.class), anyBoolean()))
                .thenAnswer(
                        inv -> {
                            User u = inv.getArgument(0);
                            boolean ev = inv.getArgument(1);
                            return new AuthenticatedUserResponse(
                                    u.getId(),
                                    u.getUsername(),
                                    u.getEmail(),
                                    u.getDisplayName(),
                                    u.getRole(),
                                    ev);
                        });
        // Execute TransactionTemplate callbacks directly (no real PlatformTransactionManager).
        lenient()
                .doAnswer(
                        inv -> {
                            java.util.function.Consumer<
                                            org.springframework.transaction.TransactionStatus>
                                    cb = inv.getArgument(0);
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
        this.service =
                new AuthServiceImpl(
                        userRepository,
                        credentialRepository,
                        settingsRepository,
                        tokenService,
                        refreshTokenService,
                        jwtTokenProvider,
                        jwtProperties,
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
                        turnstileGuard);
    }

    private MockHttpServletRequest stubRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("4.5.6.7");
        req.addHeader("User-Agent", "JUnit");
        return req;
    }

    @Test
    void register_duplicateEmail_throwsConflict() {
        when(userRepository.existsByEmail("a@b.c")).thenReturn(true);
        RegisterRequest req =
                new RegisterRequest("user1", "a@b.c", TEST_PASSWORD, null, TURNSTILE_TOKEN);

        assertThatThrownBy(() -> service.register(req, stubRequest()))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.USER_ALREADY_EXISTS);
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_duplicateUsername_throwsConflict() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.existsByUsername("user1")).thenReturn(true);
        RegisterRequest req =
                new RegisterRequest("user1", "a@b.c", TEST_PASSWORD, null, TURNSTILE_TOKEN);

        assertThatThrownBy(() -> service.register(req, stubRequest()))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.USER_ALREADY_EXISTS);
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_success_persistsUserCredentialAndSettings() {
        UUID newId = UUID.randomUUID();
        when(userRepository.save(any(User.class)))
                .thenAnswer(
                        inv -> {
                            User u = inv.getArgument(0);
                            u.setId(newId);
                            return u;
                        });
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn("HASH");

        service.register(
                new RegisterRequest("user1", "a@b.c", TEST_PASSWORD, null, TURNSTILE_TOKEN),
                stubRequest());

        verify(userRepository).save(any(User.class));
        verify(credentialRepository).save(any(UserCredential.class));
        verify(settingsRepository).save(any(UserSettings.class));
    }

    @Test
    void register_success_recordsMailEventsWithoutCreatingRawToken() {
        UUID newId = UUID.randomUUID();
        when(userRepository.save(any(User.class)))
                .thenAnswer(
                        inv -> {
                            User u = inv.getArgument(0);
                            u.setId(newId);
                            return u;
                        });
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");

        service.register(
                new RegisterRequest("user1", "a@b.c", TEST_PASSWORD, null, TURNSTILE_TOKEN),
                stubRequest());

        verify(authMailEventService).publishUserRegistered(any(User.class));
        verify(authMailEventService).publishEmailVerificationRequested(any(User.class), eq(newId));
        verify(tokenService, never()).createEmailVerificationToken(any());
    }

    @Test
    void register_success_logsInfoWithoutEmail() {
        UUID newId = UUID.randomUUID();
        when(userRepository.save(any(User.class)))
                .thenAnswer(
                        inv -> {
                            User u = inv.getArgument(0);
                            u.setId(newId);
                            return u;
                        });
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");

        Logger logger = (Logger) LoggerFactory.getLogger(AuthServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.register(
                    new RegisterRequest("user1", "a@b.c", TEST_PASSWORD, null, TURNSTILE_TOKEN),
                    stubRequest());
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .anyMatch(
                        event ->
                                event.getLevel() == Level.INFO
                                        && event.getFormattedMessage().contains(newId.toString()))
                .noneMatch(event -> event.getFormattedMessage().contains("a@b.c"));
    }

    @Test
    void login_unknownEmail_throwsInvalidCredentials() {
        when(userRepository.findByEmailAndDeletedAtIsNull("nobody@x.y"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest(
                                                "nobody@x.y", TEST_PASSWORD, TURNSTILE_TOKEN),
                                        stubRequest()))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    @Test
    void login_wrongPassword_logsWarningWithoutEmailOrReason() {
        User u = activeUser();
        when(userRepository.findByEmailAndDeletedAtIsNull(u.getEmail())).thenReturn(Optional.of(u));
        when(credentialRepository.findByUserId(u.getId()))
                .thenReturn(Optional.of(credential(u.getId(), "STORED-HASH")));
        when(passwordEncoder.matches(eq("wrong"), eq("STORED-HASH"))).thenReturn(false);

        Logger logger = (Logger) LoggerFactory.getLogger(AuthServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(
                            () ->
                                    service.login(
                                            new LoginRequest(
                                                    u.getEmail(), "wrong", TURNSTILE_TOKEN),
                                            stubRequest()))
                    .isInstanceOf(AppException.class);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .anyMatch(event -> event.getLevel() == Level.WARN)
                .noneMatch(event -> event.getFormattedMessage().contains(u.getEmail()));
    }

    @Test
    void login_wrongPassword_throwsInvalidCredentials() {
        User u = activeUser();
        when(userRepository.findByEmailAndDeletedAtIsNull(u.getEmail())).thenReturn(Optional.of(u));
        when(credentialRepository.findByUserId(u.getId()))
                .thenReturn(Optional.of(credential(u.getId(), "STORED-HASH")));
        when(passwordEncoder.matches(eq("wrong"), eq("STORED-HASH"))).thenReturn(false);

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest(u.getEmail(), "wrong", TURNSTILE_TOKEN),
                                        stubRequest()))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    @Test
    void login_bannedUser_throwsAccountLocked() {
        User u = activeUser();
        when(userRepository.findByEmailAndDeletedAtIsNull(u.getEmail())).thenReturn(Optional.of(u));
        when(credentialRepository.findByUserId(u.getId()))
                .thenReturn(Optional.of(credential(u.getId(), "STORED-HASH")));
        when(passwordEncoder.matches(eq("any"), eq("STORED-HASH"))).thenReturn(true);
        doThrow(new AppException(ApiErrorCode.AUTH_ACCOUNT_LOCKED))
                .when(userStateValidator)
                .enforceActive(u);

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest(u.getEmail(), "any", TURNSTILE_TOKEN),
                                        stubRequest()))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_ACCOUNT_LOCKED);
    }

    @Test
    void login_suspendedUser_throwsAccountInactive() {
        User u = activeUser();
        when(userRepository.findByEmailAndDeletedAtIsNull(u.getEmail())).thenReturn(Optional.of(u));
        when(credentialRepository.findByUserId(u.getId()))
                .thenReturn(Optional.of(credential(u.getId(), "STORED-HASH")));
        when(passwordEncoder.matches(eq("any"), eq("STORED-HASH"))).thenReturn(true);
        doThrow(new AppException(ApiErrorCode.AUTH_ACCOUNT_INACTIVE))
                .when(userStateValidator)
                .enforceActive(u);

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest(u.getEmail(), "any", TURNSTILE_TOKEN),
                                        stubRequest()))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_ACCOUNT_INACTIVE);
    }

    @Test
    void login_nullPasswordHash_throwsInvalidCredentials() {
        User u = activeUser();
        UserCredential cred = credential(u.getId(), null);
        when(userRepository.findByEmailAndDeletedAtIsNull(u.getEmail())).thenReturn(Optional.of(u));
        when(credentialRepository.findByUserId(u.getId())).thenReturn(Optional.of(cred));

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest(u.getEmail(), "any", TURNSTILE_TOKEN),
                                        stubRequest()))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    @Test
    void login_unverifiedEmail_throwsEmailNotVerified() {
        User u = activeUser();
        UserCredential cred = credential(u.getId(), "STORED-HASH");
        when(userRepository.findByEmailAndDeletedAtIsNull(u.getEmail())).thenReturn(Optional.of(u));
        when(credentialRepository.findByUserId(u.getId())).thenReturn(Optional.of(cred));
        when(passwordEncoder.matches(eq(TEST_PASSWORD), eq("STORED-HASH"))).thenReturn(true);
        doThrow(new AppException(ApiErrorCode.AUTH_EMAIL_NOT_VERIFIED))
                .when(userStateValidator)
                .enforceEmailVerified(cred);

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest(
                                                u.getEmail(), TEST_PASSWORD, TURNSTILE_TOKEN),
                                        stubRequest()))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_EMAIL_NOT_VERIFIED);
    }

    @Test
    void login_success_returnsAccessAndRefreshTokens() {
        User u = activeUser();
        UserCredential cred = verifiedCredential(u.getId(), "STORED-HASH");
        when(userRepository.findByEmailAndDeletedAtIsNull(u.getEmail())).thenReturn(Optional.of(u));
        when(credentialRepository.findByUserId(u.getId())).thenReturn(Optional.of(cred));
        when(passwordEncoder.matches(eq(TEST_PASSWORD), eq("STORED-HASH"))).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(eq(u.getId()), eq("USER"), anyInt()))
                .thenReturn("ACCESS");
        when(refreshTokenService.issue(eq(u.getId()), any(), any(), any())).thenReturn("REFRESH");

        AuthResponse resp =
                service.login(
                        new LoginRequest(u.getEmail(), TEST_PASSWORD, TURNSTILE_TOKEN),
                        stubRequest());

        assertThat(resp.accessToken()).isEqualTo("ACCESS");
        assertThat(resp.refreshToken()).isEqualTo("REFRESH");
        assertThat(resp.user().id()).isEqualTo(u.getId());
    }

    @Test
    void login_success_logsInfoWithoutEmail() {
        User u = activeUser();
        UserCredential cred = verifiedCredential(u.getId(), "STORED-HASH");
        when(userRepository.findByEmailAndDeletedAtIsNull(u.getEmail())).thenReturn(Optional.of(u));
        when(credentialRepository.findByUserId(u.getId())).thenReturn(Optional.of(cred));
        when(passwordEncoder.matches(eq(TEST_PASSWORD), eq("STORED-HASH"))).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(eq(u.getId()), eq("USER"), anyInt()))
                .thenReturn("ACCESS");
        when(refreshTokenService.issue(eq(u.getId()), any(), any(), any())).thenReturn("REFRESH");

        Logger logger = (Logger) LoggerFactory.getLogger(AuthServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.login(
                    new LoginRequest(u.getEmail(), TEST_PASSWORD, TURNSTILE_TOKEN), stubRequest());
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .anyMatch(
                        event ->
                                event.getLevel() == Level.INFO
                                        && event.getFormattedMessage()
                                                .contains(u.getId().toString()))
                .noneMatch(event -> event.getFormattedMessage().contains(u.getEmail()));
    }

    @Test
    void login_validUsername_success_returnsTokens() {
        User u = activeUser();
        UserCredential cred = verifiedCredential(u.getId(), "STORED-HASH");
        when(userRepository.findByUsernameAndDeletedAtIsNull("alice")).thenReturn(Optional.of(u));
        when(credentialRepository.findByUserId(u.getId())).thenReturn(Optional.of(cred));
        when(passwordEncoder.matches(eq(TEST_PASSWORD), eq("STORED-HASH"))).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(eq(u.getId()), eq("USER"), anyInt()))
                .thenReturn("ACCESS");
        when(refreshTokenService.issue(eq(u.getId()), any(), any(), any())).thenReturn("REFRESH");

        AuthResponse resp =
                service.login(
                        new LoginRequest("alice", TEST_PASSWORD, TURNSTILE_TOKEN), stubRequest());

        assertThat(resp.accessToken()).isEqualTo("ACCESS");
        assertThat(resp.refreshToken()).isEqualTo("REFRESH");
        assertThat(resp.user().id()).isEqualTo(u.getId());
        verify(userRepository).findByUsernameAndDeletedAtIsNull("alice");
    }

    @Test
    void login_unknownUsername_throwsSameCodeAsWrongPassword() {
        when(userRepository.findByUsernameAndDeletedAtIsNull("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest("ghost", TEST_PASSWORD, TURNSTILE_TOKEN),
                                        stubRequest()))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                // Identical code to the wrong-password path so the two are indistinguishable.
                .isEqualTo(ApiErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    @Test
    void login_usernameWrongPassword_throwsInvalidCredentials() {
        User u = activeUser();
        when(userRepository.findByUsernameAndDeletedAtIsNull("alice")).thenReturn(Optional.of(u));
        when(credentialRepository.findByUserId(u.getId()))
                .thenReturn(Optional.of(credential(u.getId(), "STORED-HASH")));
        when(passwordEncoder.matches(eq("wrong"), eq("STORED-HASH"))).thenReturn(false);

        assertThatThrownBy(
                        () ->
                                service.login(
                                        new LoginRequest("alice", "wrong", TURNSTILE_TOKEN),
                                        stubRequest()))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    @Test
    void login_usernameUpperCase_resolvesCorrectly() {
        User u = activeUser();
        UserCredential cred = verifiedCredential(u.getId(), "STORED-HASH");
        // The identifier is passed through as typed. Case-insensitivity now lives in the query,
        // which compares lower(username) on both sides, not in a toLowerCase() before the call.
        when(userRepository.findByUsernameAndDeletedAtIsNull("ALICE")).thenReturn(Optional.of(u));
        when(credentialRepository.findByUserId(u.getId())).thenReturn(Optional.of(cred));
        when(passwordEncoder.matches(eq(TEST_PASSWORD), eq("STORED-HASH"))).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(eq(u.getId()), eq("USER"), anyInt()))
                .thenReturn("ACCESS");
        when(refreshTokenService.issue(eq(u.getId()), any(), any(), any())).thenReturn("REFRESH");

        AuthResponse resp =
                service.login(
                        new LoginRequest("ALICE", TEST_PASSWORD, TURNSTILE_TOKEN), stubRequest());

        assertThat(resp.accessToken()).isEqualTo("ACCESS");
        assertThat(resp.user().id()).isEqualTo(u.getId());
        verify(userRepository).findByUsernameAndDeletedAtIsNull("ALICE");
    }

    @Test
    void register_username_storedAsSubmitted() {
        UUID newId = UUID.randomUUID();
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        when(userRepository.save(any(User.class)))
                .thenAnswer(
                        inv -> {
                            User u = inv.getArgument(0);
                            u.setId(newId);
                            return u;
                        });
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn("HASH");

        service.register(
                new RegisterRequest("MixedCase", "a@b.c", TEST_PASSWORD, null, TURNSTILE_TOKEN),
                stubRequest());

        // Deliberate inversion: this asserted the stored value was lowercased. Identity is
        // case-insensitive via idx_users_username_lower, so the column no longer has to carry a
        // normalized value, and display is case-preserving.
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getUsername()).isEqualTo("MixedCase");
    }

    @Test
    void refresh_bannedUser_revokesNewTokenAndThrowsAccountLocked() {
        UUID userId = UUID.randomUUID();
        String newRawToken = "NEW-TOKEN";
        when(refreshTokenService.rotate(eq("OLD"), anyString()))
                .thenReturn(new RefreshTokenService.RotationResult(newRawToken, userId));
        User u = activeUser();
        u.setId(userId);
        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(u));
        doThrow(new AppException(ApiErrorCode.AUTH_ACCOUNT_LOCKED))
                .when(userStateValidator)
                .enforceActive(u);

        assertThatThrownBy(() -> service.refresh("OLD", stubRequest()))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_ACCOUNT_LOCKED);

        verify(refreshTokenService).revoke(newRawToken);
    }

    @Test
    void refresh_suspendedUser_revokesNewTokenAndThrowsAccountInactive() {
        UUID userId = UUID.randomUUID();
        String newRawToken = "NEW-TOKEN-SUSP";
        when(refreshTokenService.rotate(eq("OLD-SUSP"), anyString()))
                .thenReturn(new RefreshTokenService.RotationResult(newRawToken, userId));
        User u = activeUser();
        u.setId(userId);
        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(u));
        doThrow(new AppException(ApiErrorCode.AUTH_ACCOUNT_INACTIVE))
                .when(userStateValidator)
                .enforceActive(u);

        assertThatThrownBy(() -> service.refresh("OLD-SUSP", stubRequest()))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_ACCOUNT_INACTIVE);

        verify(refreshTokenService).revoke(newRawToken);
    }

    @Test
    void refresh_validToken_returnsNewTokenPair() {
        UUID userId = UUID.randomUUID();
        when(refreshTokenService.rotate(eq("OLD"), anyString()))
                .thenReturn(new RefreshTokenService.RotationResult("NEW", userId));
        User u = activeUser();
        u.setId(userId);
        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(u));
        when(credentialRepository.findByUserId(userId))
                .thenReturn(Optional.of(credential(userId, "HASH")));
        when(jwtTokenProvider.generateAccessToken(eq(userId), eq("USER"), anyInt()))
                .thenReturn("ACCESS-NEW");

        AuthResponse resp = service.refresh("OLD", stubRequest());

        assertThat(resp.accessToken()).isEqualTo("ACCESS-NEW");
        assertThat(resp.refreshToken()).isEqualTo("NEW");
    }

    @Test
    void refresh_invalidToken_propagatesAuthRefreshTokenInvalid() {
        when(refreshTokenService.rotate(anyString(), anyString()))
                .thenThrow(new AppException(ApiErrorCode.AUTH_REFRESH_TOKEN_INVALID));

        assertThatThrownBy(() -> service.refresh("BAD", stubRequest()))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    }

    @Test
    void logout_callsRevokeOnce() {
        service.logout("RAW");
        verify(refreshTokenService, times(1)).revoke("RAW");
    }

    @Test
    void logout_unknownToken_doesNotPropagateException() {
        // revoke is no-op-by-contract; the service must not propagate any exception even if
        // the underlying repository update returns zero rows.
        assertThatCode(() -> service.logout("UNKNOWN")).doesNotThrowAnyException();
    }

    @Test
    void logout_blacklistsAccessTokenAndRevokesRefreshToken() {
        String rawAccessToken = "raw-access";
        String jti = UUID.randomUUID().toString();
        Instant exp = Instant.now().plusSeconds(600);
        JwtClaims claims = new JwtClaims(UUID.randomUUID(), "USER", jti, 0, exp);
        when(jwtTokenProvider.validateAndParse(rawAccessToken)).thenReturn(claims);

        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken("principal", rawAccessToken));
        try {
            service.logout("REFRESH-RAW");
        } finally {
            SecurityContextHolder.clearContext();
        }

        verify(tokenBlacklistService).blacklist(eq(jti), longThat(ttl -> ttl > 0L && ttl <= 600L));
        verify(refreshTokenService).revoke("REFRESH-RAW");
    }

    @Test
    void logout_invalidAccessToken_stillRevokesRefreshToken() {
        String rawAccessToken = "expired-access";
        when(jwtTokenProvider.validateAndParse(rawAccessToken))
                .thenThrow(new AppException(ApiErrorCode.AUTH_TOKEN_EXPIRED));

        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken("principal", rawAccessToken));
        try {
            service.logout("REFRESH-RAW");
        } finally {
            SecurityContextHolder.clearContext();
        }

        verify(tokenBlacklistService, never()).blacklist(anyString(), anyLong());
        verify(refreshTokenService).revoke("REFRESH-RAW");
    }

    @Test
    void logout_noAuthContext_blacklistsNothingAndRevokesRefreshToken() {
        SecurityContextHolder.clearContext();

        service.logout("REFRESH-RAW");

        verify(tokenBlacklistService, never()).blacklist(anyString(), anyLong());
        verify(refreshTokenService).revoke("REFRESH-RAW");
    }

    @Test
    void resendVerification_delegatesDurableEventRecordingAndEqualizesTiming() {
        service.resendVerification(
                new ResendVerificationRequest("alice@example.com", TURNSTILE_TOKEN), CLIENT_IP);

        verify(authResendVerificationEventService)
                .recordResendVerificationRequest("alice@example.com");
        verify(forgotPasswordTimingEqualizer).equalizeFrom(anyLong());
        verify(tokenService, never()).createEmailVerificationToken(any());
    }

    @Test
    void resendVerification_equalizesTimingWhenEventRecordingFails() {
        doThrow(new IllegalStateException("db down"))
                .when(authResendVerificationEventService)
                .recordResendVerificationRequest("alice@example.com");

        assertThatThrownBy(
                        () ->
                                service.resendVerification(
                                        new ResendVerificationRequest(
                                                "alice@example.com", TURNSTILE_TOKEN),
                                        CLIENT_IP))
                .isInstanceOf(IllegalStateException.class);

        verify(forgotPasswordTimingEqualizer).equalizeFrom(anyLong());
    }

    @Test
    void forgotPassword_delegatesDurableEventRecordingAndEqualizesTiming() {
        ForgotPasswordRequest request =
                new ForgotPasswordRequest("alice@example.com", TURNSTILE_TOKEN);

        service.forgotPassword(request, CLIENT_IP);

        verify(authForgotPasswordEventService).recordForgotPasswordRequest(request.email());
        verify(forgotPasswordTimingEqualizer).equalizeFrom(anyLong());
        verify(tokenService, never()).createPasswordResetToken(any());
    }

    @Test
    void forgotPassword_equalizesTimingWhenEventRecordingFails() {
        ForgotPasswordRequest request =
                new ForgotPasswordRequest("alice@example.com", TURNSTILE_TOKEN);
        doThrow(new IllegalStateException("db down"))
                .when(authForgotPasswordEventService)
                .recordForgotPasswordRequest(request.email());

        assertThatThrownBy(() -> service.forgotPassword(request, CLIENT_IP))
                .isInstanceOf(IllegalStateException.class);

        verify(forgotPasswordTimingEqualizer).equalizeFrom(anyLong());
    }

    @Test
    void resetPassword_revokesAllSessionsAndRecordsNotificationEvent() {
        UUID userId = UUID.randomUUID();
        String raw = "RESET-RAW";
        when(tokenService.consumePasswordResetToken(raw)).thenReturn(userId);
        UserCredential cred = credential(userId, "OLD-HASH");
        when(credentialRepository.findByUserId(userId)).thenReturn(Optional.of(cred));
        when(passwordEncoder.encode("newPassword1")).thenReturn("NEW-HASH");
        User u = activeUser();
        u.setId(userId);
        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(u));

        service.resetPassword(
                new ResetPasswordRequest(raw, "newPassword1", TURNSTILE_TOKEN), CLIENT_IP);

        verify(refreshTokenService).revokeAllForUser(userId);
        verify(credentialRepository).save(cred);
        assertThat(cred.getPasswordHash()).isEqualTo("NEW-HASH");
        verify(authMailEventService).publishPasswordChanged(u);
    }

    @Test
    void resetPassword_unknownToken_throwsResetTokenInvalid() {
        when(tokenService.consumePasswordResetToken(anyString()))
                .thenThrow(new TokenNotFoundException("invalid"));

        assertThatThrownBy(
                        () ->
                                service.resetPassword(
                                        new ResetPasswordRequest(
                                                "ghost", "newPassword1", TURNSTILE_TOKEN),
                                        CLIENT_IP))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_RESET_TOKEN_INVALID);
        verify(refreshTokenService, never()).revokeAllForUser(any());
    }

    @Test
    void resetPassword_expiredToken_throwsResetTokenInvalid() {
        when(tokenService.consumePasswordResetToken(anyString()))
                .thenThrow(new TokenExpiredException("expired"));

        assertThatThrownBy(
                        () ->
                                service.resetPassword(
                                        new ResetPasswordRequest(
                                                "expired-token", "newPassword1", TURNSTILE_TOKEN),
                                        CLIENT_IP))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_RESET_TOKEN_INVALID);
        verify(refreshTokenService, never()).revokeAllForUser(any());
    }

    // FIX-23: resetPassword — credential with null passwordHash throws AUTH_RESET_TOKEN_INVALID
    @Test
    void resetPassword_oauthOnlyCredential_throwsResetTokenInvalid() {
        UUID userId = UUID.randomUUID();
        when(tokenService.consumePasswordResetToken("RESET-RAW")).thenReturn(userId);
        User u = activeUser();
        u.setId(userId);
        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(u));
        UserCredential cred = credential(userId, null);
        when(credentialRepository.findByUserId(userId)).thenReturn(Optional.of(cred));

        assertThatThrownBy(
                        () ->
                                service.resetPassword(
                                        new ResetPasswordRequest(
                                                "RESET-RAW", "newPassword1", TURNSTILE_TOKEN),
                                        CLIENT_IP))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_RESET_TOKEN_INVALID);
        verify(refreshTokenService, never()).revokeAllForUser(any());
    }

    // FIX-7: verifyEmail — TokenNotFoundException converts to AUTH_VERIFY_TOKEN_INVALID
    @Test
    void verifyEmail_tokenNotFound_throwsVerifyTokenInvalid() {
        when(tokenService.consumeEmailVerificationToken("BAD-TOKEN"))
                .thenThrow(new TokenNotFoundException("not found"));

        assertThatThrownBy(() -> service.verifyEmail("BAD-TOKEN", stubRequest()))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_VERIFY_TOKEN_INVALID);
    }

    // FIX-7: verifyEmail — TokenExpiredException converts to AUTH_VERIFY_TOKEN_INVALID
    @Test
    void verifyEmail_tokenExpired_throwsVerifyTokenInvalid() {
        when(tokenService.consumeEmailVerificationToken("EXPIRED-TOKEN"))
                .thenThrow(new TokenExpiredException("expired"));

        assertThatThrownBy(() -> service.verifyEmail("EXPIRED-TOKEN", stubRequest()))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_VERIFY_TOKEN_INVALID);
    }

    // FIX-7: verifyEmail — valid token marks email verified and issues session
    @Test
    void verifyEmail_validToken_marksEmailVerifiedAndIssuesSession() {
        UUID userId = UUID.randomUUID();
        when(tokenService.consumeEmailVerificationToken("VALID-TOKEN")).thenReturn(userId);
        UserCredential cred = credential(userId, "HASH");
        when(credentialRepository.findByUserId(userId)).thenReturn(Optional.of(cred));
        User user = activeUser();
        user.setId(userId);
        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(jwtTokenProvider.generateAccessToken(eq(userId), anyString(), anyInt()))
                .thenReturn("ACCESS");
        when(refreshTokenService.issue(eq(userId), any(), any(), any())).thenReturn("REFRESH");

        AuthResponse resp = service.verifyEmail("VALID-TOKEN", stubRequest());

        assertThat(resp.accessToken()).isEqualTo("ACCESS");
        assertThat(resp.refreshToken()).isEqualTo("REFRESH");
        assertThat(resp.user().emailVerified()).isTrue();
        verify(credentialRepository).save(cred);
        assertThat(cred.isEmailVerified()).isTrue();
        assertThat(cred.getEmailVerifiedAt()).isNotNull();
    }

    private static User activeUser() {
        return User.builder()
                .id(UUID.randomUUID())
                .username("alice")
                .email("alice@example.com")
                .displayName("Alice")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .isPrivate(false)
                .isVerified(false)
                .build();
    }

    private static UserCredential credential(UUID userId, String hash) {
        return UserCredential.builder()
                .userId(userId)
                .passwordHash(hash)
                .emailVerified(false)
                .build();
    }

    private static UserCredential verifiedCredential(UUID userId, String hash) {
        return UserCredential.builder()
                .userId(userId)
                .passwordHash(hash)
                .emailVerified(true)
                .build();
    }

    @SuppressWarnings("unused")
    private HttpServletRequest unusedToSatisfyImport() {
        return null;
    }
}
