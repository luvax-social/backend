package com.app.modules.message.service.impl;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.common.pagination.Cursor;
import com.app.common.pagination.CursorCodec;
import com.app.common.pagination.CursorScope;
import com.app.common.pagination.TimeCursors;
import com.app.common.response.CursorPageResponse;
import com.app.modules.media.entity.MediaAsset;
import com.app.modules.message.dto.request.CreateDirectConversationRequest;
import com.app.modules.message.dto.response.ConversationResponse;
import com.app.modules.message.dto.response.ConversationSummaryResponse;
import com.app.modules.message.dto.response.LastReactionPreviewResponse;
import com.app.modules.message.dto.response.MessageMediaResponse;
import com.app.modules.message.dto.response.MessageResponse;
import com.app.modules.message.dto.response.ParticipantResponse;
import com.app.modules.message.entity.Conversation;
import com.app.modules.message.entity.ConversationParticipant;
import com.app.modules.message.entity.ConversationParticipantId;
import com.app.modules.message.entity.Message;
import com.app.modules.message.entity.MessageReaction;
import com.app.modules.message.enums.ConversationFolder;
import com.app.modules.message.enums.ConversationRequestStatus;
import com.app.modules.message.mapper.MessageMapper;
import com.app.modules.message.messaging.MessageEventTypes;
import com.app.modules.message.repository.ConversationParticipantRepository;
import com.app.modules.message.repository.ConversationRepository;
import com.app.modules.message.repository.ConversationUnreadCount;
import com.app.modules.message.repository.MessageMediaAssetRepository;
import com.app.modules.message.repository.MessageReactionRepository;
import com.app.modules.message.repository.MessageRepository;
import com.app.modules.message.repository.MessageUserRepository;
import com.app.modules.message.service.ConversationService;
import com.app.modules.social.policy.ConversationContext;
import com.app.modules.social.policy.ConversationRequestState;
import com.app.modules.social.policy.InteractionDecision;
import com.app.modules.social.policy.InteractionPolicyService;
import com.app.modules.social.policy.InteractionReason;
import com.app.modules.social.policy.InteractionRequest;
import com.app.modules.social.policy.InteractionType;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.entity.User;

