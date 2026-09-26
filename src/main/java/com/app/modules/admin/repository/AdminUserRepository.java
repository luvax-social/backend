package com.app.modules.admin.repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.app.modules.users.entity.User;

/**
 * Administrative access to {@code users}, kept separate from {@code UserRepository} so the
 * soft-delete inversion documented on {@link AdminUserRepositoryCustom} cannot leak into an
 * ordinary read path. Only the admin module injects this interface.
 */
@org.springframework.stereotype.Repository
public interface AdminUserRepository extends Repository<User, UUID>, AdminUserRepositoryCustom {

    /**
     * Returns a lapsed fixed-term suspension to active, if it is still lapsed and still suspended.
     *
     * <p>Takes no lock. Every component of the predicate is checked in the statement itself, so two
     * concurrent callers cannot both apply it: whichever commits first changes {@code status},
     * which invalidates the predicate for the other. A zero row count therefore means the work was
     * already done, not that it failed, and both callers must treat it as success.
     *
     * <p>The cutoff is a bound parameter rather than the database's {@code now()}, because {@code
     * suspended_until} is written from the JVM clock. Comparing a JVM-written value against the
     * database clock puts one column in two clock domains: with the database behind, a suspension
     * the service has already decided is over is not yet expired here.
     *
     * @param userId account to reinstate
     * @param now the cutoff, from the same clock that wrote {@code suspended_until}
     * @return {@code 1} when this call performed the reinstatement, {@code 0} when it did not apply
     */
    @Modifying
    @Query(
            value =
                    "UPDATE users"
                            + " SET status = 'active', suspended_until = NULL"
                            + " WHERE id = :userId"
                            + " AND status = 'suspended'"
                            + " AND suspended_until IS NOT NULL"
                            + " AND suspended_until <= :now",
            nativeQuery = true)
    int reinstateExpiredSuspension(@Param("userId") UUID userId, @Param("now") OffsetDateTime now);

    /**
     * Reads the current status of one account, spanning soft-deleted rows.
     *
     * <p>Native and scalar on purpose: it must not be answered from the persistence context,
     * because the reinstatement above is an SQL update that the context does not see. Reading the
     * row through the entity would return the pre-update status.
     *
     * @param userId account to read
     * @return the {@code user_status} value as its lowercase wire form, or null when no row holds
     *     that id
     */
    @Query(value = "SELECT u.status::text FROM users u WHERE u.id = :userId", nativeQuery = true)
    String findStatusIncludingDeleted(@Param("userId") UUID userId);

    /**
     * Advances the account's token epoch, invalidating every access token already issued to it.
     *
     * <p>The sole writer of {@code users.token_epoch}. The increment is computed by the database in
     * the statement itself rather than read into the application and written back, so two
     * administrators acting at the same moment cannot lose one of the two increments and leave a
     * token minted between their reads still valid.
     *
     * @param userId account whose access tokens are being invalidated
     * @return {@code 1} when a live row was advanced, {@code 0} when no live row holds that id
     */
    @Modifying
    @Query(
            value =
                    "UPDATE users SET token_epoch = token_epoch + 1"
                            + " WHERE id = :userId AND deleted_at IS NULL",
            nativeQuery = true)
    int incrementTokenEpoch(@Param("userId") UUID userId);

    /**
     * Loads a live account and holds its row until the transaction ends.
     *
     * <p>Used by the discipline path only. Two moderators warning the same account at the same
     * moment would otherwise both read the same active-warning count and both conclude the account
     * had reached three, issuing one strike each. The unique index on the active strike number
     * would reject the second, but by failing its whole transaction and taking a legitimate warning
     * down with it. Serializing on this row makes the later warning read the earlier one's strike
     * and correctly issue none.
     *
     * <p>Not on the reinstatement path, which is deliberately lock-free: its predicate is checked
     * in the statement itself, so it needs no lock to be correct.
     *
     * @param userId account to lock
     * @return the account, or empty when no live row holds that id
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :userId AND u.deletedAt IS NULL")
    Optional<User> lockForDiscipline(@Param("userId") UUID userId);

    User save(User user);
}
