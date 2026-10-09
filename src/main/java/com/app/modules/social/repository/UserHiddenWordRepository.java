package com.app.modules.social.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.social.entity.UserHiddenWord;

@Repository
public interface UserHiddenWordRepository extends JpaRepository<UserHiddenWord, UUID> {

    long countByUserId(UUID userId);

    boolean existsByUserIdAndNormalizedPhrase(UUID userId, String normalizedPhrase);

    List<UserHiddenWord> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId);

    /**
     * Returns only the matching form of every entry on one account's list.
     *
     * <p>A scalar projection keeps the matcher build to a single query that loads no entities.
     */
    @Query("select w.normalizedPhrase from UserHiddenWord w where w.userId = :userId")
    List<String> findNormalizedPhrasesByUserId(@Param("userId") UUID userId);

    /**
     * Deletes one entry only when it belongs to the given account.
     *
     * @return 1 when the entry was removed, 0 when it does not exist or belongs to another account
     */
    @Modifying
    @Query("delete from UserHiddenWord w where w.id = :id and w.userId = :userId")
    int deleteByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    /**
     * Takes a transaction-scoped advisory lock on one account's personal list.
     *
     * <p>The entry limit is a count taken before an insert, so two concurrent inserts at 199
     * entries would both pass it. Holding this lock until commit serialises the count and the
     * insert per account without locking any row.
     *
     * @return the empty text the lock function produces, which callers ignore
     */
    @Query(
            value =
                    "SELECT CAST(pg_advisory_xact_lock(hashtextextended('user_hidden_words:'"
                            + " || CAST(:userId AS text), 0)) AS text)",
            nativeQuery = true)
    String lockPersonalList(@Param("userId") UUID userId);
}
