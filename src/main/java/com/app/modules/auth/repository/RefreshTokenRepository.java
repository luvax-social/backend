package com.app.modules.auth.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.auth.entity.RefreshToken;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    interface RotationRetry {
        UUID getUserId();
        String getTokenHash();
    }

    /** Reads committed child state, including after losing a concurrent rotation CAS. */
    @Query(value = """
        SELECT child.user_id AS "userId", child.token_hash AS "tokenHash"
        FROM refresh_tokens parent JOIN refresh_tokens child ON child.id = parent.rotated_to_id
        WHERE parent.token_hash = :tokenHash AND parent.rotation_request_hash = :requestHash
          AND parent.revoked_at >= :retryAfter AND parent.revoked_at <= :now
          AND child.user_id = parent.user_id AND child.revoked_at IS NULL AND child.expires_at > :now
        """, nativeQuery = true)
    Optional<RotationRetry> findRotationRetry(@Param("tokenHash") String tokenHash,
            @Param("requestHash") String requestHash, @Param("retryAfter") OffsetDateTime retryAfter,
            @Param("now") OffsetDateTime now);

    /** Links the consumed parent and issued child in the same rotation transaction. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
        UPDATE refresh_tokens SET rotation_request_hash = :requestHash,
          rotated_to_id = (SELECT id FROM refresh_tokens WHERE token_hash = :childHash)
        WHERE token_hash = :tokenHash AND revoked_at IS NOT NULL AND rotation_request_hash IS NULL
        """, nativeQuery = true)
    int linkRotation(@Param("tokenHash") String tokenHash, @Param("requestHash") String requestHash,
            @Param("childHash") String childHash);

    /**
     * Revokes every active refresh token belonging to the given user.
     *
     * @param userId user whose tokens should be revoked
     * @param now revocation timestamp to record
     * @return number of sessions that were active and are now revoked
     */
    @Modifying
    @Query(
            "UPDATE RefreshToken r SET r.revokedAt = :now "
                    + "WHERE r.userId = :userId AND r.revokedAt IS NULL")
    int revokeAllActiveByUserId(@Param("userId") UUID userId, @Param("now") OffsetDateTime now);

    /**
     * Lists a user's live refresh tokens, newest first.
     *
     * <p>Live means neither revoked nor past expiry. Served by {@code idx_refresh_tokens_user}
     * (V15).
     *
     * @param userId owner whose sessions to list
     * @param now comparison instant for the expiry check
     * @param limit maximum rows to return
     * @return live refresh tokens ordered by issuance time descending
     */
    @Query(
            "SELECT r FROM RefreshToken r "
                    + "WHERE r.userId = :userId AND r.revokedAt IS NULL AND r.expiresAt > :now "
                    + "ORDER BY r.createdAt DESC, r.id DESC LIMIT :limit")
    List<RefreshToken> findActiveByUserId(
            @Param("userId") UUID userId,
            @Param("now") OffsetDateTime now,
            @Param("limit") int limit);

    /**
     * Revokes a single active refresh token by its hash and returns the row count affected.
     * Returning the count lets callers detect a concurrent rotation losing the race so that replay
     * can be flagged.
     *
     * @param tokenHash SHA-256 hash of the raw refresh token
     * @param now revocation timestamp to record
     * @return number of rows updated; {@code 0} when the token does not exist or was already
     *     revoked
     */
    @Modifying
    @Query(
            "UPDATE RefreshToken r SET r.revokedAt = :now "
                    + "WHERE r.tokenHash = :tokenHash AND r.revokedAt IS NULL")
    int revokeByTokenHash(@Param("tokenHash") String tokenHash, @Param("now") OffsetDateTime now);

    /**
     * Revokes one session, but only if it belongs to the named account.
     *
     * <p>The account id is part of the predicate rather than checked beforehand, so the ownership
     * test and the write cannot be separated by a concurrent change. Revoking by session id alone
     * would let a caller end any session in the system given only an identifier, and the session
     * listing hands those out.
     *
     * <p>Already-revoked rows are excluded, so a second call updates nothing and returns 0. That is
     * reported as success rather than as an error: a reviewer clicking twice has got what they
     * asked for.
     *
     * @param id refresh-token row identifying the session
     * @param userId account the session must belong to
     * @param now revocation timestamp
     * @return 1 when a live session was revoked, 0 when it was already revoked or not that
     *     account's
     */
    @Modifying
    @Query(
            value =
                    "UPDATE refresh_tokens SET revoked_at = :now"
                            + " WHERE id = :id AND user_id = :userId AND revoked_at IS NULL",
            nativeQuery = true)
    int revokeByIdForUser(
            @Param("id") UUID id, @Param("userId") UUID userId, @Param("now") OffsetDateTime now);

    /**
     * Reports whether a session row exists for the named account, whatever its revocation state.
     *
     * <p>Separates "not that account's session, or no such session" from "already revoked", so the
     * first can answer not-found and the second can answer success.
     *
     * @param id refresh-token row identifying the session
     * @param userId account the session must belong to
     * @return true when the row exists and belongs to that account
     */
    boolean existsByIdAndUserId(UUID id, UUID userId);

    /**
     * Deletes all refresh tokens that are either expired past the grace period or revoked past the
     * retention window.
     *
     * @param expiredBefore delete rows where {@code expires_at < expiredBefore}
     * @param revokedBefore delete rows where {@code revoked_at < revokedBefore}
     * @return number of rows deleted
     */
    @Modifying
    @Query(
            "DELETE FROM RefreshToken r "
                    + "WHERE r.expiresAt < :expiredBefore "
                    + "OR (r.revokedAt IS NOT NULL AND r.revokedAt < :revokedBefore)")
    int deleteExpiredAndRevoked(
            @Param("expiredBefore") OffsetDateTime expiredBefore,
            @Param("revokedBefore") OffsetDateTime revokedBefore);
}
