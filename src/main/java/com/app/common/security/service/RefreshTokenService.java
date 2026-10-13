package com.app.common.security.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Issuance, rotation, and revocation of opaque refresh tokens.
 *
 * <p>Refresh tokens are opaque 256-bit values. Only their SHA-256 hashes are persisted.
 */
public interface RefreshTokenService {

    /**
     * Issues a new refresh token bound to the given user and device metadata.
     *
     * @param userId owner of the token
     * @param deviceId optional opaque device identifier
     * @param userAgent optional user agent string
     * @param ipAddress optional source IP address
     * @return the raw refresh token to send to the client
     */
    String issue(UUID userId, String deviceId, String userAgent, String ipAddress);

    /**
     * Atomically revokes the supplied raw token and issues a fresh one for the same user.
     *
     * @param rawToken raw refresh token presented by the client
     * @param ipAddress source IP of the rotation request
     * @return the rotation result containing the new raw token and its owning user id
     * @throws com.app.common.exception.AppException with {@code AUTH_REFRESH_TOKEN_INVALID} when
     *     the token does not exist or is already revoked, or {@code AUTH_REFRESH_TOKEN_EXPIRED}
     *     when the token has passed its expiry
     */
    RotationResult rotate(String rawToken, String ipAddress);

    /**
     * Rotates, or recovers the original active child within sixty seconds for the exact request.
     *
     * @param rawToken refresh credential presented by the client
     * @param ipAddress source IP of the request
     * @param requestKey random UUID v4 retained while the client awaits the response
     * @return the newly issued or recovered child token and its owning user
     */
    RotationResult rotate(String rawToken, String ipAddress, String requestKey);

    /**
     * Revokes the supplied raw token. No-op when the token does not exist, allowing logout to be
     * idempotent.
     *
     * @param rawToken raw refresh token to revoke
     */
    void revoke(String rawToken);

    /**
     * Revokes every active refresh token belonging to the given user.
     *
     * <p>Terminates refresh capability immediately. It does not invalidate an access token already
     * in the user's hands: the blacklist is keyed on {@code jti} and no caller other than the token
     * holder knows it, so access capability survives for up to the remaining access-token lifetime.
     *
     * @param userId owner whose sessions should be terminated
     * @return number of sessions that were active and are now revoked
     */
    int revokeAllForUser(UUID userId);

    /**
     * Revokes one named session belonging to one account.
     *
     * <p>Idempotent: revoking a session that is already revoked or expired is a no-op that reports
     * success, because a reviewer clicking twice has still got what they asked for.
     *
     * @param userId account the session must belong to
     * @param sessionId refresh-token row identifying the session
     * @return true when this call revoked a live session, false when it was already revoked
     * @throws com.app.common.exception.AppException with {@code NOT_FOUND} when no such session
     *     belongs to that account
     */
    boolean revokeSessionForUser(UUID userId, UUID sessionId);

    /**
     * Resolves the session a raw refresh token belongs to.
     *
     * <p>Serves the caller's own "which of these rows is me" question. The session identifier is
     * not derivable from an access token: its {@code jti} is unique per access token and carries no
     * link to the refresh-token row, and the refresh cookie is scoped to the auth path so it never
     * reaches the administrative tree.
     *
     * @param rawToken the raw refresh token presented by the caller
     * @return the session identifier, or empty when the token is unknown, revoked or expired
     */
    java.util.Optional<UUID> findSessionIdByRawToken(String rawToken);

    /**
     * Lists the user's live sessions, newest first.
     *
     * <p>A session is live when its refresh token is neither revoked nor past its expiry. The raw
     * token is never returned; only the row identifier and the device metadata recorded at
     * issuance.
     *
     * @param userId owner whose sessions to list
     * @param limit maximum sessions to return
     * @return live sessions ordered by issuance time descending
     */
    List<ActiveSession> listActiveSessions(UUID userId, int limit);

    record RotationResult(String newRawToken, UUID userId) {}

    /**
     * One live session, without the token value.
     *
     * @param id refresh-token row identifier
     * @param deviceId opaque device identifier supplied at issuance, or null
     * @param userAgent user agent recorded at issuance, or null
     * @param ipAddress client IP recorded at issuance, or null
     * @param createdAt issuance timestamp
     * @param expiresAt expiry timestamp
     */
    record ActiveSession(
            UUID id,
            String deviceId,
            String userAgent,
            String ipAddress,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt) {}
}
