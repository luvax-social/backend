package com.app.modules.admin.service;

import java.util.UUID;

import com.app.common.exception.AppException;
import com.app.common.response.CursorPageResponse;
import com.app.modules.admin.dto.request.AdminDeleteHiddenWordRequest;
import com.app.modules.admin.dto.request.AdminHiddenWordRequest;
import com.app.modules.social.dto.response.AdminHiddenWordResponse;

/**
 * Administrative management of the platform-wide hidden word list.
 *
 * <p>Owns the administrator guard and the audit row; the social module owns the table. Every write
 * puts the {@code admin_actions} row and the list change in one transaction, so the log cannot
 * claim a change that rolled back or miss one that committed. The audit metadata is built from what
 * the transaction established - the phrase, its matching form and, for an edit, the values it
 * replaced - never from a client-supplied map.
 */
public interface AdminHiddenWordService {

    /**
     * Lists the platform-wide phrases newest first.
     *
     * @param actorId the acting administrator, checked against the source of truth
     * @param query text a phrase must contain once both are normalised, or null for every phrase
     * @param cursor opaque cursor from the prior page
     * @param limit requested page size
     * @return one page of entries
     * @throws AppException {@code FORBIDDEN} when the actor is not an administrator
     */
    CursorPageResponse<AdminHiddenWordResponse> list(
            UUID actorId, String query, String cursor, int limit);

    /**
     * Adds a phrase and records one {@code create_hidden_word} row.
     *
     * @param actorId the acting administrator
     * @param request the phrase and the justification
     * @return the stored entry
     * @throws AppException with {@code HIDDEN_WORD_INVALID} or {@code HIDDEN_WORD_DUPLICATE}
     */
    AdminHiddenWordResponse create(UUID actorId, AdminHiddenWordRequest request);

    /**
     * Replaces an entry's phrase and records one {@code edit_hidden_word} row carrying the previous
     * phrase.
     *
     * @param actorId the acting administrator
     * @param hiddenWordId the entry to edit
     * @param request the new phrase and the justification
     * @return the entry after the edit
     * @throws AppException with {@code NOT_FOUND}, {@code HIDDEN_WORD_INVALID} or {@code
     *     HIDDEN_WORD_DUPLICATE}
     */
    AdminHiddenWordResponse update(UUID actorId, UUID hiddenWordId, AdminHiddenWordRequest request);

    /**
     * Removes an entry and records one {@code delete_hidden_word} row holding the phrase it had.
     *
     * @param actorId the acting administrator
     * @param hiddenWordId the entry to remove
     * @param request the justification
     * @throws AppException with {@code NOT_FOUND} when no such entry exists
     */
    void delete(UUID actorId, UUID hiddenWordId, AdminDeleteHiddenWordRequest request);
}
