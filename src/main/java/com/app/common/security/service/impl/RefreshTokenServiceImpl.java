package com.app.common.security.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.GeneralSecurityException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.security.jwt.JwtProperties;
import com.app.common.security.service.RefreshTokenService;
import com.app.modules.auth.entity.RefreshToken;
import com.app.modules.auth.repository.RefreshTokenRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class RefreshTokenServiceImpl implements RefreshTokenService {

    private static final String SHA_256 = "SHA-256";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RefreshTokenRepository repository;
    private final JwtProperties jwtProperties;

    public RefreshTokenServiceImpl(RefreshTokenRepository repository, JwtProperties jwtProperties) {
        this.repository = repository;
        this.jwtProperties = jwtProperties;
    }

    @Override
    @Transactional
    public String issue(UUID userId, String deviceId, String userAgent, String ipAddress) {
        byte[] buf = new byte[32];
        SECURE_RANDOM.nextBytes(buf);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        return persistIssuedToken(userId, deviceId, userAgent, ipAddress, rawToken);
    }

    private String persistIssuedToken(UUID userId, String deviceId, String userAgent,
            String ipAddress, String rawToken) {
        RefreshToken entity =
                RefreshToken.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .tokenHash(sha256(rawToken))
                        .deviceId(deviceId)
                        .userAgent(userAgent)
                        .ipAddress(ipAddress)
                        .expiresAt(
                                OffsetDateTime.now().plusSeconds(jwtProperties.refreshTokenTtl()))
                        .build();
        repository.save(entity);
        return rawToken;
    }

    @Override
    // Rotation must commit independently so the old token is always durably revoked even if the
    // outer refresh transaction rolls back after a subsequent account-status check.
    //
    // noRollbackFor is what makes the revocations below durable. Every AppException this method
    // throws is a business rejection raised *after* a revoking UPDATE that has to outlive it, so
    // the default rollback-on-unchecked-exception silently undid the write while the log line
    // above it survived, reporting a remediation that never happened. Any future branch that
    // throws AppException here must be one whose writes are also meant to commit.
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = AppException.class)
    public RotationResult rotate(String rawToken, String ipAddress) {
        return rotateInternal(rawToken, ipAddress, null);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = AppException.class)
    public RotationResult rotate(String rawToken, String ipAddress, String requestKey) {
        if (requestKey == null || !requestKey.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")) {
            throw new AppException(ApiErrorCode.VALIDATION_ERROR);
        }
        return rotateInternal(rawToken, ipAddress, requestKey.toLowerCase(java.util.Locale.ROOT));
    }

    private RotationResult rotateInternal(String rawToken, String ipAddress, String requestKey) {
        String hash = sha256(rawToken);
        RefreshToken existing = repository.findByTokenHash(hash).orElse(null);

        if (existing == null) {
            // Equalize timing with the known-revoked path (which performs additional UPDATEs to
            // revoke all active sessions). A 2-8ms randomized sleep masks the DB-round-trip gap;
            // this is a coarse mitigation, not a constant-time guarantee.
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(2, 8));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            throw new AppException(ApiErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }

        if (existing.getRevokedAt() != null) {
            RotationResult retry = recoverRotation(rawToken, hash, requestKey);
            if (retry != null) return retry;
            // Replay of an already-revoked refresh token is the OAuth 2.0 Security BCP signal for
            // token theft -- without this log, revoking every session for the user is invisible.
            log.warn("Refresh token replay detected for userId={}", existing.getUserId());
            repository.revokeAllActiveByUserId(existing.getUserId(), OffsetDateTime.now());
            throw new AppException(ApiErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (existing.getExpiresAt().isBefore(now)) {
            repository.revokeByTokenHash(hash, now);
            throw new AppException(ApiErrorCode.AUTH_REFRESH_TOKEN_EXPIRED);
        }

        // Conditional UPDATE is the atomic gate. If zero rows update, a concurrent rotation
        // already won the race; treat this as a replay attempt and revoke every active session
        // for the user (token theft detection per OAuth 2.0 Security BCP).
        int revoked = repository.revokeByTokenHash(hash, now);
        if (revoked == 0) {
            RotationResult retry = recoverRotation(rawToken, hash, requestKey);
            if (retry != null) return retry;
            // Lost the conditional-UPDATE race: same replay-detection signal as an already-revoked
            // token above -- log before revoking every session for the user.
            log.warn(
                    "Refresh token replay detected (concurrent rotation race) for userId={}",
                    existing.getUserId());
            repository.revokeAllActiveByUserId(existing.getUserId(), now);
            throw new AppException(ApiErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }
        String newRaw = requestKey == null
                ? issue(
                        existing.getUserId(),
                        existing.getDeviceId(),
                        existing.getUserAgent(),
                        ipAddress)
                : persistIssuedToken(existing.getUserId(), existing.getDeviceId(),
                        existing.getUserAgent(), ipAddress, deriveRotationToken(rawToken, requestKey));
        if (requestKey != null && repository.linkRotation(hash, sha256(requestKey), sha256(newRaw)) != 1) {
            // Roll back the entire rotation rather than commit a consumed token
            // whose response could not be recovered after navigation.
            throw new IllegalStateException("Unable to link refresh rotation");
        }
        return new RotationResult(newRaw, existing.getUserId());
    }

    private RotationResult recoverRotation(String rawToken, String hash, String requestKey) {
        if (requestKey == null) return null;
        OffsetDateTime now = OffsetDateTime.now();
        var retry = repository.findRotationRetry(hash, sha256(requestKey), now.minusSeconds(60), now);
        if (retry.isEmpty()) return null;
        String child = deriveRotationToken(rawToken, requestKey);
        if (!MessageDigest.isEqual(sha256(child).getBytes(StandardCharsets.UTF_8),
                retry.get().getTokenHash().getBytes(StandardCharsets.UTF_8))) return null;
        return new RotationResult(child, retry.get().getUserId());
    }

    private String deriveRotationToken(String rawToken, String requestKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwtProperties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] input = ("luvax:refresh-rotation:" + rawToken + ":" + requestKey).getBytes(StandardCharsets.UTF_8);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to derive refresh rotation", ex);
        }
    }

    @Override
    // Revocation must commit independently so that a banned or suspended user's newly rotated
    // token is durably revoked even if the outer refresh transaction rolls back.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revoke(String rawToken) {
        if (rawToken == null) {
            return;
        }
        repository.revokeByTokenHash(sha256(rawToken), OffsetDateTime.now());
    }

    @Override
    @Transactional
    public int revokeAllForUser(UUID userId) {
        return repository.revokeAllActiveByUserId(userId, OffsetDateTime.now());
    }

    @Override
    @Transactional
    public boolean revokeSessionForUser(UUID userId, UUID sessionId) {
        int revoked = repository.revokeByIdForUser(sessionId, userId, OffsetDateTime.now());
        if (revoked > 0) {
            return true;
        }
        // Nothing was updated, which is either "already revoked" or "not this account's session".
        // Only the second is an error, so the two are separated rather than collapsed into one
        // answer: a reviewer clicking revoke twice must not be shown a failure, and a caller
        // naming another account's session must not be told it exists.
        if (!repository.existsByIdAndUserId(sessionId, userId)) {
            throw new AppException(ApiErrorCode.NOT_FOUND);
        }
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findSessionIdByRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return repository
                .findByTokenHashAndRevokedAtIsNull(sha256(rawToken))
                .filter(token -> token.getExpiresAt().isAfter(OffsetDateTime.now()))
                .map(RefreshToken::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ActiveSession> listActiveSessions(UUID userId, int limit) {
        return repository.findActiveByUserId(userId, OffsetDateTime.now(), limit).stream()
                .map(
                        token ->
                                new ActiveSession(
                                        token.getId(),
                                        token.getDeviceId(),
                                        token.getUserAgent(),
                                        token.getIpAddress(),
                                        token.getCreatedAt(),
                                        token.getExpiresAt()))
                .toList();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable in JVM", e);
        }
    }
}
