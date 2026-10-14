package com.app.modules.message.service;

import java.util.UUID;

import com.app.modules.message.dto.response.MessageReactionResponse;

/** One emoji reaction per user per message: set, change and remove. */
public interface MessageReactionService {

    /**
     * Sets or changes the caller's reaction to a message.
     *
     * <p>Reacting again with the same emoji changes nothing and publishes nothing. A change
     * publishes {@code message.reaction.changed.v1} in the same transaction. Reactions are not
     * messages, so a participant of a pending message request may react.
     *
     * @param actorId the authenticated caller, who must be an active participant
     * @param conversationId the conversation the message belongs to
     * @param messageId the message to react to
     * @param emoji exactly one emoji grapheme
     * @return the caller's reaction as stored
     * @throws com.app.common.exception.AppException CONVERSATION_NOT_FOUND when the conversation is
     *     missing or a block exists between the participants, which is stealthed as not-found;
     *     CONVERSATION_FORBIDDEN when the caller is not an active participant; MESSAGE_NOT_FOUND
     *     when the message is missing or carries either tombstone; MESSAGE_REACTION_INVALID_EMOJI
     *     when the text is not exactly one emoji
     */
    MessageReactionResponse react(UUID actorId, UUID conversationId, UUID messageId, String emoji);

    /**
     * Removes the caller's reaction to a message.
     *
     * <p>Idempotent: removing a reaction that does not exist, or one on a message that is no longer
     * shown, succeeds without effect.
     *
     * @param actorId the authenticated caller, who must be an active participant
     * @param conversationId the conversation the message belongs to
     * @param messageId the message
     * @throws com.app.common.exception.AppException CONVERSATION_NOT_FOUND when the conversation is
     *     missing or a block exists between the participants; CONVERSATION_FORBIDDEN when the
     *     caller is not an active participant; MESSAGE_NOT_FOUND when no such message exists in the
     *     conversation
     */
    void unreact(UUID actorId, UUID conversationId, UUID messageId);
}
