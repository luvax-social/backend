package com.app.modules.social.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.social.entity.AccountShowFewer;
import com.app.modules.social.entity.AccountShowFewerId;

@Repository
public interface AccountShowFewerRepository
        extends JpaRepository<AccountShowFewer, AccountShowFewerId> {

    /**
     * Records a show-fewer request unless the pair already has one.
     *
     * <p>One statement rather than exists-then-save, so concurrent requests for the same pair
     * cannot collide on the primary key and exactly one of them reports an insert.
     *
     * @param userId the account asking to see less
     * @param authorId the author it wants to see less of
     * @return 1 when a row was inserted, 0 when the pair already had one
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value =
                    "INSERT INTO account_show_fewer (user_id, author_id)"
                            + " VALUES (:userId, :authorId) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId, @Param("authorId") UUID authorId);

    /**
     * Removes a show-fewer request.
     *
     * @param userId the account that asked to see less
     * @param authorId the author it asked to see less of
     * @return number of rows deleted (0 or 1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "DELETE FROM AccountShowFewer a"
                    + " WHERE a.id.userId = :userId AND a.id.authorId = :authorId")
    int deleteByUserAndAuthor(@Param("userId") UUID userId, @Param("authorId") UUID authorId);

    /**
     * Removes every show-fewer request between two accounts, in both directions.
     *
     * <p>A block supersedes the preference, so blocking either way clears both accounts' requests
     * about each other.
     *
     * @param firstUserId one account of the pair
     * @param secondUserId the other account of the pair
     * @return number of rows deleted
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "DELETE FROM AccountShowFewer a WHERE (a.id.userId = :firstUserId AND a.id.authorId ="
                    + " :secondUserId) OR (a.id.userId = :secondUserId AND a.id.authorId ="
                    + " :firstUserId)")
    int deleteBetween(
            @Param("firstUserId") UUID firstUserId, @Param("secondUserId") UUID secondUserId);
}
