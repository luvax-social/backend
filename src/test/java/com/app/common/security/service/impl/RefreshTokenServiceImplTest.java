package com.app.common.security.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.security.jwt.JwtProperties;
import com.app.common.security.service.RefreshTokenService;
import com.app.modules.auth.entity.RefreshToken;
import com.app.modules.auth.repository.RefreshTokenRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceImplTest {

    @Mock private RefreshTokenRepository repository;

    private RefreshTokenServiceImpl service;

    @BeforeEach
    void setUp() {
        JwtProperties properties =
                new JwtProperties("secret-32-chars-secret-32-chars--", "iss", "App", 900, 3600);
        this.service = new RefreshTokenServiceImpl(repository, properties);
    }

    @Test
    void rotate_sameRequestAfterLostResponse_returnsOriginalActiveChild() {
        String raw = UUID.randomUUID().toString();
        String requestKey = UUID.randomUUID().toString();
        UUID userId = UUID.randomUUID();
        RefreshToken parent = RefreshToken.builder().id(UUID.randomUUID()).userId(userId)
                .tokenHash(sha256(raw)).expiresAt(OffsetDateTime.now().plusMinutes(30)).build();
        when(repository.findByTokenHash(sha256(raw))).thenReturn(Optional.of(parent));
        when(repository.revokeByTokenHash(eq(sha256(raw)), any())).thenReturn(1);
        when(repository.linkRotation(eq(sha256(raw)), eq(sha256(requestKey)), anyString())).thenReturn(1);
        var issued = service.rotate(raw, "127.0.0.1", requestKey);
        parent.setRevokedAt(OffsetDateTime.now());
        var retry = org.mockito.Mockito.mock(RefreshTokenRepository.RotationRetry.class);
        when(retry.getUserId()).thenReturn(userId);
        when(retry.getTokenHash()).thenReturn(sha256(issued.newRawToken()));
        when(repository.findRotationRetry(eq(sha256(raw)), eq(sha256(requestKey)), any(), any()))
                .thenReturn(Optional.of(retry));

        var recovered = service.rotate(raw, "127.0.0.1", requestKey);

        assertThat(recovered).isEqualTo(issued);
        verify(repository, never()).revokeAllActiveByUserId(any(), any());
    }

    @Test
    void rotate_differentRequestOnConsumedToken_keepsReplayRevocation() {
        String raw = UUID.randomUUID().toString();
        String requestKey = UUID.randomUUID().toString();
        UUID userId = UUID.randomUUID();
        RefreshToken parent = RefreshToken.builder().id(UUID.randomUUID()).userId(userId)
                .tokenHash(sha256(raw)).revokedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusMinutes(30)).build();
        when(repository.findByTokenHash(sha256(raw))).thenReturn(Optional.of(parent));
        when(repository.findRotationRetry(eq(sha256(raw)), eq(sha256(requestKey)), any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate(raw, "127.0.0.1", requestKey)).isInstanceOf(AppException.class);
        verify(repository).revokeAllActiveByUserId(eq(userId), any());
    }

    @Test
    void rotate_retryWithMismatchedChildHash_keepsReplayRevocation() {
        String raw = UUID.randomUUID().toString();
        String requestKey = UUID.randomUUID().toString();
        UUID userId = UUID.randomUUID();
        RefreshToken parent = RefreshToken.builder().id(UUID.randomUUID()).userId(userId)
                .tokenHash(sha256(raw)).revokedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusMinutes(30)).build();
        var retry = org.mockito.Mockito.mock(RefreshTokenRepository.RotationRetry.class);
        when(retry.getTokenHash()).thenReturn(sha256("another child"));
        when(repository.findByTokenHash(sha256(raw))).thenReturn(Optional.of(parent));
        when(repository.findRotationRetry(eq(sha256(raw)), eq(sha256(requestKey)), any(), any()))
                .thenReturn(Optional.of(retry));

        assertThatThrownBy(() -> service.rotate(raw, "127.0.0.1", requestKey)).isInstanceOf(AppException.class);
        verify(repository).revokeAllActiveByUserId(eq(userId), any());
    }

    @Test
    void issue_persistsHashedTokenNotRawValue() {
        UUID userId = UUID.randomUUID();

        String raw = service.issue(userId, "device-1", "ua", "1.2.3.4");

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(captor.capture());
        RefreshToken saved = captor.getValue();
        assertThat(saved.getTokenHash()).isNotEqualTo(raw);
        assertThat(saved.getTokenHash()).isEqualTo(sha256(raw));
        assertThat(saved.getTokenHash()).hasSize(64);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getDeviceId()).isEqualTo("device-1");
        assertThat(saved.getUserAgent()).isEqualTo("ua");
        assertThat(saved.getIpAddress()).isEqualTo("1.2.3.4");
    }

    @Test
    void issue_setsExpiryEqualToTtl() {
        OffsetDateTime before = OffsetDateTime.now();

        service.issue(UUID.randomUUID(), null, null, null);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(captor.capture());
        Duration delta = Duration.between(before, captor.getValue().getExpiresAt());
        assertThat(delta).isBetween(Duration.ofSeconds(3590), Duration.ofSeconds(3610));
    }

    @Test
    void issue_rawTokenIs43CharUrlSafeBase64() {
        String raw = service.issue(UUID.randomUUID(), null, null, null);

        assertThat(raw).hasSize(43);
        assertThat(raw).matches("[A-Za-z0-9\\-_]+");
    }

    @Test
    void issue_consecutiveCalls_produceDifferentTokens() {
        String first = service.issue(UUID.randomUUID(), null, null, null);
        String second = service.issue(UUID.randomUUID(), null, null, null);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void rotate_validToken_revokesOldAndIssuesNew() {
        String raw = UUID.randomUUID().toString();
        String hash = sha256(raw);
        UUID userId = UUID.randomUUID();
        RefreshToken existing =
                RefreshToken.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .tokenHash(hash)
                        .expiresAt(OffsetDateTime.now().plusMinutes(30))
                        .build();
        when(repository.findByTokenHash(hash)).thenReturn(Optional.of(existing));
        when(repository.revokeByTokenHash(eq(hash), any())).thenReturn(1);

        RefreshTokenService.RotationResult result = service.rotate(raw, "9.9.9.9");

        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.newRawToken()).isNotBlank();
        assertThat(result.newRawToken()).isNotEqualTo(raw);
        verify(repository).revokeByTokenHash(eq(hash), any());
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTokenHash()).isEqualTo(sha256(result.newRawToken()));
        assertThat(captor.getValue().getIpAddress()).isEqualTo("9.9.9.9");
    }

    @Test
    void rotate_unknownTokenHash_throwsAuthRefreshTokenInvalid() {
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("garbage", "1.1.1.1"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        verify(repository, never()).save(any());
    }

    @Test
    void rotate_knownRevokedToken_revokesAllUserSessionsAndThrows() {
        String raw = UUID.randomUUID().toString();
        String hash = sha256(raw);
        UUID userId = UUID.randomUUID();
        RefreshToken revoked =
                RefreshToken.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .tokenHash(hash)
                        .expiresAt(OffsetDateTime.now().plusMinutes(30))
                        .revokedAt(OffsetDateTime.now().minusSeconds(10))
                        .build();
        when(repository.findByTokenHash(hash)).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> service.rotate(raw, "1.1.1.1"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        verify(repository).revokeAllActiveByUserId(eq(userId), any());
        verify(repository, never()).save(any());
    }

    @Test
    void rotate_knownRevokedToken_logsReplayWarning() {
        String raw = UUID.randomUUID().toString();
        String hash = sha256(raw);
        UUID userId = UUID.randomUUID();
        RefreshToken revoked =
                RefreshToken.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .tokenHash(hash)
                        .expiresAt(OffsetDateTime.now().plusMinutes(30))
                        .revokedAt(OffsetDateTime.now().minusSeconds(10))
                        .build();
        when(repository.findByTokenHash(hash)).thenReturn(Optional.of(revoked));

        Logger logger = (Logger) LoggerFactory.getLogger(RefreshTokenServiceImpl.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            assertThatThrownBy(() -> service.rotate(raw, "1.1.1.1"))
                    .isInstanceOf(AppException.class);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
        }

        assertThat(appender.list)
                .anyMatch(
                        event ->
                                event.getLevel() == Level.WARN
                                        && event.getFormattedMessage().contains(userId.toString()));
    }

    @Test
    void rotate_expiredToken_throwsAuthRefreshTokenExpired() {
        String raw = UUID.randomUUID().toString();
        String hash = sha256(raw);
        RefreshToken expired =
                RefreshToken.builder()
                        .id(UUID.randomUUID())
                        .userId(UUID.randomUUID())
                        .tokenHash(hash)
                        .expiresAt(OffsetDateTime.now().minusSeconds(1))
                        .build();
        when(repository.findByTokenHash(hash)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.rotate(raw, "1.1.1.1"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_REFRESH_TOKEN_EXPIRED);
        verify(repository).revokeByTokenHash(eq(hash), any());
        verify(repository, never()).save(any());
    }

    @Test
    void rotate_concurrentRevoke_revokesAllUserSessionsAndThrowsInvalid() {
        String raw = UUID.randomUUID().toString();
        String hash = sha256(raw);
        UUID userId = UUID.randomUUID();
        RefreshToken existing =
                RefreshToken.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .tokenHash(hash)
                        .expiresAt(OffsetDateTime.now().plusMinutes(30))
                        .build();
        when(repository.findByTokenHash(hash)).thenReturn(Optional.of(existing));
        // Simulate a concurrent rotation that already revoked the row.
        when(repository.revokeByTokenHash(eq(hash), any())).thenReturn(0);

        assertThatThrownBy(() -> service.rotate(raw, "1.1.1.1"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        verify(repository).revokeAllActiveByUserId(eq(userId), any());
        verify(repository, never()).save(any());
    }

    @Test
    void rotate_concurrentRevoke_logsReplayWarning() {
        String raw = UUID.randomUUID().toString();
        String hash = sha256(raw);
        UUID userId = UUID.randomUUID();
        RefreshToken existing =
                RefreshToken.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .tokenHash(hash)
                        .expiresAt(OffsetDateTime.now().plusMinutes(30))
                        .build();
        when(repository.findByTokenHash(hash)).thenReturn(Optional.of(existing));
        when(repository.revokeByTokenHash(eq(hash), any())).thenReturn(0);

        Logger logger = (Logger) LoggerFactory.getLogger(RefreshTokenServiceImpl.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            assertThatThrownBy(() -> service.rotate(raw, "1.1.1.1"))
                    .isInstanceOf(AppException.class);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
        }

        assertThat(appender.list)
                .anyMatch(
                        event ->
                                event.getLevel() == Level.WARN
                                        && event.getFormattedMessage().contains(userId.toString()));
    }

    @Test
    void revoke_callsRepositoryWithSha256Hash() {
        String raw = UUID.randomUUID().toString();

        service.revoke(raw);

        verify(repository).revokeByTokenHash(eq(sha256(raw)), any());
    }

    @Test
    void revoke_nullToken_isNoOp() {
        service.revoke(null);

        verify(repository, never()).revokeByTokenHash(any(), any());
    }

    @Test
    void revokeAllForUser_delegatesToRepositoryBulkRevoke() {
        UUID userId = UUID.randomUUID();

        service.revokeAllForUser(userId);

        verify(repository).revokeAllActiveByUserId(eq(userId), any());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
