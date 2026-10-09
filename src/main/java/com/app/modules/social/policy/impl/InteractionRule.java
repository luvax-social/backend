package com.app.modules.social.policy.impl;

import java.util.Set;

import com.app.modules.social.policy.ConversationContext;
import com.app.modules.social.policy.ConversationRequestState;
import com.app.modules.social.policy.InteractionRequest;
import com.app.modules.social.policy.InteractionType;

/**
 * One link of the interaction policy chain.
 *
 * <p>A rule is consulted only for the types in {@link #handles()}, so a reserved type is enabled by
 * adding it to the sets of the rules that should govern it.
 */
interface InteractionRule {

    Set<InteractionType> handles();

    Verdict apply(InteractionRequest request, EvaluationContext context);

    /** A requester's send into a conversation the recipient has not accepted yet. */
    static boolean isRequesterSendIntoPending(InteractionRequest request) {
        ConversationContext conversation = request.conversation();
        return request.type() == InteractionType.MESSAGE_SEND
                && conversation != null
                && conversation.actorIsRequester()
                && (conversation.state() == ConversationRequestState.PENDING
                        || conversation.state() == ConversationRequestState.PENDING_HIDDEN);
    }
}
