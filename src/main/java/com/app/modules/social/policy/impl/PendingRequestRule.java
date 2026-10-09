package com.app.modules.social.policy.impl;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.app.modules.social.policy.ConversationContext;
import com.app.modules.social.policy.ConversationRequestState;
import com.app.modules.social.policy.InteractionReason;
import com.app.modules.social.policy.InteractionRequest;
import com.app.modules.social.policy.InteractionType;

/**
 * Lets the requester of an unaccepted conversation send one text message and nothing more.
 *
 * <p>A declined request answers exactly as a pending one, so the requester never learns that the
 * recipient declined. The recipient's own send passes, and the message module treats it as an
 * acceptance.
 */
@Component
class PendingRequestRule implements InteractionRule {

    static final String TEXT_MESSAGE_TYPE = "text";

    private static final Set<InteractionType> HANDLES = EnumSet.of(InteractionType.MESSAGE_SEND);

    @Override
    public Set<InteractionType> handles() {
        return HANDLES;
    }

    @Override
    public Verdict apply(InteractionRequest request, EvaluationContext context) {
        ConversationContext conversation = request.conversation();
        if (conversation.state() == ConversationRequestState.ACCEPTED
                || !conversation.actorIsRequester()) {
            return Verdict.pass();
        }
        if (conversation.state() == ConversationRequestState.DECLINED
                || conversation.actorMessagesSoFar() >= 1
                || !TEXT_MESSAGE_TYPE.equalsIgnoreCase(conversation.messageType())) {
            return Verdict.deny(InteractionReason.AWAITING_ACCEPTANCE);
        }
        return Verdict.pass();
    }
}
