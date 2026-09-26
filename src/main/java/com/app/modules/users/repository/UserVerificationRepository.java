package com.app.modules.users.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.users.entity.UserVerification;

@Repository
public interface UserVerificationRepository extends JpaRepository<UserVerification, UUID> {

    /**
     * The account's current grant, if it holds one.
     *
     * <p>Served by the partial unique index {@code uq_user_verifications_active}, which also
     * guarantees at most one row can match.
     *
     * @param userId the account
     * @return the active grant, or empty when the account holds no badge
     */
    @Query("SELECT v FROM UserVerification v WHERE v.userId = :userId AND v.revokedAt IS NULL")
    Optional<UserVerification> findActive(@Param("userId") UUID userId);

    /**
     * Every grant this account has ever held, newest first.
     *
     * <p>The resubmission read. A moderator deciding a new request sees what was granted before,
     * when it was withdrawn, and whether a person or a status change withdrew it.
     *
     * @param userId the account
     * @return grants ordered newest first, including revoked ones
     */
    @Query("SELECT v FROM UserVerification v WHERE v.userId = :userId ORDER BY v.grantedAt DESC")
    List<UserVerification> findHistory(@Param("userId") UUID userId);
}
