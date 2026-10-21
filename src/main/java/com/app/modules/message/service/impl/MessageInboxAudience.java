package com.app.modules.message.service.impl;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.app.modules.message.entity.Conversation;
import com.app.modules.message.enums.ConversationRequestStatus;
import com.app.modules.message.repository.ConversationParticipantRepository;
import com.app.modules.social.service.SocialService;

/**
 * Decides which accounts' inbox topics a conversation event is pushed to, and which folder the
 * conversation sits in for them.
 *
 * <p>The audience is decided by the producer, inside the transaction that records the change,
 * instead of by the live consumer afterwards. The consumer then needs no database access, and a
 * decline or block committed after the event cannot widen who it reaches. An account is left out
 * when it is the actor, has left the conversation, is the recipient of a declined request, or is
 * blocked by or blocking the actor, so the inbox topic never tells a requester about a decline and
 * never connects a blocked pair.
 */
@Component
public class MessageInboxAudience {

    private final ConversationParticipantRepository participantRepository;
    private final SocialService socialService;

    public MessageInboxAudience(
            ConversationParticipantRepository participantRepository, SocialService socialService) {
        this.participantRepository = participantRepository;
        this.socialService = socialService;
    }

    /**
     * The accounts whose inbox topic should hear about an event the actor caused.
     *
     * @param conversation the conversation as it stands after the change
     * @param actorId the account that caused the event, never part of the result
     * @return the other active participants who may be told, in join order
     */
    public List<UUID> recipients(Conversation conversation, UUID actorId) {
        boolean declined = conversation.getRequestStatus() == ConversationRequestStatus.DECLINED;
        return participantRepository
                .findByIdConversationIdOrderByJoinedAtAsc(conversation.getId())
                .stream()
                .filter(participant -> participant.getLeftAt() == null)
                .map(participant -> participant.getId().getUserId())
                .filter(userId -> !userId.equals(actorId))
                .filter(
                        userId ->
                                !(declined && userId.equals(conversation.getRequestRecipientId())))
                .filter(userId -> !socialService.isBlockedBetween(actorId, userId))
                .toList();
    }

    /**
     * The accounts whose inbox topic should hear about a reaction the actor made.
     *
     * <p>Reacting is not a message, so it is not subject to the one-message rule of a pending
     * request, and a requester could otherwise react and unreact in a loop to keep pinging the
     * recipient. While the conversation is not accepted, a reaction by its requester therefore
     * reaches nobody. This is decided from the request state alone, so it is identical for a
     * pending and a declined request and the requester learns nothing from the difference. A
     * reaction by the request's recipient, and every reaction once the request is accepted, reaches
     * the same accounts {@link #recipients} names.
     *
     * @param conversation the conversation as it stands after the change
     * @param actorId the account that reacted, never part of the result
     * @return the other active participants who may be told, in join order
     */
    public List<UUID> reactionRecipients(Conversation conversation, UUID actorId) {
        boolean unaccepted = conversation.getRequestStatus() != ConversationRequestStatus.ACCEPTED;
        if (unaccepted && !actorId.equals(conversation.getRequestRecipientId())) {
            return List.of();
        }
        return recipients(conversation, actorId);
    }

    /**
     * The folder the conversation occupies for its recipient, in the vocabulary of the conversation
     * list's {@code folder} parameter.
     *
     * @param conversation the conversation as it stands after the change
     * @return {@code primary}, {@code requests} or {@code hidden_requests}
     */
    public static String folderOf(Conversation conversation) {
        if (conversation.getRequestStatus() != ConversationRequestStatus.PENDING) {
            return "primary";
        }
        return conversation.isRequestHidden() ? "hidden_requests" : "requests";
    }
}
