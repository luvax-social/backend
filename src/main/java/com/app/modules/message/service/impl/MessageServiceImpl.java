package com.app.modules.message.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.common.response.CursorPageResponse;
import com.app.modules.media.entity.MediaAsset;
import com.app.modules.message.dto.request.SendMessageRequest;
import com.app.modules.message.dto.response.MessageMediaResponse;
import com.app.modules.message.dto.response.MessageReactionResponse;
import com.app.modules.message.dto.response.MessageResponse;
import com.app.modules.message.entity.Conversation;
import com.app.modules.message.entity.ConversationParticipant;
import com.app.modules.message.entity.Message;
import com.app.modules.message.entity.MessageReaction;
import com.app.modules.message.entity.MessageWriteIdempotency;
import com.app.modules.message.enums.ConversationRequestStatus;
import com.app.modules.message.enums.MessageType;
import com.app.modules.message.mapper.MessageMapper;
import com.app.modules.message.messaging.MessageEventTypes;
import com.app.modules.message.repository.ConversationParticipantRepository;
import com.app.modules.message.repository.ConversationRepository;
import com.app.modules.message.repository.MessageIdempotencyRepository;
import com.app.modules.message.repository.MessageMediaAssetRepository;
import com.app.modules.message.repository.MessagePostRepository;
import com.app.modules.message.repository.MessageReactionRepository;
import com.app.modules.message.repository.MessageRepository;
import com.app.modules.message.repository.MessageStoryRepository;
import com.app.modules.message.service.MessageService;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.social.policy.ConversationContext;
import com.app.modules.social.policy.ConversationRequestState;
import com.app.modules.social.policy.InteractionDecision;
import com.app.modules.social.policy.InteractionOutcome;
import com.app.modules.social.policy.InteractionPolicyService;
import com.app.modules.social.policy.InteractionReason;
import com.app.modules.social.policy.InteractionRequest;
import com.app.modules.social.policy.InteractionType;

import tools.jackson.databind.ObjectMapper;

