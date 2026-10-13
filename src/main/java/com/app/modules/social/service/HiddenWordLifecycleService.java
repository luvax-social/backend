package com.app.modules.social.service;

import java.util.UUID;

import com.app.common.exception.AppException;
import com.app.common.response.CursorPageResponse;
import com.app.modules.social.dto.response.AdminHiddenWordResponse;

/**
 * Owns every write to the platform-wide {@code hidden_words} table.
 *
 * <p>The administrator module owns the guard and the audit row and calls this service for the
 * change itself, the same division the hashtag registry follows. Each write publishes {@code
 * HiddenWordsChangedEvent} in its own transaction, so the in-memory copy of the list reloads only
 * after the change commits and never for one that rolled back.
 */
public interface HiddenWordLifecycleService {

    /**
     * Lists the platform-wide phrases newest first.
     *
     * @param query text a phrase must contain once both are normalised, or null or blank for every
     *     phrase
     * @param cursor opaque cursor from the prior page
     * @param limit requested page size
     * @return one page of entries
     */
    CursorPageResponse<AdminHiddenWordResponse> list(String query, String cursor, int limit);

    /**
     * Adds a phrase to the platform-wide list.
     *
     * @param actorId the administrator adding it, kept as the creator
     * @param phrase the raw phrase
     * @return the stored entry
     * @throws AppException with {@code HIDDEN_WORD_INVALID} when the phrase breaks the personal
     *     list's bounds, or {@code HIDDEN_WORD_DUPLICATE} when an entry with the same normalised
     *     form exists
     */
    HiddenWordLifecycleResult create(UUID actorId, String phrase);

    /**
     * Replaces the phrase of an entry.
     *
     * <p>Re-typing an entry with different case or accents is allowed, because its own normalised
     * form is not a duplicate of itself.
     *
     * @param id the entry to edit
     * @param phrase the new raw phrase
     * @return the entry after the edit, with the values it replaced
     * @throws AppException with {@code NOT_FOUND}, {@code HIDDEN_WORD_INVALID}, or {@code
     *     HIDDEN_WORD_DUPLICATE} when another entry already has the new normalised form
     */
    HiddenWordLifecycleResult update(UUID id, String phrase);

    /**
     * Removes an entry from the list.
     *
     * <p>A hard delete: the row is configuration rather than user content, and the audit metadata
     * the administrator module writes is what keeps the phrase.
     *
     * @param id the entry to remove
     * @return the entry as it stood before removal
     * @throws AppException with {@code NOT_FOUND} when no such entry exists
     */
    HiddenWordLifecycleResult delete(UUID id);
}
