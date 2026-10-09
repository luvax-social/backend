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
import java.util.Set;
import java.util.UUID;
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
import com.app.modules.message.dto.response.MessageResponse;
import com.app.modules.message.entity.Conversation;
import com.app.modules.message.entity.ConversationParticipant;
import com.app.modules.message.entity.Message;
import com.app.modules.message.entity.MessageWriteIdempotency;
import com.app.modules.message.enums.MessageType;
import com.app.modules.message.mapper.MessageMapper;
import com.app.modules.message.messaging.MessageEventTypes;
import com.app.modules.message.repository.ConversationParticipantRepository;
import com.app.modules.message.repository.ConversationRepository;
import com.app.modules.message.repository.MessageIdempotencyRepository;
import com.app.modules.message.repository.MessageMediaAssetRepository;
import com.app.modules.message.repository.MessagePostRepository;
import com.app.modules.message.repository.MessageRepository;
import com.app.modules.message.repository.MessageStoryRepository;
import com.app.modules.message.service.MessageService;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.social.service.SocialService;

import tools.jackson.databind.ObjectMapper;

@Service
public class MessageServiceImpl implements MessageService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String AGGREGATE_TYPE = "message";

    private final MessageRepository messageRepository;
    private final MessageIdempotencyRepository idempotencyRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationParticipantRepository participantRepository;
    private final MessageMediaAssetRepository mediaAssetRepository;
    private final MessagePostRepository postRepository;
    private final MessageStoryRepository storyRepository;
    private final SocialService socialService;
    private final MessageMapper mapper;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    public MessageServiceImpl(
            MessageRepository messageRepository,
            MessageIdempotencyRepository idempotencyRepository,
            ConversationRepository conversationRepository,
            ConversationParticipantRepository participantRepository,
            MessageMediaAssetRepository mediaAssetRepository,
            MessagePostRepository postRepository,
            MessageStoryRepository storyRepository,
            SocialService socialService,
            MessageMapper mapper,
            OutboxService outboxService,
            ObjectMapper objectMapper) {
        this.messageRepository = messageRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.conversationRepository = conversationRepository;
        this.participantRepository = participantRepository;
        this.mediaAssetRepository = mediaAssetRepository;
        this.postRepository = postRepository;
        this.storyRepository = storyRepository;
        this.socialService = socialService;
        this.mapper = mapper;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public MessageResponse sendMessage(
            UUID actorId, UUID conversationId, SendMessageRequest request, String idempotencyKey) {
        Conversation conversation = fetchConversation(conversationId);
        requireActiveParticipant(conversationId, actorId);
        assertNotBlockedForDirectMessage(conversation, actorId);
        validatePayload(actorId, conversationId, request);

        // Reserve the idempotency key in the same transaction via ON CONFLICT DO NOTHING. A
        // duplicate key returns a clean replay/conflict instead of poisoning the transaction with a
        // constraint violation, and a rolled-back send frees the key.
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
        MessageResponse response = mapper.toMessageResponse(saved, resolveMedia(saved));

        if (idempotencyKey != null) {
            idempotencyRepository.updateResponseBody(
                    actorId, idempotencyKey, objectMapper.writeValueAsString(response));
        }

        reactivateOtherParticipants(conversationId, actorId);
        enqueueSent(conversationId, saved, actorId, response);
        return response;
    }

    /**
     * Un-hides the conversation for any other participant who had deleted it.
     *
     * <p>Deleting a conversation only sets {@code left_at} for the deleting user's own row - it
     * never touches the conversation or the other participant's state, matching the DATA_RULES note
     * that hiding is per-user. A new message is the signal that revives it for them, the same
     * behaviour the reference chat apps this feature was modelled on use.
     */
    private void reactivateOtherParticipants(UUID conversationId, UUID actorId) {
        for (ConversationParticipant participant :
                participantRepository.findByIdConversationIdOrderByJoinedAtAsc(conversationId)) {
            if (participant.getLeftAt() != null
                    && !participant.getId().getUserId().equals(actorId)) {
                participant.setLeftAt(null);
                participantRepository.save(participant);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<MessageResponse> listHistory(
            UUID actorId, UUID conversationId, String cursor, int limit) {
        fetchConversation(conversationId);
        requireActiveParticipant(conversationId, actorId);
        int pageSize = normalizeLimit(limit);
        OffsetDateTime cursorTime = decodeCursor(cursor);
        PageRequest page = PageRequest.of(0, pageSize + 1);
        List<Message> messages =
                cursorTime == null
                        ? messageRepository.findFirstByConversation(conversationId, page)
                        : messageRepository.findByConversationBefore(
                                conversationId, cursorTime, page);
        return toPage(messages, pageSize, cursor);
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

    private void assertNotBlockedForDirectMessage(Conversation conversation, UUID actorId) {
        UUID otherId =
                participantRepository
                        .findByIdConversationIdOrderByJoinedAtAsc(conversation.getId())
                        .stream()
                        .filter(
                                p ->
                                        p.getLeftAt() == null
                                                && !p.getId().getUserId().equals(actorId))
                        .map(p -> p.getId().getUserId())
                        .findFirst()
                        .orElse(null);
        if (otherId != null && socialService.isBlockedBetween(actorId, otherId)) {
            // Stealth block model: matches ConversationServiceImpl.assertNotBlocked - a block in
            // either direction must not be distinguishable from the conversation itself vanishing.
            throw new AppException(ApiErrorCode.CONVERSATION_NOT_FOUND);
        }
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
        if (!requestHash.equals(row.getRequestHash()) || row.getResponseBody() == null) {
            throw new AppException(ApiErrorCode.MESSAGE_IDEMPOTENCY_CONFLICT);
        }
        try {
            return objectMapper.readValue(row.getResponseBody(), MessageResponse.class);
        } catch (Exception e) {
            throw new AppException(ApiErrorCode.INTERNAL_ERROR, "Failed to read cached response");
        }
    }

    private void enqueueSent(
            UUID conversationId, Message saved, UUID actorId, MessageResponse response) {
        Map<String, Object> data = new HashMap<>();
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

    // Keyset pagination over limit+1 rows: hasNextPage is decided by the pre-trim size, then the
    // extra probe row is dropped.
    private CursorPageResponse<MessageResponse> toPage(
            List<Message> rows, int pageSize, String cursor) {
        boolean hasNextPage = rows.size() > pageSize;
        List<Message> page = hasNextPage ? rows.subList(0, pageSize) : rows;
        Map<UUID, MessageMediaResponse> media = resolveMedia(page);
        List<MessageResponse> content =
                page.stream().map(m -> mapper.toMessageResponse(m, mediaFor(media, m))).toList();
        String startCursor = page.isEmpty() ? null : encodeCursor(page.get(0).getCreatedAt());
        String endCursor =
                page.isEmpty() ? null : encodeCursor(page.get(page.size() - 1).getCreatedAt());
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

    private String encodeCursor(OffsetDateTime time) {
        if (time == null) {
            return null;
        }
        return Base64.getEncoder().encodeToString(time.toString().getBytes(StandardCharsets.UTF_8));
    }

    private OffsetDateTime decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(
                    new String(Base64.getDecoder().decode(cursor), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new AppException(ApiErrorCode.BAD_REQUEST, "Invalid cursor format");
        }
    }

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
