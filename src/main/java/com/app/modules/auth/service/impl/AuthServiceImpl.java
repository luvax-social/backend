package com.app.modules.auth.service.impl;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.security.jwt.JwtClaims;
import com.app.common.security.jwt.JwtProperties;
import com.app.common.security.jwt.JwtTokenProvider;
import com.app.common.security.service.RefreshTokenService;
import com.app.common.security.service.TokenBlacklistService;
import com.app.common.security.util.IpExtractor;
import com.app.common.turnstile.AuthTurnstileGuard;
import com.app.common.turnstile.TurnstileSurface;
import com.app.modules.auth.dto.request.ForgotPasswordRequest;
import com.app.modules.auth.dto.request.LoginRequest;
import com.app.modules.auth.dto.request.OAuth2ExchangeRequest;
import com.app.modules.auth.dto.request.RegisterRequest;
import com.app.modules.auth.dto.request.ResendVerificationRequest;
import com.app.modules.auth.dto.request.ResetPasswordRequest;
import com.app.modules.auth.dto.response.AuthResponse;
import com.app.modules.auth.entity.UserCredential;
import com.app.modules.auth.exception.TokenExpiredException;
import com.app.modules.auth.exception.TokenNotFoundException;
import com.app.modules.auth.mapper.AuthMapper;
import com.app.modules.auth.repository.UserCredentialRepository;
import com.app.modules.auth.service.AuthForgotPasswordEventService;
import com.app.modules.auth.service.AuthMailEventService;
import com.app.modules.auth.service.AuthResendVerificationEventService;
import com.app.modules.auth.service.AuthService;
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

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final UserCredentialRepository credentialRepository;
    private final UserSettingsRepository settingsRepository;
    private final TokenService tokenService;
    private final RefreshTokenService refreshTokenService;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final PasswordEncoder passwordEncoder;
    private final AuthMailEventService authMailEventService;
    private final AuthForgotPasswordEventService authForgotPasswordEventService;
    private final AuthResendVerificationEventService authResendVerificationEventService;
    private final ForgotPasswordTimingEqualizer forgotPasswordTimingEqualizer;
    private final AuthMapper authMapper;
    private final TokenBlacklistService tokenBlacklistService;
    private final IpExtractor ipExtractor;
    private final UserStateValidator userStateValidator;
    private final OAuth2ExchangeCodeService oauth2ExchangeCodeService;
    private final TransactionTemplate transactionTemplate;
    private final UserEventRecorder userEventRecorder;
    private final AuthTurnstileGuard turnstileGuard;

    // Pre-computed BCrypt hash used to equalize CPU work on login failure paths so that
    // "email not found" is indistinguishable from "wrong password" via response timing.
    private String dummyPasswordHash;

    public AuthServiceImpl(
            UserRepository userRepository,
            UserCredentialRepository credentialRepository,
            UserSettingsRepository settingsRepository,
            TokenService tokenService,
            RefreshTokenService refreshTokenService,
            JwtTokenProvider jwtTokenProvider,
            JwtProperties jwtProperties,
            PasswordEncoder passwordEncoder,
            AuthMailEventService authMailEventService,
            AuthForgotPasswordEventService authForgotPasswordEventService,
            AuthResendVerificationEventService authResendVerificationEventService,
            ForgotPasswordTimingEqualizer forgotPasswordTimingEqualizer,
            AuthMapper authMapper,
            TokenBlacklistService tokenBlacklistService,
            IpExtractor ipExtractor,
            UserStateValidator userStateValidator,
            OAuth2ExchangeCodeService oauth2ExchangeCodeService,
            TransactionTemplate transactionTemplate,
            UserEventRecorder userEventRecorder,
            AuthTurnstileGuard turnstileGuard) {
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.settingsRepository = settingsRepository;
        this.tokenService = tokenService;
        this.refreshTokenService = refreshTokenService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.passwordEncoder = passwordEncoder;
        this.authMailEventService = authMailEventService;
        this.authForgotPasswordEventService = authForgotPasswordEventService;
        this.authResendVerificationEventService = authResendVerificationEventService;
        this.forgotPasswordTimingEqualizer = forgotPasswordTimingEqualizer;
        this.authMapper = authMapper;
        this.tokenBlacklistService = tokenBlacklistService;
        this.ipExtractor = ipExtractor;
        this.userStateValidator = userStateValidator;
        this.oauth2ExchangeCodeService = oauth2ExchangeCodeService;
        this.transactionTemplate = transactionTemplate;
        this.userEventRecorder = userEventRecorder;
        this.turnstileGuard = turnstileGuard;
    }

    @PostConstruct
    void initDummyPasswordHash() {
        // Encoded once at bean init; the value is irrelevant because this hash never matches
        // any real user password. It exists solely to keep BCrypt cost on the failure paths.
        this.dummyPasswordHash =
                passwordEncoder.encode("login-timing-equalizer-" + UUID.randomUUID());
    }

    @Override
    public void register(RegisterRequest request, HttpServletRequest httpRequest) {
        String clientIp = ipExtractor.extract(httpRequest);
        // First statement in the method, so a rejected challenge costs no uniqueness query and
        // leaves no account, no credential, no settings row and no mail event behind.
        turnstileGuard.require(request.turnstileToken(), clientIp, TurnstileSurface.REGISTER);

        // Return a single generic conflict code for both email and username collisions so the
        // response cannot be used to enumerate which emails or usernames are already registered.
        if (userRepository.existsByEmail(request.email())
                || userRepository.existsByUsername(request.username())) {
            throw new AppException(ApiErrorCode.USER_ALREADY_EXISTS);
        }

        // Hash the password before opening a transaction so the connection is not held during
        // the BCrypt computation (~200-300ms at cost 12).
        String passwordHash = passwordEncoder.encode(request.password());
        String registrationIp = clientIp;

        transactionTemplate.executeWithoutResult(
                status -> {
                    // Re-check uniqueness inside the transaction to close the TOCTOU window.
                    if (userRepository.existsByEmail(request.email())
                            || userRepository.existsByUsername(request.username())) {
                        throw new AppException(ApiErrorCode.USER_ALREADY_EXISTS);
                    }

                    String displayName =
                            StringUtils.hasText(request.displayName())
                                    ? request.displayName()
                                    : request.username();

                    User user =
                            User.builder()
                                    // Stored exactly as submitted: identity is case-insensitive,
                                    // display is case-preserving. Uniqueness is enforced by the
                                    // lower(username) index and checked case-insensitively above,
                                    // so nothing depends on the stored value being lowercase.
                                    .username(request.username())
                                    .email(request.email())
                                    .displayName(displayName)
                                    .role(UserRole.USER)
                                    .status(UserStatus.ACTIVE)
                                    .isPrivate(false)
                                    .isVerified(false)
                                    // Same transaction as the users insert, so an account can never
                                    // exist without the origin that created it.
                                    .registrationIp(registrationIp)
                                    .build();
                    User savedUser = userRepository.save(user);

                    UserCredential credential =
                            UserCredential.builder()
                                    .userId(savedUser.getId())
                                    .passwordHash(passwordHash)
                                    .emailVerified(false)
                                    .build();
                    credentialRepository.save(credential);

                    settingsRepository.save(
                            UserSettings.builder().userId(savedUser.getId()).build());

                    authMailEventService.publishUserRegistered(savedUser);
                    authMailEventService.publishEmailVerificationRequested(
                            savedUser, savedUser.getId());
                    log.info("User registered: userId={}", savedUser.getId());
                });
    }

    @Override
    public AuthResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        // Before the account lookup and before BCrypt runs, so a rejected challenge issues no
        // session and cannot be used to probe whether an identifier exists.
        turnstileGuard.require(
                request.turnstileToken(), ipExtractor.extract(httpRequest), TurnstileSurface.LOGIN);

        // Usernames cannot contain '@' (enforced by the registration pattern), so an '@' in the
        // identifier unambiguously marks an email; anything else is a username. The username
        // lookup normalizes both sides of the comparison itself, so the identifier is passed
        // through as typed: case-insensitivity comes from the query, not from the stored casing.
        String rawIdentifier = request.identifier().trim();
        Optional<User> userOpt;
        if (rawIdentifier.contains("@")) {
            userOpt = userRepository.findByEmailAndDeletedAtIsNull(rawIdentifier);
        } else {
            userOpt = userRepository.findByUsernameAndDeletedAtIsNull(rawIdentifier);
        }
        User user = userOpt.orElse(null);
        UserCredential credential =
                user == null ? null : credentialRepository.findByUserId(user.getId()).orElse(null);

        // Run BCrypt unconditionally so unknown-email, missing-credential, and wrong-password
        // paths are indistinguishable via response timing. The dummy hash is a real BCrypt
        // hash that no user password can satisfy.
        // BCrypt runs outside any transaction — no connection held during hashing.
        String hashForCompare =
                credential != null && credential.getPasswordHash() != null
                        ? credential.getPasswordHash()
                        : dummyPasswordHash;
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashForCompare);

        if (user == null || credential == null || credential.getPasswordHash() == null) {
            // Deliberately no email/reason detail in this log -- distinguishing "unknown email"
            // from "wrong password" via logs would defeat the timing-equalization above.
            log.warn("Login failed: reason=invalid_credentials");
            throw new AppException(ApiErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        // Verify the password before any account-state enforcement so that account status
        // (banned, suspended, deactivated) is never revealed to a caller who has not proven
        // knowledge of the credentials. Otherwise a wrong-password attempt against a banned
        // account would surface a 403, leaking status as an enumeration oracle.
        if (!passwordMatches) {
            log.warn("Login failed: reason=invalid_credentials");
            throw new AppException(ApiErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        userStateValidator.enforceActive(user);
        userStateValidator.enforceEmailVerified(credential);

        // issueSession writes a refresh_token row; run inside a short write-only transaction.
        // TransactionTemplate ensures the proxy is used correctly (no self-call bypass).
        User finalUser = user;
        UserCredential finalCredential = credential;
        AuthResponse response =
                transactionTemplate.execute(
                        status ->
                                issueSession(
                                        finalUser, finalCredential.isEmailVerified(), httpRequest));
        log.info("User login: userId={}", user.getId());
        return response;
    }

    @Override
    @Transactional
    public AuthResponse refresh(String rawRefreshToken, HttpServletRequest httpRequest) {
        RefreshTokenService.RotationResult rotation =
                refreshTokenService.rotate(rawRefreshToken, ipExtractor.extract(httpRequest));

        User user =
                userRepository
                        .findByIdAndDeletedAtIsNull(rotation.userId())
                        .orElseThrow(
                                () -> new AppException(ApiErrorCode.AUTH_REFRESH_TOKEN_INVALID));

        try {
            userStateValidator.enforceActive(user);
        } catch (AppException ex) {
            refreshTokenService.revoke(rotation.newRawToken());
            throw ex;
        }

        boolean emailVerified =
                credentialRepository
                        .findByUserId(user.getId())
                        .map(UserCredential::isEmailVerified)
                        .orElse(false);

        String accessToken =
                jwtTokenProvider.generateAccessToken(
                        user.getId(), user.getRole().name(), user.getTokenEpoch());

        return new AuthResponse(
                accessToken,
                rotation.newRawToken(),
                jwtProperties.accessTokenTtl(),
                AuthResponse.BEARER,
                authMapper.toAuthenticatedUserResponse(user, emailVerified));
    }

    @Override
    @Transactional
    public void logout(String rawRefreshToken) {
        // Blacklist the current access token so it cannot authenticate again before its
        // natural expiry. The raw token was placed on the Authentication credentials by
        // JwtAuthenticationFilter; absence (e.g. logout without an Authorization header)
        // is tolerated and only the refresh token is revoked.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // Revoke the refresh token first so that if the subsequent blacklist call fails the
        // refresh token is already invalidated; failing before revoke would leave neither
        // invalidation applied.
        refreshTokenService.revoke(rawRefreshToken);

        // Retained for defensive completeness — public path now requires authentication
        // (SecurityConfig enforces authenticated() on /logout).
        if (auth != null && auth.getCredentials() instanceof String rawToken) {
            JwtClaims claims;
            long remaining;
            try {
                claims = jwtTokenProvider.validateAndParse(rawToken);
                remaining =
                        claims.expiresAt() == null
                                ? 0L
                                : claims.expiresAt().getEpochSecond()
                                        - Instant.now().getEpochSecond();
            } catch (AppException ignored) {
                // Token already invalid — nothing to blacklist; refresh token is revoked above.
                return;
            }
            // Blacklist failures must propagate; the refresh token is already revoked above.
            tokenBlacklistService.blacklist(claims.jti(), remaining);
        }
    }

    @Override
    @Transactional
    public AuthResponse verifyEmail(String rawToken, HttpServletRequest httpRequest) {
        UUID userId;
        try {
            userId = tokenService.consumeEmailVerificationToken(rawToken);
        } catch (TokenNotFoundException | TokenExpiredException e) {
            throw new AppException(ApiErrorCode.AUTH_VERIFY_TOKEN_INVALID);
        }

        UserCredential credential =
                credentialRepository
                        .findByUserId(userId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.AUTH_TOKEN_INVALID));
        credential.setEmailVerified(true);
        credential.setEmailVerifiedAt(OffsetDateTime.now());
        credentialRepository.save(credential);

        User user =
                userRepository
                        .findByIdAndDeletedAtIsNull(userId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.AUTH_TOKEN_INVALID));
        return issueSession(user, true, httpRequest);
    }

    @Override
    public void resendVerification(ResendVerificationRequest request, String clientIp) {
        // Mirrors forgotPassword: the durable event recording runs in a separate transactional
        // delegate and response time is normalised to a floor, so a registered address is not
        // distinguishable from an unregistered one by latency. The equalizer runs on every path,
        // including when the delegate throws.
        long startNanos = System.nanoTime();
        try {
            // Inside the equalized window, not before it. The siteverify call takes real and
            // variable time; measuring from before it would leave that variance outside the floor
            // and hand back the latency channel the equalizer exists to close. A rejected challenge
            // throws from here and still leaves through the finally below, so a refusal takes the
            // same wall time as a success.
            turnstileGuard.require(
                    request.turnstileToken(), clientIp, TurnstileSurface.RESEND_VERIFICATION);
            authResendVerificationEventService.recordResendVerificationRequest(request.email());
        } finally {
            forgotPasswordTimingEqualizer.equalizeFrom(startNanos);
        }
    }

    @Override
    public void forgotPassword(ForgotPasswordRequest request, String clientIp) {
        long startNanos = System.nanoTime();
        try {
            // Inside the equalized window, not before it. The siteverify call takes real and
            // variable time; measuring from before it would leave that variance outside the floor
            // and hand back the enumeration channel the equalizer exists to close. A rejected
            // challenge throws from here and still leaves through the finally below, so a refusal
            // takes the same wall time as a success and no reset mail is enqueued.
            turnstileGuard.require(
                    request.turnstileToken(), clientIp, TurnstileSurface.FORGOT_PASSWORD);
            authForgotPasswordEventService.recordForgotPasswordRequest(request.email());
        } finally {
            forgotPasswordTimingEqualizer.equalizeFrom(startNanos);
        }
    }

    @Override
    public void resetPassword(ResetPasswordRequest request, String clientIp) {
        // Before the reset token is consumed, so a rejected challenge leaves the single-use link
        // intact and the user can try again with a fresh widget rather than a dead link.
        turnstileGuard.require(request.turnstileToken(), clientIp, TurnstileSurface.RESET_PASSWORD);

        // Hash the new password before opening the transaction so the connection is not
        // held during BCrypt computation.
        String newPasswordHash = passwordEncoder.encode(request.newPassword());

        transactionTemplate.executeWithoutResult(
                status -> {
                    UUID userId;
                    try {
                        userId = tokenService.consumePasswordResetToken(request.token());
                    } catch (TokenNotFoundException | TokenExpiredException e) {
                        throw new AppException(ApiErrorCode.AUTH_RESET_TOKEN_INVALID);
                    }

                    User user =
                            userRepository
                                    .findByIdAndDeletedAtIsNull(userId)
                                    .orElseThrow(
                                            () ->
                                                    new AppException(
                                                            ApiErrorCode.AUTH_RESET_TOKEN_INVALID));

                    userStateValidator.enforceActive(user);

                    UserCredential credential =
                            credentialRepository
                                    .findByUserId(userId)
                                    .orElseThrow(
                                            () ->
                                                    new AppException(
                                                            ApiErrorCode.AUTH_RESET_TOKEN_INVALID));
                    if (credential.getPasswordHash() == null) {
                        throw new AppException(ApiErrorCode.AUTH_RESET_TOKEN_INVALID);
                    }
                    credential.setPasswordHash(newPasswordHash);
                    credentialRepository.save(credential);

                    refreshTokenService.revokeAllForUser(userId);

                    authMailEventService.publishPasswordChanged(user);
                });
    }

    @Override
    @Transactional
    public AuthResponse exchangeOAuth2Code(
            OAuth2ExchangeRequest request, HttpServletRequest httpRequest) {
        UUID userId = oauth2ExchangeCodeService.consumeExchangeCode(request.code());

        User user =
                userRepository
                        .findByIdAndDeletedAtIsNull(userId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ApiErrorCode.AUTH_OAUTH2_EXCHANGE_CODE_INVALID));

        userStateValidator.enforceActive(user);

        boolean emailVerified =
                credentialRepository
                        .findByUserId(user.getId())
                        .map(UserCredential::isEmailVerified)
                        // OAuth-authenticated users have their email verified by the IdP.
                        .orElse(true);

        return issueSession(user, emailVerified, httpRequest);
    }

    // Every caller runs this inside a write transaction, which is what lets the last-login write
    // below commit with the refresh_tokens row rather than as a separate statement.
    private AuthResponse issueSession(
            User user, boolean emailVerified, HttpServletRequest httpRequest) {
        String clientIp = ipExtractor.extract(httpRequest);
        // Only a real session issuance advances these. The refresh path deliberately does not call
        // this method: a "last login" that moved on every token refresh would stop being a login
        // signal and would report an idle background tab as recent activity.
        user.setLastLoginAt(OffsetDateTime.now());
        user.setLastLoginIp(clientIp);
        userRepository.save(user);
        String accessToken =
                jwtTokenProvider.generateAccessToken(
                        user.getId(), user.getRole().name(), user.getTokenEpoch());
        String refreshToken =
                refreshTokenService.issue(
                        user.getId(),
                        null,
                        httpRequest.getHeader(HttpHeaders.USER_AGENT),
                        clientIp);
        // Placed here rather than in login() so every route that issues a session is covered:
        // password login, the verify-and-sign-in link, and the OAuth2 code exchange. The refresh
        // path does not reach this method, which is exactly right - a token refresh is not a login.
        userEventRecorder.recordSessionStart(user.getId());
        return new AuthResponse(
                accessToken,
                refreshToken,
                jwtProperties.accessTokenTtl(),
                AuthResponse.BEARER,
                authMapper.toAuthenticatedUserResponse(user, emailVerified));
    }
}
