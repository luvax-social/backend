package com.app.modules.comment.config;

import java.util.List;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start while the retired {@code app.comment.blocked-words} list is still configured.
 *
 * <p>Comment moderation used to reject any comment containing a configured word. That list is gone:
 * words are now managed in the administrator Hidden Words registry, which hides a matching comment
 * from the account it is aimed at instead of rejecting it from its author. The property would
 * otherwise be ignored without a trace, and an operator who still relied on it would believe a
 * filter was active that no longer is. Failing at startup, with the replacement named, is the only
 * way the change cannot go unnoticed.
 */
@Component
public class LegacyBlockedWordsGuard {

    static final String RETIRED_PROPERTY = "app.comment.blocked-words";

    public LegacyBlockedWordsGuard(Environment environment) {
        List<String> configured =
                Binder.get(environment)
                        .bind(RETIRED_PROPERTY, Bindable.listOf(String.class))
                        .orElse(List.of());
        if (configured.stream().anyMatch(word -> !word.isBlank())) {
            throw new IllegalStateException(
                    RETIRED_PROPERTY
                            + " is no longer supported. Remove it and add the words to the"
                            + " platform-wide Hidden Words registry instead"
                            + " (POST /api/v1/admin/hidden-words, administrators only).");
        }
    }
}
