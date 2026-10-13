package com.app.modules.social.validation;

import java.util.List;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.text.TextMatchNormalizer;
import com.app.modules.social.service.HiddenWordService;

/**
 * Validation shared by every way a hidden word phrase enters the system.
 *
 * <p>A personal entry and a platform-wide entry obey the same bounds, so one implementation decides
 * them. Every rejection answers {@code HIDDEN_WORD_INVALID}, a missing phrase included, so a client
 * handles a single error code.
 */
public final class HiddenWordPhrases {

    private HiddenWordPhrases() {}

    /**
     * Validates a raw phrase and returns its matching form.
     *
     * <p>Length is counted by code point on the raw text, and the phrase may carry surrounding
     * whitespace that the caller strips before storing. A control character anywhere inside is
     * refused: PostgreSQL cannot store NUL in text at all, and a tab or line break inside a phrase
     * is never something a person meant to filter on.
     *
     * @param phrase the raw phrase as typed
     * @return the normalised tokens joined by single spaces
     * @throws AppException with {@code HIDDEN_WORD_INVALID} when the phrase is missing, outside the
     *     length or word-count bounds, contains a control character, or normalises to no words
     */
    public static String normalizeOrReject(String phrase) {
        if (phrase == null) {
            throw new AppException(ApiErrorCode.HIDDEN_WORD_INVALID);
        }
        int characters = phrase.codePointCount(0, phrase.length());
        if (characters < 1 || characters > HiddenWordService.MAX_PHRASE_CHARACTERS) {
            throw new AppException(ApiErrorCode.HIDDEN_WORD_INVALID);
        }
        if (containsControlCharacter(phrase.strip())) {
            throw new AppException(ApiErrorCode.HIDDEN_WORD_INVALID);
        }
        List<String> tokens = TextMatchNormalizer.tokens(phrase);
        if (tokens.isEmpty() || tokens.size() > HiddenWordService.MAX_PHRASE_TOKENS) {
            throw new AppException(ApiErrorCode.HIDDEN_WORD_INVALID);
        }
        return String.join(" ", tokens);
    }

    private static boolean containsControlCharacter(String text) {
        return text.codePoints().anyMatch(Character::isISOControl);
    }
}
