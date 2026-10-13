package com.app.modules.social.policy;

/**
 * The conversation a {@link InteractionType#MESSAGE_SEND} goes into; absent for every other type.
 *
 * @param state the conversation's request state
 * @param actorIsRequester whether the sender started the request, rather than received it
 * @param actorMessagesSoFar messages the sender has already sent into the conversation
 * @param messageType the message type in its database form, such as {@code text} or {@code image}
 */
public record ConversationContext(
        ConversationRequestState state,
        boolean actorIsRequester,
        long actorMessagesSoFar,
        String messageType) {}