@Service
public class ConversationServiceImpl implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String AGGREGATE_TYPE = "message";

    private final ConversationRepository conversationRepository;
    private final ConversationParticipantRepository participantRepository;
    private final MessageRepository messageRepository;
    private final MessageUserRepository userRepository;
    private final InteractionPolicyService interactionPolicy;
    private final MessageMapper mapper;
    // Read-only view of the media module, mirroring MessageServiceImpl. The conversation list
    // renders the newest message, which may be an attachment.
    private final MessageMediaAssetRepository mediaAssetRepository;
    private final SocialService socialService;
    private final OutboxService outboxService;
    private final MessageReactionRepository reactionRepository;

    public ConversationServiceImpl(
            ConversationRepository conversationRepository,
            ConversationParticipantRepository participantRepository,
            MessageRepository messageRepository,
            MessageUserRepository userRepository,
            InteractionPolicyService interactionPolicy,
            MessageMapper mapper,
            MessageMediaAssetRepository mediaAssetRepository,
            SocialService socialService,
            OutboxService outboxService,
            MessageReactionRepository reactionRepository) {
        this.conversationRepository = conversationRepository;
        this.participantRepository = participantRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.interactionPolicy = interactionPolicy;
        this.mapper = mapper;
        this.mediaAssetRepository = mediaAssetRepository;
        this.socialService = socialService;
        this.outboxService = outboxService;
        this.reactionRepository = reactionRepository;
    }

    /**
     * Looks up a message's resolved media, tolerating a message that carries none.
     *
     * <p>{@code Map.of()} throws on a null key and a text message has no asset id, so the absent
     * case is checked here rather than left to the map.
     *
     * @param media resolved media keyed by asset id
     * @param message the message being rendered
     * @return the resolved media, or null when the message has no attachment
     */
    private static MessageMediaResponse mediaFor(
            Map<UUID, MessageMediaResponse> media, Message message) {
        UUID assetId = message.getMediaAssetId();
        return assetId == null ? null : media.get(assetId);
    }

    // The preview shows a reaction only when it is the newest thing in the thread. A reaction older
    // than the last message sits behind that message, which is already the row's preview, and a
    // reaction never moves the row because it leaves last_message_at and the unread count alone.
    private Map<UUID, LastReactionPreviewResponse> lastReactions(
            UUID viewerId,
            List<UUID> conversationIds,
            Map<UUID, MessageResponse> lastMessageByConversation) {
        List<MessageReaction> latest =
                reactionRepository.findLatestVisiblePerConversation(conversationIds, viewerId);
        if (latest.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Message> reactedMessages =
                messageRepository
                        .findAllById(latest.stream().map(MessageReaction::getMessageId).toList())
                        .stream()
                        .collect(Collectors.toMap(Message::getId, Function.identity()));
        Map<UUID, LastReactionPreviewResponse> previews = new HashMap<>();
        for (MessageReaction reaction : latest) {
            Message message = reactedMessages.get(reaction.getMessageId());
            if (message == null) {
                continue;
            }
            MessageResponse lastMessage =
                    lastMessageByConversation.get(message.getConversationId());
            if (lastMessage != null
                    && lastMessage.createdAt() != null
                    && !reaction.getReactedAt().isAfter(lastMessage.createdAt())) {
                continue;
            }
            previews.put(
                    message.getConversationId(),
                    new LastReactionPreviewResponse(
                            reaction.getUserId(),
                            reaction.getEmoji(),
                            message.getId(),
                            reaction.getReactedAt(),
                            viewerId.equals(message.getSenderId())));
        }
        return previews;
    }

    @Override
    @Transactional
    public ConversationResponse createDirectConversation(
            UUID actorId, CreateDirectConversationRequest request) {
        UUID targetId = request.targetUserId();
        if (actorId.equals(targetId)) {
            throw new AppException(
                    ApiErrorCode.CONVERSATION_INVALID_PARTICIPANTS, "Cannot message yourself");
        }
        userRepository
                .findByIdAndDeletedAtIsNull(targetId)
                .orElseThrow(() -> new AppException(ApiErrorCode.USER_NOT_FOUND));

        // Serializes concurrent createDirectConversation calls for this pair so the
        // check-then-create below cannot race into two conversations; the lock is released
        // automatically at transaction end, and the returned key is reused on insert as a
        // database-level uniqueness backstop.
        String pairKey = conversationRepository.lockDirectConversationPair(actorId, targetId);

        Optional<Conversation> existing =
                conversationRepository.findDirectConversationBetween(actorId, targetId);
        Conversation conversation;
        if (existing.isPresent() && !declinedByActor(existing.get(), actorId)) {
            conversation = reuseExisting(existing.get(), actorId, targetId);
        } else {
            // The recipient who declined is now starting their own conversation. The declined one
            // stays as the old sender saw it; only its claim on the pair is released, so the new
            // conversation can take the pair key.
            existing.ifPresent(
                    declined -> conversationRepository.retireDirectPairKey(declined.getId()));
            conversation = startConversation(actorId, targetId, pairKey);
        }
        return assembleDetail(conversation, actorId);
    }

    private static boolean declinedByActor(Conversation conversation, UUID actorId) {
        return conversation.getRequestStatus() == ConversationRequestStatus.DECLINED
                && actorId.equals(conversation.getRequestRecipientId());
    }

    // An existing conversation is reused without asking for message permission again, so an
    // accepted one survives the recipient unfollowing, tightening their settings or blocking and
    // later unblocking. A pending or declined one is reused unchanged for its sender, which keeps a
    // decline invisible, and a recipient opening a pending request simply gets it back.
    private Conversation reuseExisting(Conversation conversation, UUID actorId, UUID targetId) {
        assertNotBlocked(actorId, targetId);
        // Reactivate the caller's own membership if they had previously left; the other
        // participant's state is untouched.
        ConversationParticipant actorParticipant =
                participantRepository
                        .findByIdConversationIdAndIdUserId(conversation.getId(), actorId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.CONVERSATION_NOT_FOUND));
        if (actorParticipant.getLeftAt() != null) {
            actorParticipant.setLeftAt(null);
            participantRepository.save(actorParticipant);
        }
        return conversation;
    }

    private Conversation startConversation(UUID actorId, UUID targetId, String pairKey) {
        InteractionDecision decision =
                interactionPolicy.evaluate(
                        new InteractionRequest(
                                actorId,
                                targetId,
                                InteractionType.MESSAGE_INITIATE,
                                null,
                                null,
                                null));
        if (decision.denied()) {
            throw refusal(decision);
        }
        Conversation.ConversationBuilder builder =
                Conversation.builder().createdBy(actorId).directPairKey(pairKey);
        switch (decision.outcome()) {
            case ALLOW -> builder.requestStatus(ConversationRequestStatus.ACCEPTED);
            case ROUTE_TO_REQUESTS ->
                    builder.requestStatus(ConversationRequestStatus.PENDING)
                            .requestRecipientId(targetId);
            case ROUTE_TO_HIDDEN_REQUESTS ->
                    builder.requestStatus(ConversationRequestStatus.PENDING)
                            .requestRecipientId(targetId)
                            .requestHidden(true);
            default ->
                    throw new IllegalStateException(
                            "unexpected initiation outcome " + decision.outcome());
        }
        Conversation conversation = builder.build();
        conversationRepository.saveAndFlush(conversation);
        participantRepository.save(newParticipant(conversation.getId(), actorId));
        participantRepository.save(newParticipant(conversation.getId(), targetId));
        log.info(
                "Direct conversation created: conversationId={}, requestStatus={}",
                conversation.getId(),
                conversation.getRequestStatus());
        return conversation;
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<ConversationSummaryResponse> listMyConversations(
            UUID actorId, ConversationFolder folder, String cursor, int limit) {
        int pageSize = normalizeLimit(limit);
        ConversationCursor decoded = decodeCursor(cursor);
        PageRequest page = PageRequest.of(0, pageSize + 1);
        List<Conversation> conversations =
                decoded.isEmpty()
                        ? conversationRepository.findFirstMyConversations(
                                actorId, folder.sqlName(), page)
                        : conversationRepository.findMyConversationsBefore(
                                actorId,
                                folder.sqlName(),
                                decoded.lastMessageAt(),
                                decoded.conversationId(),
                                page);
        boolean hasNextPage = conversations.size() > pageSize;
        if (hasNextPage) {
            conversations = conversations.subList(0, pageSize);
        }
        // Cursors are derived from the unpinned page alone, before the pinned prepend below, so
        // continuation always resumes within the keyset-paginated sequence and never re-anchors on
        // a pinned row.
        String startCursor = conversations.isEmpty() ? null : encodeCursor(conversations.get(0));
        String endCursor =
                conversations.isEmpty()
                        ? null
                        : encodeCursor(conversations.get(conversations.size() - 1));

        // Pinned conversations bypass keyset pagination entirely and are shown ahead of the first
        // page of the primary folder only; see ConversationRepository.findMyPinnedConversations.
        List<Conversation> pinned =
                decoded.isEmpty() && folder == ConversationFolder.PRIMARY
                        ? conversationRepository.findMyPinnedConversations(actorId)
                        : List.of();
        List<Conversation> allConversations =
                pinned.isEmpty()
                        ? conversations
                        : Stream.concat(pinned.stream(), conversations.stream()).toList();
        if (allConversations.isEmpty()) {
            return CursorPageResponse.of(
                    Collections.emptyList(), false, null, null, cursor != null);
        }

        List<UUID> conversationIds = allConversations.stream().map(Conversation::getId).toList();
        List<ConversationParticipant> activeParticipants =
                participantRepository.findByIdConversationIdInAndLeftAtIsNull(conversationIds);
        Map<UUID, List<ParticipantResponse>> participantsByConversation =
                groupParticipantResponses(activeParticipants);
        restoreDeclinedRecipients(allConversations, actorId, participantsByConversation);
        Map<UUID, ConversationParticipant> myParticipantByConversation =
                activeParticipants.stream()
                        .filter(p -> p.getId().getUserId().equals(actorId))
                        .collect(
                                Collectors.toMap(
                                        p -> p.getId().getConversationId(), Function.identity()));
        Map<UUID, Long> unreadByConversation =
                messageRepository.countUnreadPerConversation(actorId, conversationIds).stream()
                        .collect(
                                Collectors.toMap(
                                        ConversationUnreadCount::getConversationId,
                                        ConversationUnreadCount::getUnreadCount));
        List<Message> lastMessages =
                messageRepository.findLastMessagePerConversation(conversationIds);
        // One batched asset lookup for the whole list. A conversation whose newest message is an
        // image otherwise shows a preview row with no way to render its attachment.
        Set<UUID> lastMessageAssetIds =
                lastMessages.stream()
                        .map(Message::getMediaAssetId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        Map<UUID, MessageMediaResponse> lastMessageMedia =
                lastMessageAssetIds.isEmpty()
                        ? Map.of()
                        : mediaAssetRepository.findAllById(lastMessageAssetIds).stream()
                                .collect(
                                        Collectors.toMap(
                                                MediaAsset::getId, mapper::toMediaResponse));
        Map<UUID, Message> lastMessageParents = ReplyParents.load(messageRepository, lastMessages);
        Map<UUID, MessageResponse> lastMessageByConversation =
                lastMessages.stream()
                        .collect(
                                Collectors.toMap(
                                        Message::getConversationId,
                                        m ->
                                                mapper.toMessageResponse(
                                                        m,
                                                        mediaFor(lastMessageMedia, m),
                                                        lastMessageParents)));

        Map<UUID, LastReactionPreviewResponse> lastReactionByConversation =
                lastReactions(actorId, conversationIds, lastMessageByConversation);
        List<ConversationSummaryResponse> content =
                allConversations.stream()
                        .map(
                                c -> {
                                    ConversationParticipant mine =
                                            myParticipantByConversation.get(c.getId());
                                    boolean isPinned = mine != null && mine.getPinnedAt() != null;
                                    boolean isMuted = mine != null && mine.isMuted();
                                    boolean isManuallyUnread =
                                            mine != null && mine.isManuallyUnread();
                                    boolean recipient = isUnacceptedRecipient(c, actorId);
                                    return mapper.toSummaryResponse(
                                            c,
                                            participantsByConversation.getOrDefault(
                                                    c.getId(), List.of()),
                                            unreadByConversation.getOrDefault(c.getId(), 0L),
                                            lastMessageByConversation.get(c.getId()),
                                            isPinned,
                                            isMuted,
                                            isManuallyUnread,
                                            requestStatusLabel(c),
                                            recipient,
                                            recipient && c.isRequestHidden(),
                                            sendRestrictionLabel(c, recipient),
                                            lastReactionByConversation.get(c.getId()));
                                })
                        .toList();
        return CursorPageResponse.of(content, hasNextPage, startCursor, endCursor, cursor != null);
    }

    @Override
    @Transactional(readOnly = true)
    public ConversationResponse getConversation(UUID actorId, UUID conversationId) {
        Conversation conversation = fetchConversation(conversationId);
        requireActiveParticipant(conversationId, actorId);
        return assembleDetail(conversation, actorId);
    }

    @Override
    @Transactional
    public void leaveConversation(UUID actorId, UUID conversationId) {
        Conversation conversation = fetchConversation(conversationId);
        // Deleting a request from the inbox is declining it. A plain departure would leave the
        // request pending with the recipient gone, which the sender could tell from silence.
        if (conversation.getRequestStatus() == ConversationRequestStatus.PENDING
                && actorId.equals(conversation.getRequestRecipientId())) {
            deleteRequest(actorId, conversationId);
            return;
        }
        ConversationParticipant participant =
                participantRepository
                        .findByIdConversationIdAndIdUserId(conversationId, actorId)
                        .filter(p -> p.getLeftAt() == null)
                        .orElseThrow(() -> new AppException(ApiErrorCode.CONVERSATION_FORBIDDEN));
        participant.setLeftAt(OffsetDateTime.now(ZoneOffset.UTC));
        participantRepository.save(participant);
    }

    @Override
    @Transactional
    public void pinConversation(UUID actorId, UUID conversationId) {
        ConversationParticipant participant = fetchActiveParticipant(conversationId, actorId);
        participant.setPinnedAt(OffsetDateTime.now(ZoneOffset.UTC));
        participantRepository.save(participant);
    }

    @Override
    @Transactional
    public void unpinConversation(UUID actorId, UUID conversationId) {
        ConversationParticipant participant = fetchActiveParticipant(conversationId, actorId);
        participant.setPinnedAt(null);
        participantRepository.save(participant);
    }

    @Override
    @Transactional
    public void muteConversation(UUID actorId, UUID conversationId) {
        ConversationParticipant participant = fetchActiveParticipant(conversationId, actorId);
        participant.setMuted(true);
        participantRepository.save(participant);
    }

    @Override
    @Transactional
    public void unmuteConversation(UUID actorId, UUID conversationId) {
        ConversationParticipant participant = fetchActiveParticipant(conversationId, actorId);
        participant.setMuted(false);
        participantRepository.save(participant);
    }

    @Override
    @Transactional
    public void setNickname(UUID actorId, UUID conversationId, String nickname) {
        ConversationParticipant participant = fetchActiveParticipant(conversationId, actorId);
        participant.setNickname(nickname == null || nickname.isBlank() ? null : nickname);
        participantRepository.save(participant);
    }

    @Override
    @Transactional
    public ConversationResponse acceptRequest(UUID actorId, UUID conversationId) {
        OpenRequest request = lockOpenRequest(actorId, conversationId);
        Conversation conversation = request.conversation();
        // Accepting is interacting, so a block in either direction wins and answers as a missing
        // request. Declining stays possible under a block: it only ends the recipient's side.
        if (blockedBetween(actorId, request.requesterId())) {
            throw new AppException(ApiErrorCode.CONVERSATION_NOT_FOUND);
        }
        conversation.setRequestStatus(ConversationRequestStatus.ACCEPTED);
        conversation.setRequestHidden(false);
        conversation.setRequestResolvedAt(OffsetDateTime.now(ZoneOffset.UTC));
        conversationRepository.save(conversation);
        enqueueResolved(conversationId, actorId, "accepted");
        return assembleDetail(conversation, actorId);
    }

    @Override
    @Transactional
    public void deleteRequest(UUID actorId, UUID conversationId) {
        decline(lockOpenRequest(actorId, conversationId).conversation(), actorId, "deleted");
    }

    @Override
    @Transactional
    public void blockRequester(UUID actorId, UUID conversationId) {
        OpenRequest request = lockOpenRequest(actorId, conversationId);
        decline(request.conversation(), actorId, "blocked");
        // Same transaction as the decline: a block that cannot be created leaves the request
        // pending rather than declined and unblocked. When the recipient had already blocked the
        // sender there is nothing to create, and the decline still has to resolve the request.
        if (!conversationRepository.existsBlock(actorId, request.requesterId())) {
            socialService.blockUser(actorId, request.requesterId());
        }
    }

    // The sender keeps reading the request as pending, so nothing about the decline is written to
    // anything the sender can see: only the recipient's own membership ends and the row records
    // when it was resolved.
    private void decline(Conversation conversation, UUID actorId, String resolution) {
        conversation.setRequestStatus(ConversationRequestStatus.DECLINED);
        conversation.setRequestResolvedAt(OffsetDateTime.now(ZoneOffset.UTC));
        conversationRepository.save(conversation);
        ConversationParticipant recipient =
                participantRepository
                        .findByIdConversationIdAndIdUserId(conversation.getId(), actorId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.CONVERSATION_NOT_FOUND));
        recipient.setLeftAt(OffsetDateTime.now(ZoneOffset.UTC));
        participantRepository.save(recipient);
        enqueueResolved(conversation.getId(), actorId, resolution);
    }

    private record OpenRequest(Conversation conversation, UUID requesterId) {}

    // Takes the pair lock before the row lock, the order every other writer of a pair's
    // conversation uses, so a decision cannot deadlock with or be overwritten by the mutual follow
    // provisioner. A request with no message yet is not listed to its recipient, so acting on it
    // answers as if it did not exist; declining one would also leave a state the sender could tell
    // from a pending one.
    private OpenRequest lockOpenRequest(UUID actorId, UUID conversationId) {
        UUID requesterId =
                participantRepository
                        .findByIdConversationIdOrderByJoinedAtAsc(conversationId)
                        .stream()
                        .map(p -> p.getId().getUserId())
                        .filter(userId -> !userId.equals(actorId))
                        .findFirst()
                        .orElseThrow(() -> new AppException(ApiErrorCode.CONVERSATION_NOT_FOUND));
        conversationRepository.lockDirectConversationPair(actorId, requesterId);
        Conversation conversation =
                conversationRepository
                        .lockById(conversationId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.CONVERSATION_NOT_FOUND));
        boolean open =
                conversation.getRequestStatus() == ConversationRequestStatus.PENDING
                        && actorId.equals(conversation.getRequestRecipientId())
                        && conversation.getLastMessageAt() != null
                        && participantRepository.existsByIdConversationIdAndIdUserIdAndLeftAtIsNull(
                                conversationId, actorId);
        if (!open) {
            throw new AppException(ApiErrorCode.CONVERSATION_NOT_FOUND);
        }
        return new OpenRequest(conversation, requesterId);
    }

    // The policy's block rule alone, asked through a send into an accepted conversation.
    private boolean blockedBetween(UUID actorId, UUID otherId) {
        InteractionDecision decision =
                interactionPolicy.evaluate(
                        new InteractionRequest(
                                actorId,
                                otherId,
                                InteractionType.MESSAGE_SEND,
                                null,
                                null,
                                new ConversationContext(
                                        ConversationRequestState.ACCEPTED, true, 0, "text")));
        return decision.denied() && decision.reason() == InteractionReason.BLOCKED;
    }

    private void enqueueResolved(UUID conversationId, UUID actorId, String resolution) {
        outboxService.enqueue(
                MessageEventTypes.MESSAGE_REQUEST_RESOLVED_V1,
                MessageEventTypes.MESSAGE_REQUEST_RESOLVED_V1,
                AGGREGATE_TYPE,
                conversationId,
                actorId,
                Map.of("conversationId", conversationId.toString(), "resolution", resolution));
    }

    private ConversationParticipant fetchActiveParticipant(UUID conversationId, UUID actorId) {
        fetchConversation(conversationId);
        return participantRepository
                .findByIdConversationIdAndIdUserId(conversationId, actorId)
                .filter(p -> p.getLeftAt() == null)
                .orElseThrow(() -> new AppException(ApiErrorCode.CONVERSATION_FORBIDDEN));
    }

    // A send evaluated against an accepted conversation runs only the block rule, which makes it
    // the policy's way to ask "may these two interact at all" without applying a message
    // permission to a conversation that already exists.
    private void assertNotBlocked(UUID actorId, UUID targetId) {
        InteractionDecision decision =
                interactionPolicy.evaluate(
                        new InteractionRequest(
                                actorId,
                                targetId,
                                InteractionType.MESSAGE_SEND,
                                null,
                                null,
                                new ConversationContext(
                                        ConversationRequestState.ACCEPTED, true, 0, "text")));
        if (decision.denied()) {
            throw refusal(decision);
        }
    }

    // Stealth block model: a block in either direction must be indistinguishable from the target
    // not existing, not a status that confirms a block relationship.
    private static AppException refusal(InteractionDecision decision) {
        if (decision.reason() == InteractionReason.BLOCKED) {
            return new AppException(ApiErrorCode.NOT_FOUND, "Target user not found");
        }
        return new AppException(ApiErrorCode.MESSAGE_REQUEST_NOT_ALLOWED);
    }

    private Conversation fetchConversation(UUID conversationId) {
        return conversationRepository
                .findById(conversationId)
                .orElseThrow(() -> new AppException(ApiErrorCode.CONVERSATION_NOT_FOUND));
    }

    private void requireActiveParticipant(UUID conversationId, UUID userId) {
        if (!participantRepository.existsByIdConversationIdAndIdUserIdAndLeftAtIsNull(
                conversationId, userId)) {
            throw new AppException(ApiErrorCode.CONVERSATION_FORBIDDEN);
        }
    }

    private static ConversationParticipant newParticipant(UUID conversationId, UUID userId) {
        return ConversationParticipant.builder()
                .id(new ConversationParticipantId(conversationId, userId))
                .build();
    }

    private ConversationResponse assembleDetail(Conversation conversation, UUID actorId) {
        ConversationParticipant mine =
                participantRepository
                        .findByIdConversationIdAndIdUserId(conversation.getId(), actorId)
                        .orElse(null);
        boolean pinned = mine != null && mine.getPinnedAt() != null;
        boolean muted = mine != null && mine.isMuted();
        boolean recipient = isUnacceptedRecipient(conversation, actorId);
        return mapper.toConversationResponse(
                conversation,
                assembleParticipants(conversation, actorId),
                pinned,
                muted,
                requestStatusLabel(conversation),
                recipient,
                sendRestrictionLabel(conversation, recipient));
    }

    private static boolean isUnacceptedRecipient(Conversation conversation, UUID actorId) {
        return conversation.getRequestStatus() != ConversationRequestStatus.ACCEPTED
                && actorId.equals(conversation.getRequestRecipientId());
    }

    // A declined request reads as pending: the sender must not be able to tell the two apart.
    private static String requestStatusLabel(Conversation conversation) {
        return conversation.getRequestStatus() == ConversationRequestStatus.ACCEPTED
                ? "accepted"
                : "pending";
    }

    // While a request is open its sender may write once, and only the sender can have written, so a
    // last message means the one allowed message is spent. A declined request allows none.
    private static String sendRestrictionLabel(Conversation conversation, boolean recipient) {
        boolean awaiting =
                !recipient
                        && (conversation.getRequestStatus() == ConversationRequestStatus.DECLINED
                                || (conversation.getRequestStatus()
                                                == ConversationRequestStatus.PENDING
                                        && conversation.getLastMessageAt() != null));
        return awaiting ? "awaiting_acceptance" : "none";
    }

    private List<ParticipantResponse> assembleParticipants(
            Conversation conversation, UUID actorId) {
        List<ConversationParticipant> participants =
                participantRepository.findByIdConversationIdOrderByJoinedAtAsc(
                        conversation.getId());
        Map<UUID, User> users = hydrateUsers(participants);
        return participants.stream()
                .map(
                        p -> {
                            ParticipantResponse response =
                                    mapper.toParticipantResponse(
                                            p, users.get(p.getId().getUserId()));
                            return hidesDecline(conversation, actorId)
                                            && p.getId()
                                                    .getUserId()
                                                    .equals(conversation.getRequestRecipientId())
                                    ? withoutLeftAt(response)
                                    : response;
                        })
                .toList();
    }

    // The recipient of an unaccepted request may have left it, which would show on their
    // participant row. The sender must see the row an untouched pending request has, so the
    // departure is not reported to them.
    private static boolean hidesDecline(Conversation conversation, UUID actorId) {
        return conversation.getRequestStatus() != ConversationRequestStatus.ACCEPTED
                && !actorId.equals(conversation.getRequestRecipientId());
    }

    private static ParticipantResponse withoutLeftAt(ParticipantResponse response) {
        return new ParticipantResponse(
                response.userId(),
                response.username(),
                response.isVerified(),
                response.verifiedCategory(),
                response.displayName(),
                response.avatarUrl(),
                response.joinedAt(),
                null,
                response.nickname());
    }

    // The list reads only active participants, so the recipient who left an unaccepted request
    // would drop out of the sender's row. They are put back, as a pending request still shows them.
    private void restoreDeclinedRecipients(
            List<Conversation> conversations,
            UUID actorId,
            Map<UUID, List<ParticipantResponse>> participantsByConversation) {
        for (Conversation conversation : conversations) {
            if (!hidesDecline(conversation, actorId)) {
                continue;
            }
            List<ConversationParticipant> departed =
                    participantRepository
                            .findByIdConversationIdOrderByJoinedAtAsc(conversation.getId())
                            .stream()
                            .filter(p -> p.getLeftAt() != null)
                            .filter(
                                    p ->
                                            p.getId()
                                                    .getUserId()
                                                    .equals(conversation.getRequestRecipientId()))
                            .toList();
            Map<UUID, User> users = hydrateUsers(departed);
            List<ParticipantResponse> restored =
                    new ArrayList<>(
                            participantsByConversation.getOrDefault(
                                    conversation.getId(), List.of()));
            for (ConversationParticipant participant : departed) {
                restored.add(
                        withoutLeftAt(
                                mapper.toParticipantResponse(
                                        participant, users.get(participant.getId().getUserId()))));
            }
            participantsByConversation.put(conversation.getId(), restored);
        }
    }

    private Map<UUID, List<ParticipantResponse>> groupParticipantResponses(
            List<ConversationParticipant> participants) {
        Map<UUID, User> users = hydrateUsers(participants);
        Map<UUID, List<ParticipantResponse>> result = new LinkedHashMap<>();
        for (ConversationParticipant participant : participants) {
            result.computeIfAbsent(participant.getId().getConversationId(), k -> new ArrayList<>())
                    .add(
                            mapper.toParticipantResponse(
                                    participant, users.get(participant.getId().getUserId())));
        }
        return result;
    }

    private Map<UUID, User> hydrateUsers(List<ConversationParticipant> participants) {
        Set<UUID> userIds =
                participants.stream()
                        .map(p -> p.getId().getUserId())
                        .collect(Collectors.toCollection(HashSet::new));
        return userIds.isEmpty()
                ? Map.of()
                : userRepository.findAllByIdInAndDeletedAtIsNull(userIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private int normalizeLimit(int limit) {
        return limit > MAX_PAGE_SIZE ? MAX_PAGE_SIZE : (limit < 1 ? DEFAULT_PAGE_SIZE : limit);
    }

    // The conversation list orders by last_message_at, a mutable key that advances whenever a new
    // message arrives, so a conversation can shift across a page boundary and be seen twice or
    // missed. That instability is intrinsic to any most-recently-active ordering and is accepted;
    // the (last_message_at, id) tuple only breaks exact ties, not the moving key.
    //
    // last_message_at is nullable (NULLS LAST group for a conversation with no message yet), which
    // the shared Cursor's sortValueMicros cannot represent directly since it is a primitive long.
    // NULL_LAST_MESSAGE_SENTINEL stands in for it instead of a bespoke hand-rolled codec: the
    // server is the only issuer of valid cursors and never assigns a real conversation's
    // last_message_at to exactly Long.MIN_VALUE, so the sentinel is unambiguous, and reusing
    // CursorCodec/TimeCursors bounds the decoded value to a long's range - closing the defect a
    // free-text OffsetDateTime.parse had, where a forged out-of-range year overflowed at JDBC
    // bind time instead of failing cleanly at decode time.
    private static final long NULL_LAST_MESSAGE_SENTINEL = Long.MIN_VALUE;

    private String encodeCursor(Conversation conversation) {
        long sortValue =
                conversation.getLastMessageAt() != null
                        ? TimeCursors.toMicros(conversation.getLastMessageAt())
                        : NULL_LAST_MESSAGE_SENTINEL;
        return CursorCodec.encode(
                new Cursor(sortValue, conversation.getId()), CursorScope.CONVERSATIONS);
    }

    private ConversationCursor decodeCursor(String cursor) {
        Cursor decoded = CursorCodec.decode(cursor, CursorScope.CONVERSATIONS);
        if (decoded == null) {
            return new ConversationCursor(null, null);
        }
        OffsetDateTime lastMessageAt =
                decoded.sortValueMicros() == NULL_LAST_MESSAGE_SENTINEL
                        ? null
                        : TimeCursors.fromMicros(decoded.sortValueMicros());
        return new ConversationCursor(lastMessageAt, decoded.id());
    }

    private record ConversationCursor(OffsetDateTime lastMessageAt, UUID conversationId) {
        // A decoded cursor always carries a conversationId, even when lastMessageAt is null
        // (continuation within the null group) - so conversationId alone signals "no cursor".
        boolean isEmpty() {
            return conversationId == null;
        }
    }
}
