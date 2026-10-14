package com.app.modules.message.service.impl;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.modules.message.dto.response.MessageReactionResponse;
import com.app.modules.message.entity.Conversation;
import com.app.modules.message.entity.Message;
import com.app.modules.message.entity.MessageReaction;
import com.app.modules.message.mapper.MessageMapper;
import com.app.modules.message.messaging.MessageEventTypes;
import com.app.modules.message.repository.ConversationParticipantRepository;
import com.app.modules.message.repository.ConversationRepository;
import com.app.modules.message.repository.MessageReactionRepository;
import com.app.modules.message.repository.MessageRepository;
import com.app.modules.message.service.MessageReactionService;
import com.app.modules.message.util.ReactionEmojiValidator;
import com.app.modules.social.service.SocialService;

/** Default {@link MessageReactionService}. */
@Service
public class MessageReactionServiceImpl implements MessageReactionService {

    private static final String AGGREGATE_TYPE = "message";

    private final ConversationRepository conversationRepository;
    private final ConversationParticipantRepository participantRepository;
    private final MessageRepository messageRepository;
    private final MessageReactionRepository reactionRepository;
    private final SocialService socialService;
    private final MessageMapper mapper;
    private final OutboxService outboxService;
    private final MessageInboxAudience inboxAudience;

    public MessageReactionServiceImpl(
            ConversationRepository conversationRepository,
            ConversationParticipantRepository participantRepository,
            MessageRepository messageRepository,
            MessageReactionRepository reactionRepository,
            SocialService socialService,
            MessageMapper mapper,
            OutboxService outboxService,
            MessageInboxAudience inboxAudience) {
        this.conversationRepository = conversationRepository;
        this.participantRepository = participantRepository;
        this.messageRepository = messageRepository;
        this.reactionRepository = reactionRepository;
        this.socialService = socialService;
        this.mapper = mapper;
        this.outboxService = outboxService;
        this.inboxAudience = inboxAudience;
    }

    @Override
    @Transactional
    public MessageReactionResponse react(
            UUID actorId, UUID conversationId, UUID messageId, String emoji) {
        Conversation conversation = requireReactableConversation(actorId, conversationId);
        Message message = requireMessage(conversationId, messageId);
        if (isTombstoned(message)) {
            throw new AppException(ApiErrorCode.MESSAGE_NOT_FOUND);
        }
        // Validated after the access checks so a caller who may not touch the message, a blocked
        // one included, gets the same answer whatever they sent.
        if (!ReactionEmojiValidator.isSingleEmoji(emoji)) {
            throw new AppException(ApiErrorCode.MESSAGE_REACTION_INVALID_EMOJI);
        }
        int changed = reactionRepository.upsert(messageId, actorId, emoji);
        MessageReaction stored =
                reactionRepository
                        .findByMessageIdAndUserId(messageId, actorId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.INTERNAL_ERROR));
        // A reaction an administrator removed stays hidden, so changing its emoji is not news.
        if (changed > 0 && stored.getAdminRemovedAt() == null) {
            enqueueChanged(conversation, messageId, actorId, emoji, stored.getReactedAt());
        }
        return mapper.toReactionResponse(stored);
    }

    @Override
    @Transactional
    public void unreact(UUID actorId, UUID conversationId, UUID messageId) {
        Conversation conversation = requireReactableConversation(actorId, conversationId);
        Message message = requireMessage(conversationId, messageId);
        // A message that is no longer shown has no visible reactions to remove; leaving the row
        // alone lets an administrative restore bring the message back exactly as it was.
        if (isTombstoned(message)) {
            return;
        }
        // The event is decided from the row this statement removed, in this transaction, so a
        // concurrent react cannot make a read taken beforehand disagree with the delete. A
        // reaction an administrator had already hidden was never shown, so its removal is not news.
        reactionRepository
                .deleteReaction(messageId, actorId)
                .filter(removed -> removed.getAdminRemovedAt() == null)
                .ifPresent(
                        removed ->
                                enqueueChanged(
                                        conversation,
                                        messageId,
                                        actorId,
                                        null,
                                        OffsetDateTime.now(ZoneOffset.UTC)));
    }

    // The block gate runs before anything else about the message is looked at, and its refusal is
    // the same not-found a missing conversation gives, so a blocked pair learns nothing from a
    // reaction attempt. The other participants are read whatever their left_at says, because a
    // peer who deleted the conversation and then blocked the actor is still a block.
    private Conversation requireReactableConversation(UUID actorId, UUID conversationId) {
        Conversation conversation =
                conversationRepository
                        .findById(conversationId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.CONVERSATION_NOT_FOUND));
        if (!participantRepository.existsByIdConversationIdAndIdUserIdAndLeftAtIsNull(
                conversationId, actorId)) {
            throw new AppException(ApiErrorCode.CONVERSATION_FORBIDDEN);
        }
        boolean blocked =
                participantRepository
                        .findByIdConversationIdOrderByJoinedAtAsc(conversationId)
                        .stream()
                        .map(participant -> participant.getId().getUserId())
                        .filter(userId -> !userId.equals(actorId))
                        .anyMatch(userId -> socialService.isBlockedBetween(actorId, userId));
        if (blocked) {
            throw new AppException(ApiErrorCode.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    private Message requireMessage(UUID conversationId, UUID messageId) {
        return messageRepository
                .findByIdAndConversationId(messageId, conversationId)
                .orElseThrow(() -> new AppException(ApiErrorCode.MESSAGE_NOT_FOUND));
    }

    private static boolean isTombstoned(Message message) {
        return message.isDeleted() || message.getAdminRemovedAt() != null;
    }

    // A removal omits the emoji key because the outbox payload map rejects null values; consumers
    // read an absent emoji as no reaction. The inbox audience is decided here so the live consumer
    // needs no lookup, and the consumer strips it before the event reaches the thread topic. No
    // folder is carried: a reaction does not move a conversation between folders.
    private void enqueueChanged(
            Conversation conversation,
            UUID messageId,
            UUID userId,
            String emoji,
            OffsetDateTime reactedAt) {
        UUID conversationId = conversation.getId();
        Map<String, Object> data = new HashMap<>();
        data.put(
                "inboxRecipientIds",
                inboxAudience.recipients(conversation, userId).stream()
                        .map(UUID::toString)
                        .toList());
        data.put("conversationId", conversationId.toString());
        data.put("messageId", messageId.toString());
        data.put("userId", userId.toString());
        if (emoji != null) {
            data.put("emoji", emoji);
        }
        data.put("reactedAt", reactedAt.toString());
        outboxService.enqueue(
                MessageEventTypes.MESSAGE_REACTION_CHANGED_V1,
                MessageEventTypes.MESSAGE_REACTION_CHANGED_V1,
                AGGREGATE_TYPE,
                messageId,
                userId,
                data);
    }
}
