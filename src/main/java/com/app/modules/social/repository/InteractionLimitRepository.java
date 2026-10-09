package com.app.modules.social.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.social.entity.InteractionLimit;

@Repository
public interface InteractionLimitRepository extends JpaRepository<InteractionLimit, UUID> {

    /**
     * Writes the account's limit, replacing the one it already has.
     *
     * <p>One statement rather than read-then-write, so two requests racing on an account that has
     * no limit yet cannot collide on the primary key. {@code updated_at} is left to its trigger,
     * and the persistence context is cleared because a cached entity would otherwise hide the new
     * values.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value =
                    "INSERT INTO interaction_limits (user_id, limit_non_followers,"
                            + " limit_recent_followers, expires_at)"
                            + " VALUES (:userId, :limitNonFollowers, :limitRecentFollowers,"
                            + " :expiresAt)"
                            + " ON CONFLICT (user_id) DO UPDATE SET"
                            + " limit_non_followers = EXCLUDED.limit_non_followers,"
                            + " limit_recent_followers = EXCLUDED.limit_recent_followers,"
                            + " expires_at = EXCLUDED.expires_at",
            nativeQuery = true)
    int upsert(
            @Param("userId") UUID userId,
            @Param("limitNonFollowers") boolean limitNonFollowers,
            @Param("limitRecentFollowers") boolean limitRecentFollowers,
            @Param("expiresAt") OffsetDateTime expiresAt);

    /**
     * Removes the account's limit.
     *
     * @return the number of rows removed, zero when the account had none
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from InteractionLimit l where l.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
