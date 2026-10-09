package com.app.modules.social.service;

import java.util.UUID;

import com.app.common.text.PhraseMatcher;
import com.app.modules.social.dto.response.HiddenWordEntryResponse;
import com.app.modules.social.dto.response.HiddenWordListResponse;

/**
 * Personal hidden words lists and the matcher that combines them with the platform-wide list.
 *
 * <p>Every phrase is stored beside its normalised form, and matching only ever sees the normalised
 * form, so the contract in the "Text match normalisation" section of {@code
 * docs/modules/GLOBAL_RULES.md} holds for both lists.
 */
public interface HiddenWordService {

    /** Most entries one account may keep on its personal list. */
    int PERSONAL_LIMIT = 200;

    /** Most characters, counted by code point, a raw phrase may have. */
    int MAX_PHRASE_CHARACTERS = 64;

    /** Most words a phrase may normalise to. */
    int MAX_PHRASE_TOKENS = 6;

    /**
     * Adds a phrase to the account's personal list.
     *
     * @param ownerId the account that owns the list
     * @param phrase the raw phrase as the account typed it
     * @return the stored entry
     * @throws com.app.common.exception.AppException with {@code HIDDEN_WORD_INVALID} when the
     *     phrase is outside the length or word-count bounds, {@code HIDDEN_WORD_LIMIT_REACHED} when
     *     the list already holds {@link #PERSONAL_LIMIT} entries, or {@code HIDDEN_WORD_DUPLICATE}
     *     when an entry with the same normalised form exists
     */
    HiddenWordEntryResponse addPersonal(UUID ownerId, String phrase);

    /**
     * Removes one entry from the account's personal list.
     *
     * @param ownerId the account that owns the list
     * @param entryId the entry to remove
     * @throws com.app.common.exception.AppException with {@code NOT_FOUND} when the entry does not
     *     exist or belongs to another account
     */
    void removePersonal(UUID ownerId, UUID entryId);

    /**
     * Lists the account's personal entries, newest first.
     *
     * @param ownerId the account that owns the list
     */
    HiddenWordListResponse listPersonal(UUID ownerId);

    /**
     * Builds the matcher for content directed at an account.
     *
     * <p>It always holds the account's personal entries and adds the platform-wide list unless the
     * account turned that off. It costs one query for the personal entries and one settings lookup,
     * however long either list is.
     *
     * @param ownerId the account whose lists apply
     */
    PhraseMatcher matcherFor(UUID ownerId);

    /**
     * Reloads the in-memory copy of the platform-wide list from the database.
     *
     * <p>The administrative change path triggers it through {@code HiddenWordsChangedEvent}; tests
     * and operators may call it directly.
     */
    void refreshSystemList();
}
