package com.app.modules.social.policy.impl;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.app.common.text.TextMatchNormalizer;
import com.app.modules.social.policy.InteractionReason;
import com.app.modules.social.policy.InteractionRequest;
import com.app.modules.social.policy.InteractionType;
import com.app.modules.social.service.HiddenWordService;

/**
 * Hides a comment, or a requester's first message, whose text matches the target's hidden words.
 *
 * <p>Messages in an accepted conversation are never checked: the recipient already chose to talk to
 * the sender.
 */
@Component
class HiddenWordsRule implements InteractionRule {

    private static final Set<InteractionType> HANDLES =
            EnumSet.of(InteractionType.COMMENT, InteractionType.MESSAGE_SEND);

    private final HiddenWordService hiddenWordService;

    HiddenWordsRule(HiddenWordService hiddenWordService) {
        this.hiddenWordService = hiddenWordService;
    }

    @Override
    public Set<InteractionType> handles() {
        return HANDLES;
    }

    @Override
    public Verdict apply(InteractionRequest request, EvaluationContext context) {
        if (request.type() == InteractionType.MESSAGE_SEND
                && !InteractionRule.isRequesterSendIntoPending(request)) {
            return Verdict.pass();
        }
        String content = request.content();
        if (content == null || content.isBlank()) {
            return Verdict.pass();
        }
        boolean matches =
                hiddenWordService
                        .matcherFor(request.targetUserId())
                        .matches(TextMatchNormalizer.tokens(content));
        return matches ? Verdict.hide(InteractionReason.HIDDEN_WORD) : Verdict.pass();
    }
}
