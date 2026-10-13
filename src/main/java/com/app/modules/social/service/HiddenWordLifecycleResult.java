package com.app.modules.social.service;

import java.util.UUID;

import com.app.modules.social.dto.response.AdminHiddenWordResponse;

/**
 * Outcome of one change to the platform-wide hidden word list.
 *
 * <p>Carries what the administrative audit row records, so the administrator module describes the
 * change from facts the social module established inside the transaction rather than from the
 * request. {@code previousPhrase} and {@code previousNormalizedPhrase} are set only by an edit.
 *
 * @param id the entry that was created, edited or removed
 * @param phrase the phrase after the change, or as it stood when it was removed
 * @param normalizedPhrase the matching form of {@code phrase}
 * @param previousPhrase the phrase before an edit, or null for any other change
 * @param previousNormalizedPhrase the matching form before an edit, or null for any other change
 * @param word the entry as the registry shows it
 */
public record HiddenWordLifecycleResult(
        UUID id,
        String phrase,
        String normalizedPhrase,
        String previousPhrase,
        String previousNormalizedPhrase,
        AdminHiddenWordResponse word) {}
