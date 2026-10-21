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
 * Lets the requester of an unaccepted conversation send one message and nothing more.
 *
 * <p>The message is either a text or a story reply, because a story reply to an account that allows
 * everyone starts a conversation and sends its one message in a single step, and refusing it would
 * end a flow that shipped before message requests. Media and post shares are refused.
 *
 * <p>A declined request answers exactly as a pending one, so the requester never learns that the
 * recipient declined. The recipient's own send passes, and the message module treats it as an
 * acceptance.
 */
@Component
class PendingRequestRule implements InteractionRule {

    static final String TEXT_MESSAGE_TYPE = "text";
    static final String STORY_REPLY_MESSAGE_TYPE = "story_share";

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
                || !isFirstMessageType(conversation.messageType())) {
            return Verdict.deny(InteractionReason.AWAITING_ACCEPTANCE);
        }
        return Verdict.pass();
    }

    private static boolean isFirstMessageType(String messageType) {
        return TEXT_MESSAGE_TYPE.equalsIgnoreCase(messageType)
                || STORY_REPLY_MESSAGE_TYPE.equalsIgnoreCase(messageType);
    }
}
