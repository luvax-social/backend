package com.app.modules.social.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.app.modules.social.entity.HiddenWord;

@Repository
public interface HiddenWordRepository extends JpaRepository<HiddenWord, UUID> {

    /**
     * Returns the matching form of every entry on the platform-wide list.
     *
     * <p>A scalar projection reads straight from the table, never from the persistence context, so
     * a rebuild that runs right after a commit cannot see a stale entity.
     */
    @Query("select h.normalizedPhrase from HiddenWord h")
    List<String> findAllNormalizedPhrases();
}