@Service
public class MessageServiceImpl implements MessageService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String AGGREGATE_TYPE = "message";
    private static final char CURSOR_SEPARATOR = '|';

    private final MessageRepository messageRepository;
    private final MessageIdempotencyRepository idempotencyRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationParticipantRepository participantRepository;
    private final MessageMediaAssetRepository mediaAssetRepository;
    private final MessagePostRepository postRepository;
    private final MessageStoryRepository storyRepository;
    private final InteractionPolicyService interactionPolicy;
    private final MessageMapper mapper;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final MessageReactionRepository reactionRepository;
    private final MessageInboxAudience inboxAudience;

    public MessageServiceImpl(
            MessageRepository messageRepository,
            MessageIdempotencyRepository idempotencyRepository,
            ConversationRepository conversationRepository,
            ConversationParticipantRepository participantRepository,
            MessageMediaAssetRepository mediaAssetRepository,
            MessagePostRepository postRepository,
            MessageStoryRepository storyRepository,
            InteractionPolicyService interactionPolicy,
            MessageMapper mapper,
            OutboxService outboxService,
            ObjectMapper objectMapper,
            MessageReactionRepository reactionRepository,
            MessageInboxAudience inboxAudience) {
        this.messageRepository = messageRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.conversationRepository = conversationRepository;
        this.participantRepository = participantRepository;
        this.mediaAssetRepository = mediaAssetRepository;
        this.postRepository = postRepository;
        this.storyRepository = storyRepository;
        this.interactionPolicy = interactionPolicy;
        this.mapper = mapper;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
        this.reactionRepository = reactionRepository;
        this.inboxAudience = inboxAudience;
    }

    @Override
    @Transactional
    public MessageResponse sendMessage(
            UUID actorId, UUID conversationId, SendMessageRequest request, String idempotencyKey) {
        // The row lock serialises every send into one conversation. Without it two first messages
        // of a pending request would each count zero prior messages and both be stored, and a
        // send could race a decline or an acceptance of the same request.
        Conversation conversation =
                conversationRepository
                        .lockById(conversationId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.CONVERSATION_NOT_FOUND));
        requireActiveParticipant(conversationId, actorId);

        String requestHash =
                sha256(
                        conversationId
                                + "|"
                                + request.messageType()
                                + "|"
                                + request.content()
                                + "|"
                                + request.sharedPostId()
                                + "|"
                                + request.sharedStoryId()
                                + "|"
                                + request.mediaAssetId()
                                + "|"
                                + request.replyToId());
        // A retry of a send that already committed replays its response. This runs before the
        // policy because the retry of a requester's one allowed message would otherwise be
        // refused for having already sent it.
        if (idempotencyKey != null) {
            Optional<MessageWriteIdempotency> recorded =
                    idempotencyRepository.findByUserIdAndIdempotencyKey(actorId, idempotencyKey);
            if (recorded.isPresent()) {
                return replayOrConflict(recorded.get(), requestHash);
            }
        }
        InteractionDecision decision = evaluatePolicy(conversation, actorId, request);
        validatePayload(actorId, conversationId, request);

        // Reserve the idempotency key in the same transaction via ON CONFLICT DO NOTHING. A
        // duplicate key returns a clean replay/conflict instead of poisoning the transaction with a
        // constraint violation, and a rolled-back send frees the key.
        if (idempotencyKey != null
                && idempotencyRepository.insertIfAbsent(actorId, idempotencyKey, requestHash)
                        == 0) {
            return replayOrConflict(actorId, idempotencyKey, requestHash);
        }

        // saveAndFlush forces the INSERT to execute now, so the @CreationTimestamp-generated
        // createdAt is populated on the returned entity instead of staying null until some later
        // flush - both the response body and the outbox payload below read it immediately.
        Message saved =
                messageRepository.saveAndFlush(
                        Message.builder()
                                .conversationId(conversationId)
                                .senderId(actorId)
                                .messageType(request.messageType())
                                .content(request.content())
                                .mediaAssetId(request.mediaAssetId())
                                .sharedPostId(request.sharedPostId())
                                .sharedStoryId(request.sharedStoryId())
                                .replyToId(request.replyToId())
                                .build());
        MessageResponse response =
                mapper.toMessageResponse(
                        saved,
                        resolveMedia(saved),
                        ReplyParents.load(messageRepository, List.of(saved)));

        if (idempotencyKey != null) {
            idempotencyRepository.updateResponseBody(
                    actorId, idempotencyKey, objectMapper.writeValueAsString(response));
        }

        applyRequestOutcome(conversation, actorId, decision);
        reactivateOtherParticipants(conversation, actorId);
        enqueueSent(conversation, saved, actorId, response);
        return response;
    }

    /**
     * Un-hides the conversation for any other participant who had deleted it.
     *
     * <p>Deleting a conversation only sets {@code left_at} for the deleting user's own row - it
     * never touches the conversation or the other participant's state, matching the DATA_RULES note
     * that hiding is per-user. A new message is the signal that revives it for them, the same
     * behaviour the reference chat apps this feature was modelled on use. The one exception is the
     * recipient of a declined request, who chose to end the conversation and is never brought back
     * into it.
     */
    private void reactivateOtherParticipants(Conversation conversation, UUID actorId) {
        boolean declined = conversation.getRequestStatus() == ConversationRequestStatus.DECLINED;
        for (ConversationParticipant participant :
                participantRepository.findByIdConversationIdOrderByJoinedAtAsc(
                        conversation.getId())) {
            UUID participantId = participant.getId().getUserId();
            if (participant.getLeftAt() == null || participantId.equals(actorId)) {
                continue;
            }
            if (declined && participantId.equals(conversation.getRequestRecipientId())) {
                continue;
            }
            participant.setLeftAt(null);
            participantRepository.save(participant);
        }
    }

    // The block rule runs first inside the policy and its refusal is the same not-found a missing
    // conversation gives. Every other refusal is the single awaiting-acceptance answer, which a
    // pending and a declined request share so the requester cannot tell them apart.
    private InteractionDecision evaluatePolicy(
            Conversation conversation, UUID actorId, SendMessageRequest request) {
        // The other participant is read whatever either left_at says, so a peer who deleted the
        // conversation and then blocked the sender is still found
        UUID otherId =
                participantRepository
                        .findByIdConversationIdOrderByJoinedAtAsc(conversation.getId())
                        .stream()
                        .map(p -> p.getId().getUserId())
                        .filter(userId -> !userId.equals(actorId))
                        .findFirst()
                        .orElse(null);
        if (otherId == null) {
            return new InteractionDecision(InteractionOutcome.ALLOW, InteractionReason.NONE);
        }
        ConversationRequestStatus status = conversation.getRequestStatus();
        boolean actorIsRequester =
                status != ConversationRequestStatus.ACCEPTED
                        && !actorId.equals(conversation.getRequestRecipientId());
        long actorMessagesSoFar =
                actorIsRequester
                        ? messageRepository.countByConversationIdAndSenderId(
                                conversation.getId(), actorId)
                        : 0;
        InteractionDecision decision =
                interactionPolicy.evaluate(
                        new InteractionRequest(
                                actorId,
                                otherId,
                                InteractionType.MESSAGE_SEND,
                                request.content(),
                                null,
                                new ConversationContext(
                                        policyState(conversation),
                                        actorIsRequester,
                                        actorMessagesSoFar,
                                        request.messageType().toJson())));
        if (decision.denied()) {
            throw decision.reason() == InteractionReason.BLOCKED
                    ? new AppException(ApiErrorCode.CONVERSATION_NOT_FOUND)
                    : new AppException(ApiErrorCode.MESSAGE_REQUEST_AWAITING_ACCEPTANCE);
        }
        return decision;
    }

    private static ConversationRequestState policyState(Conversation conversation) {
        return switch (conversation.getRequestStatus()) {
            case ACCEPTED -> ConversationRequestState.ACCEPTED;
            case PENDING ->
                    conversation.isRequestHidden()
                            ? ConversationRequestState.PENDING_HIDDEN
                            : ConversationRequestState.PENDING;
            case DECLINED -> ConversationRequestState.DECLINED;
        };
    }

    // The recipient answering a request accepts it, and the requester's message that the policy
    // routed to hidden requests files the request there. Both ride the transaction that stores the
    // message, so a refused or failed send leaves the request exactly as it was.
    private void applyRequestOutcome(
            Conversation conversation, UUID actorId, InteractionDecision decision) {
        if (conversation.getRequestStatus() == ConversationRequestStatus.ACCEPTED) {
            return;
        }
        if (actorId.equals(conversation.getRequestRecipientId())) {
            conversation.setRequestStatus(ConversationRequestStatus.ACCEPTED);
            conversation.setRequestHidden(false);
            conversation.setRequestResolvedAt(OffsetDateTime.now(ZoneOffset.UTC));
            conversationRepository.save(conversation);
            // The recipient answering is a decision like an explicit accept, so analytics hears it
            // the same way.
            outboxService.enqueue(
                    MessageEventTypes.MESSAGE_REQUEST_RESOLVED_V1,
                    MessageEventTypes.MESSAGE_REQUEST_RESOLVED_V1,
                    AGGREGATE_TYPE,
                    conversation.getId(),
                    actorId,
                    Map.of(
                            "conversationId",
                            conversation.getId().toString(),
                            "resolution",
                            "accepted"));
        } else if (decision.outcome() == InteractionOutcome.ROUTE_TO_HIDDEN_REQUESTS
                && !conversation.isRequestHidden()) {
            conversation.setRequestHidden(true);
            conversationRepository.save(conversation);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<MessageResponse> listHistory(
            UUID actorId, UUID conversationId, String cursor, int limit) {
        fetchConversation(conversationId);
        requireActiveParticipant(conversationId, actorId);
        int pageSize = normalizeLimit(limit);
        HistoryCursor decoded = decodeCursor(cursor);
        PageRequest page = PageRequest.of(0, pageSize + 1);
        List<Message> messages =
                decoded == null
                        ? messageRepository.findFirstByConversation(conversationId, page)
                        : messageRepository.findByConversationBefore(
                                conversationId, decoded.createdAt(), decoded.id(), page);
        return toPage(messages, pageSize, cursor, actorId);
    }

    @Override
    @Transactional
    public void deleteMessage(UUID actorId, UUID conversationId, UUID messageId) {
        Message message =
                messageRepository
                        .findByIdAndConversationId(messageId, conversationId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.MESSAGE_NOT_FOUND));
        if (message.getSenderId() == null || !message.getSenderId().equals(actorId)) {
            throw new AppException(ApiErrorCode.MESSAGE_FORBIDDEN);
        }
        message.setDeleted(true);
        message.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        message.setContent(null);
        messageRepository.save(message);

        Map<String, Object> data = new HashMap<>();
        data.put("conversationId", conversationId.toString());
        data.put("messageId", messageId.toString());
        data.put("deletedAt", message.getDeletedAt().toString());
        outboxService.enqueue(
                MessageEventTypes.MESSAGE_DELETED_V1,
                MessageEventTypes.MESSAGE_DELETED_V1,
                AGGREGATE_TYPE,
                messageId,
                actorId,
                data);
    }

    @Override
    @Transactional
    public void markRead(UUID actorId, UUID conversationId) {
        fetchConversation(conversationId);
        ConversationParticipant participant =
                participantRepository
                        .findByIdConversationIdAndIdUserId(conversationId, actorId)
                        .filter(p -> p.getLeftAt() == null)
                        .orElseThrow(() -> new AppException(ApiErrorCode.CONVERSATION_FORBIDDEN));
        participant.setLastReadAt(OffsetDateTime.now(ZoneOffset.UTC));
        participant.setManuallyUnread(false);
        participantRepository.save(participant);
    }

    @Override
    @Transactional
    public void markUnread(UUID actorId, UUID conversationId) {
        fetchConversation(conversationId);
        ConversationParticipant participant =
                participantRepository
                        .findByIdConversationIdAndIdUserId(conversationId, actorId)
                        .filter(p -> p.getLeftAt() == null)
                        .orElseThrow(() -> new AppException(ApiErrorCode.CONVERSATION_FORBIDDEN));
        // last_read_at is left untouched: it drives the real unread count, and clearing it finds
        // nothing to count when the actor sent the conversation's own newest messages. This flag is
        // the independent, purely visual marker "mark as unread" actually means.
        participant.setManuallyUnread(true);
        participantRepository.save(participant);
    }

    @Override
    @Transactional(readOnly = true)
    public long getUnreadCount(UUID actorId) {
        return messageRepository.countTotalUnreadForUser(actorId);
    }

    @Override
    @Transactional(readOnly = true)
    public long getRequestCount(UUID actorId) {
        return messageRepository.countPendingRequests(actorId);
    }

    private void validatePayload(UUID actorId, UUID conversationId, SendMessageRequest request) {
        boolean hasMedia = request.mediaAssetId() != null;
        boolean hasPost = request.sharedPostId() != null;
        boolean hasStory = request.sharedStoryId() != null;
        switch (request.messageType()) {
            case TEXT -> {
                if (request.content() == null || request.content().isBlank()) {
                    throw invalidPayload("Text message requires content");
                }
                if (hasMedia || hasPost || hasStory) {
                    throw invalidPayload("Text message must not reference media or shared content");
                }
            }
            case IMAGE, VIDEO -> {
                if (!hasMedia) {
                    throw invalidPayload("Media message requires mediaAssetId");
                }
                if (hasPost || hasStory) {
                    throw invalidPayload("Media message must not reference shared content");
                }
                if (!mediaAssetRepository.existsByIdAndUserId(request.mediaAssetId(), actorId)) {
                    throw invalidPayload("Media asset not found");
                }
            }
            case POST_SHARE -> {
                if (!hasPost) {
                    throw invalidPayload("Post share requires sharedPostId");
                }
                if (hasMedia || hasStory) {
                    throw invalidPayload("Post share must not reference media or a shared story");
                }
                if (!postRepository.existsByIdAndDeletedAtIsNullAndStatus(
                        request.sharedPostId(), PostStatus.PUBLISHED)) {
                    throw invalidPayload("Shared post not found");
                }
            }
            case STORY_SHARE -> {
                if (!hasStory) {
                    throw invalidPayload("Story share requires sharedStoryId");
                }
                if (hasMedia || hasPost) {
                    throw invalidPayload("Story share must not reference media or a shared post");
                }
                if (!storyRepository.existsByIdAndDeletedAtIsNullAndExpiresAtAfter(
                        request.sharedStoryId(), OffsetDateTime.now(ZoneOffset.UTC))) {
                    throw invalidPayload("Shared story not found");
                }
            }
        }
        if (request.replyToId() != null
                && messageRepository
                        .findByIdAndConversationId(request.replyToId(), conversationId)
                        .isEmpty()) {
            throw invalidPayload("Reply target must belong to the same conversation");
        }
    }

    private static AppException invalidPayload(String message) {
        return new AppException(ApiErrorCode.MESSAGE_INVALID_PAYLOAD, message);
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

    // Re-reads the existing idempotency row to replay the cached response or reject a key reuse
    // with a different payload. A null body means a concurrent in-flight send reserved the key but
    // has not committed its response yet, which is treated as a conflict.
    private MessageResponse replayOrConflict(
            UUID actorId, String idempotencyKey, String requestHash) {
        MessageWriteIdempotency row =
                idempotencyRepository
                        .findByUserIdAndIdempotencyKey(actorId, idempotencyKey)
                        .orElseThrow(
                                () -> new AppException(ApiErrorCode.MESSAGE_IDEMPOTENCY_CONFLICT));
        return replayOrConflict(row, requestHash);
    }

    private MessageResponse replayOrConflict(MessageWriteIdempotency row, String requestHash) {
        if (!requestHash.equals(row.getRequestHash()) || row.getResponseBody() == null) {
            throw new AppException(ApiErrorCode.MESSAGE_IDEMPOTENCY_CONFLICT);
        }
        try {
            return objectMapper.readValue(row.getResponseBody(), MessageResponse.class);
        } catch (Exception e) {
            throw new AppException(ApiErrorCode.INTERNAL_ERROR, "Failed to read cached response");
        }
    }

    // The audience and folder ride the event so the live consumer can tell each recipient's inbox
    // which folder the conversation landed in without a lookup. They are decided here, after the
    // request outcome and the reactivation above, and the consumer strips both before the event
    // reaches the thread topic, which the requester is subscribed to.
    private void enqueueSent(
            Conversation conversation, Message saved, UUID actorId, MessageResponse response) {
        UUID conversationId = conversation.getId();
        Map<String, Object> data = new HashMap<>();
        data.put(
                "inboxRecipientIds",
                inboxAudience.recipients(conversation, actorId).stream()
                        .map(UUID::toString)
                        .toList());
        data.put("folder", MessageInboxAudience.folderOf(conversation));
        data.put("conversationId", conversationId.toString());
        data.put("messageId", saved.getId().toString());
        data.put("senderId", actorId.toString());
        data.put("messageType", saved.getMessageType().toJson());
        data.put("sentAt", saved.getCreatedAt().toString());
        data.put("message", response);
        outboxService.enqueue(
                MessageEventTypes.MESSAGE_SENT_V1,
                MessageEventTypes.MESSAGE_SENT_V1,
                AGGREGATE_TYPE,
                saved.getId(),
                actorId,
                data);
        enqueuePostShared(saved, actorId);
    }

    // Sharing a post is implemented as sending it inside a conversation, so this module is where
    // the action happens and therefore where the event belongs. It is a separate event from
    // message.sent.v1 because its aggregate is the shared post, not the message, and the
    // recommender binds to it without having to parse every message that crosses the broker.
    private void enqueuePostShared(Message saved, UUID actorId) {
        if (saved.getMessageType() != MessageType.POST_SHARE || saved.getSharedPostId() == null) {
            return;
        }
        outboxService.enqueue(
                MessageEventTypes.POST_SHARED_V1,
                MessageEventTypes.POST_SHARED_V1,
                "post",
                saved.getSharedPostId(),
                actorId,
                Map.of(
                        "postId", saved.getSharedPostId().toString(),
                        "messageId", saved.getId().toString()));
    }

    /**
     * Resolves the attachment for one message, or null when it carries none.
     *
     * @param message the message whose asset to resolve
     * @return the resolved media, or null
     */
    private MessageMediaResponse resolveMedia(Message message) {
        if (message.getMediaAssetId() == null) {
            return null;
        }
        return mediaAssetRepository
                .findById(message.getMediaAssetId())
                .map(mapper::toMediaResponse)
                .orElse(null);
    }

    /**
     * Looks up a message's resolved media, tolerating a message that has none.
     *
     * <p>{@code Map.of()} throws on a null key, and a text message carries a null asset id, so the
     * absent case is checked before the lookup rather than left to the map.
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

    /**
     * Resolves attachments for a page of messages in one query.
     *
     * <p>Batched deliberately: resolving per row would issue one media_assets query for every
     * message on a history page, which is the N+1 the post module's assembler already avoids.
     *
     * @param messages the page being rendered
     * @return resolved media keyed by asset id; empty when the page carries no attachments
     */
    private Map<UUID, MessageMediaResponse> resolveMedia(List<Message> messages) {
        Set<UUID> assetIds =
                messages.stream()
                        .map(Message::getMediaAssetId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        if (assetIds.isEmpty()) {
            return Map.of();
        }
        return mediaAssetRepository.findAllById(assetIds).stream()
                .collect(Collectors.toMap(MediaAsset::getId, mapper::toMediaResponse));
    }

    // One statement per page. The viewer is passed to the query so a reaction from a user blocked
    // in either direction is left out, and a message that is no longer shown contributes none.
    private Map<UUID, List<MessageReactionResponse>> resolveReactions(
            List<Message> page, UUID viewerId) {
        if (page.isEmpty()) {
            return Map.of();
        }
        List<UUID> messageIds = page.stream().map(Message::getId).toList();
        return reactionRepository.findVisibleForMessages(messageIds, viewerId).stream()
                .collect(
                        Collectors.groupingBy(
                                MessageReaction::getMessageId,
                                Collectors.mapping(
                                        mapper::toReactionResponse, Collectors.toList())));
    }

    // Keyset pagination over limit+1 rows: hasNextPage is decided by the pre-trim size, then the
    // extra probe row is dropped.
    private CursorPageResponse<MessageResponse> toPage(
            List<Message> rows, int pageSize, String cursor, UUID viewerId) {
        boolean hasNextPage = rows.size() > pageSize;
        List<Message> page = hasNextPage ? rows.subList(0, pageSize) : rows;
        Map<UUID, MessageMediaResponse> media = resolveMedia(page);
        Map<UUID, Message> parents = ReplyParents.load(messageRepository, page);
        Map<UUID, List<MessageReactionResponse>> reactions = resolveReactions(page, viewerId);
        List<MessageResponse> content =
                page.stream()
                        .map(
                                m ->
                                        mapper.toMessageResponse(
                                                m,
                                                mediaFor(media, m),
                                                parents,
                                                reactions.get(m.getId())))
                        .toList();
        String startCursor = page.isEmpty() ? null : encodeCursor(page.get(0));
        String endCursor = page.isEmpty() ? null : encodeCursor(page.get(page.size() - 1));
        return CursorPageResponse.<MessageResponse>builder()
                .content(content)
                .pageInfo(
                        CursorPageResponse.PageInfo.builder()
                                .hasNextPage(hasNextPage)
                                .hasPreviousPage(cursor != null)
                                .startCursor(startCursor)
                                .endCursor(endCursor)
                                .build())
                .build();
    }

    private int normalizeLimit(int limit) {
        return limit > MAX_PAGE_SIZE ? MAX_PAGE_SIZE : (limit < 1 ? DEFAULT_PAGE_SIZE : limit);
    }

    // The cursor carries the (created_at, id) pair of the last message on the page, because two
    // messages can share a timestamp and the timestamp alone would skip or repeat the tie group.
    private String encodeCursor(Message message) {
        if (message.getCreatedAt() == null) {
            return null;
        }
        return Base64.getEncoder()
                .encodeToString(
                        (message.getCreatedAt().toString() + CURSOR_SEPARATOR + message.getId())
                                .getBytes(StandardCharsets.UTF_8));
    }

    // A cursor minted before the id joined it carries only the timestamp. The nil UUID sorts below
    // every id, so the tie-break clause matches nothing and the page resumes strictly before the
    // timestamp, the behaviour that cursor was issued under.
    private HistoryCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String[] parts =
                    new String(Base64.getDecoder().decode(cursor), StandardCharsets.UTF_8)
                            .split(Pattern.quote(String.valueOf(CURSOR_SEPARATOR)), -1);
            OffsetDateTime createdAt = OffsetDateTime.parse(parts[0]);
            if (parts.length == 1) {
                return new HistoryCursor(createdAt, new UUID(0L, 0L));
            }
            if (parts.length != 2) {
                throw new IllegalArgumentException("malformed cursor");
            }
            return new HistoryCursor(createdAt, UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw new AppException(ApiErrorCode.BAD_REQUEST, "Invalid cursor format");
        }
    }

    private record HistoryCursor(OffsetDateTime createdAt, UUID id) {}

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
