package com.app.modules.social.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /** Returns whether an entry with this matching form exists. */
    boolean existsByNormalizedPhrase(String normalizedPhrase);

    /** Returns whether an entry other than {@code id} has this matching form. */
    boolean existsByNormalizedPhraseAndIdNot(String normalizedPhrase, UUID id);

    /**
     * Returns the first page of entries whose matching form fits a LIKE pattern, newest first.
     *
     * <p>Keyset ordering is {@code (created_at DESC, id DESC)}. The pattern uses {@code !} as its
     * escape character, so callers escape {@code !}, {@code %} and {@code _} with it, and pass
     * {@code %} alone to select every entry. The page size travels in {@code page}.
     */
    @Query(
            "select h from HiddenWord h where h.normalizedPhrase like :pattern escape '!'"
                    + " order by h.createdAt desc, h.id desc")
    List<HiddenWord> findAdminPage(@Param("pattern") String pattern, Pageable page);

    /**
     * Returns the entries after a keyset position whose matching form fits a LIKE pattern, newest
     * first.
     *
     * <p>Same ordering and pattern rules as {@link #findAdminPage}; the position is exclusive.
     */
    @Query(
            "select h from HiddenWord h where h.normalizedPhrase like :pattern escape '!'"
                    + " and (h.createdAt < :cursorCreatedAt"
                    + " or (h.createdAt = :cursorCreatedAt and h.id < :cursorId))"
                    + " order by h.createdAt desc, h.id desc")
    List<HiddenWord> findAdminPageAfter(
            @Param("pattern") String pattern,
            @Param("cursorCreatedAt") OffsetDateTime cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable page);
}
