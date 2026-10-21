package com.app.modules.message.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.common.response.CursorPageResponse;
import com.app.modules.message.dto.request.SendMessageRequest;
import com.app.modules.message.dto.response.MessageMediaResponse;
import com.app.modules.message.dto.response.MessageResponse;
import com.app.modules.message.entity.Conversation;
import com.app.modules.message.entity.ConversationParticipant;
import com.app.modules.message.entity.ConversationParticipantId;
import com.app.modules.message.entity.Message;
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
import com.app.modules.post.enums.PostStatus;
import com.app.modules.social.policy.ConversationRequestState;
import com.app.modules.social.policy.InteractionDecision;
import com.app.modules.social.policy.InteractionOutcome;
import com.app.modules.social.policy.InteractionPolicyService;
import com.app.modules.social.policy.InteractionReason;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class MessageServiceImplTest {

    @Mock private MessageRepository messageRepository;
    @Mock private MessageIdempotencyRepository idempotencyRepository;
    @Mock private ConversationRepository conversationRepository;
    @Mock private ConversationParticipantRepository participantRepository;
    @Mock private MessageMediaAssetRepository mediaAssetRepository;
    @Mock private MessagePostRepository postRepository;
    @Mock private MessageStoryRepository storyRepository;
    @Mock private InteractionPolicyService interactionPolicy;
    @Mock private MessageMapper mapper;
    @Mock private OutboxService outboxService;
    @Mock private ObjectMapper objectMapper;
    @Mock private MessageReactionRepository reactionRepository;
    @Mock private MessageInboxAudience inboxAudience;

    private MessageServiceImpl service;

    @BeforeEach
    void setUp() {
        service =
                new MessageServiceImpl(
                        messageRepository,
                        idempotencyRepository,
                        conversationRepository,
                        participantRepository,
                        mediaAssetRepository,
                        postRepository,
                        storyRepository,
                        interactionPolicy,
                        mapper,
                        outboxService,
                        objectMapper,
                        reactionRepository,
                        inboxAudience);
        lenient()
                .when(interactionPolicy.evaluate(any()))
                .thenReturn(
                        new InteractionDecision(InteractionOutcome.ALLOW, InteractionReason.NONE));
        org.mockito.stubbing.Answer<MessageResponse> echo =
                inv -> {
                    Message m = inv.getArgument(0);
                    MessageMediaResponse media = inv.getArgument(1);
                    return new MessageResponse(
                            m.getId(),
                            m.getConversationId(),
                            m.getSenderId(),
                            m.getMessageType(),
                            m.getContent(),
                            m.getMediaAssetId(),
                            media,
                            m.getSharedPostId(),
                            m.getSharedStoryId(),
                            m.getReplyToId(),
                            null,
                            m.isDeleted(),
                            m.getDeletedAt(),
                            m.getCreatedAt(),
                            null);
                };
        lenient().when(mapper.toMessageResponse(any(), any(), any())).thenAnswer(echo);
        lenient().when(mapper.toMessageResponse(any(), any(), any(), any())).thenAnswer(echo);
    }

    @Test
    void sendMessage_conversationNotFound_throwsConversationNotFound() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.lockById(conversationId)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () -> service.sendMessage(actorId, conversationId, textRequest("hi"), null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    void sendMessage_nonParticipant_throwsConversationForbidden() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.lockById(conversationId))
                .thenReturn(Optional.of(directConversation(conversationId)));
        when(participantRepository.existsByIdConversationIdAndIdUserIdAndLeftAtIsNull(
                        conversationId, actorId))
                .thenReturn(false);

        assertThatThrownBy(
                        () -> service.sendMessage(actorId, conversationId, textRequest("hi"), null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.CONVERSATION_FORBIDDEN);
    }

    @Test
    void sendMessage_directConversationBlocked_throwsConversationNotFound() {
        UUID actorId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.lockById(conversationId))
                .thenReturn(Optional.of(directConversation(conversationId)));
        when(participantRepository.existsByIdConversationIdAndIdUserIdAndLeftAtIsNull(
                        conversationId, actorId))
                .thenReturn(true);
        when(participantRepository.findByIdConversationIdOrderByJoinedAtAsc(conversationId))
                .thenReturn(
                        List.of(
                                participant(conversationId, actorId, null),
                                participant(conversationId, otherId, null)));
        when(interactionPolicy.evaluate(any()))
                .thenReturn(
                        new InteractionDecision(
                                InteractionOutcome.DENY, InteractionReason.BLOCKED));

        assertThatThrownBy(
                        () -> service.sendMessage(actorId, conversationId, textRequest("hi"), null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    void sendMessage_textWithoutContent_throwsInvalidPayload() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        stubActiveGroupParticipant(conversationId, actorId);

        assertThatThrownBy(
                        () -> service.sendMessage(actorId, conversationId, textRequest(null), null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.MESSAGE_INVALID_PAYLOAD);
    }

    @Test
    void sendMessage_textWithMediaReference_throwsInvalidPayload() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        stubActiveGroupParticipant(conversationId, actorId);
        SendMessageRequest request =
                new SendMessageRequest(MessageType.TEXT, "hi", UUID.randomUUID(), null, null, null);

        assertThatThrownBy(() -> service.sendMessage(actorId, conversationId, request, null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.MESSAGE_INVALID_PAYLOAD);
    }

    @Test
    void sendMessage_imageWithoutMediaAssetId_throwsInvalidPayload() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        stubActiveGroupParticipant(conversationId, actorId);
        SendMessageRequest request =
                new SendMessageRequest(MessageType.IMAGE, null, null, null, null, null);

        assertThatThrownBy(() -> service.sendMessage(actorId, conversationId, request, null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.MESSAGE_INVALID_PAYLOAD);
    }

    @Test
    void sendMessage_imageWithMissingMediaAsset_throwsInvalidPayload() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID mediaAssetId = UUID.randomUUID();
        stubActiveGroupParticipant(conversationId, actorId);
        when(mediaAssetRepository.existsByIdAndUserId(mediaAssetId, actorId)).thenReturn(false);
        SendMessageRequest request =
                new SendMessageRequest(MessageType.IMAGE, null, mediaAssetId, null, null, null);

        assertThatThrownBy(() -> service.sendMessage(actorId, conversationId, request, null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.MESSAGE_INVALID_PAYLOAD);
    }

    @Test
    void sendMessage_postShareWithMissingPost_throwsInvalidPayload() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        stubActiveGroupParticipant(conversationId, actorId);
        when(postRepository.existsByIdAndDeletedAtIsNullAndStatus(postId, PostStatus.PUBLISHED))
                .thenReturn(false);
        SendMessageRequest request =
                new SendMessageRequest(MessageType.POST_SHARE, null, null, postId, null, null);

        assertThatThrownBy(() -> service.sendMessage(actorId, conversationId, request, null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.MESSAGE_INVALID_PAYLOAD);
        verify(postRepository).existsByIdAndDeletedAtIsNullAndStatus(postId, PostStatus.PUBLISHED);
    }

    @Test
    void sendMessage_storyShareWithMissingStory_throwsInvalidPayload() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID storyId = UUID.randomUUID();
        stubActiveGroupParticipant(conversationId, actorId);
        when(storyRepository.existsByIdAndDeletedAtIsNullAndExpiresAtAfter(eq(storyId), any()))
                .thenReturn(false);
        SendMessageRequest request =
                new SendMessageRequest(MessageType.STORY_SHARE, null, null, null, storyId, null);

        assertThatThrownBy(() -> service.sendMessage(actorId, conversationId, request, null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.MESSAGE_INVALID_PAYLOAD);
    }

    @Test
    void sendMessage_replyToDifferentConversation_throwsInvalidPayload() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID replyToId = UUID.randomUUID();
        stubActiveGroupParticipant(conversationId, actorId);
        when(messageRepository.findByIdAndConversationId(replyToId, conversationId))
                .thenReturn(Optional.empty());
        SendMessageRequest request =
                new SendMessageRequest(MessageType.TEXT, "hi", null, null, null, replyToId);

        assertThatThrownBy(() -> service.sendMessage(actorId, conversationId, request, null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.MESSAGE_INVALID_PAYLOAD);
    }

    @Test
    void sendMessage_success_persistsAndEnqueuesOutbox() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        stubActiveGroupParticipant(conversationId, actorId);
        Message saved =
                Message.builder()
                        .id(messageId)
                        .conversationId(conversationId)
                        .senderId(actorId)
                        .messageType(MessageType.TEXT)
                        .content("hello")
                        .createdAt(createdAt)
                        .build();
        when(messageRepository.saveAndFlush(any())).thenReturn(saved);

        MessageResponse response =
                service.sendMessage(actorId, conversationId, textRequest("hello"), null);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(messageId);
        // sentAt must be present: the PR#115 consumer reads it to timestamp the notification it
        // creates from this event.
        verify(outboxService)
                .enqueue(
                        eq(MessageEventTypes.MESSAGE_SENT_V1),
                        eq(MessageEventTypes.MESSAGE_SENT_V1),
                        eq("message"),
                        eq(messageId),
                        eq(actorId),
                        argThat(data -> createdAt.toString().equals(data.get("sentAt"))));
        verify(idempotencyRepository, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void sendMessage_success_carriesTheInboxAudienceAndFolderOnTheEvent() {
        UUID actorId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        stubActiveGroupParticipant(conversationId, actorId);
        when(inboxAudience.recipients(any(), eq(actorId))).thenReturn(List.of(otherId));
        Message saved =
                Message.builder()
                        .id(messageId)
                        .conversationId(conversationId)
                        .senderId(actorId)
                        .messageType(MessageType.TEXT)
                        .content("hello")
                        .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                        .build();
        when(messageRepository.saveAndFlush(any())).thenReturn(saved);

        service.sendMessage(actorId, conversationId, textRequest("hello"), null);

        verify(outboxService)
                .enqueue(
                        eq(MessageEventTypes.MESSAGE_SENT_V1),
                        eq(MessageEventTypes.MESSAGE_SENT_V1),
                        eq("message"),
                        eq(messageId),
                        eq(actorId),
                        argThat(
                                data ->
                                        List.of(otherId.toString())
                                                        .equals(data.get("inboxRecipientIds"))
                                                && "primary".equals(data.get("folder"))));
    }

    @Test
    void sendMessage_idempotencyReplay_returnsCachedResponse() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        stubActiveGroupParticipant(conversationId, actorId);
        String hash = sha256(conversationId + "|TEXT|hello|null|null|null|null");
        MessageWriteIdempotency row =
                MessageWriteIdempotency.builder()
                        .userId(actorId)
                        .idempotencyKey("key-1")
                        .requestHash(hash)
                        .responseBody("cached-json")
                        .build();
        when(idempotencyRepository.findByUserIdAndIdempotencyKey(actorId, "key-1"))
                .thenReturn(Optional.of(row));
        MessageResponse cached =
                new MessageResponse(
                        UUID.randomUUID(),
                        conversationId,
                        actorId,
                        MessageType.TEXT,
                        "hello",
                        null,
                        // mediaAssetId, then the resolved media, both absent on a text message
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        null,
                        OffsetDateTime.now(ZoneOffset.UTC),
                        null);
        when(objectMapper.readValue("cached-json", MessageResponse.class)).thenReturn(cached);

        MessageResponse response =
                service.sendMessage(actorId, conversationId, textRequest("hello"), "key-1");

        assertThat(response).isSameAs(cached);
        verify(messageRepository, never()).save(any());
        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void sendMessage_idempotencyConflict_throwsConflict() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        stubActiveGroupParticipant(conversationId, actorId);
        MessageWriteIdempotency row =
                MessageWriteIdempotency.builder()
                        .userId(actorId)
                        .idempotencyKey("key-2")
                        .requestHash("a-different-hash")
                        .responseBody("cached-json")
                        .build();
        when(idempotencyRepository.findByUserIdAndIdempotencyKey(actorId, "key-2"))
                .thenReturn(Optional.of(row));

        assertThatThrownBy(
                        () ->
                                service.sendMessage(
                                        actorId, conversationId, textRequest("hello"), "key-2"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.MESSAGE_IDEMPOTENCY_CONFLICT);
    }

    @Test
    void listHistory_nonParticipant_throwsConversationForbidden() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(directConversation(conversationId)));
        when(participantRepository.existsByIdConversationIdAndIdUserIdAndLeftAtIsNull(
                        conversationId, actorId))
                .thenReturn(false);

        assertThatThrownBy(() -> service.listHistory(actorId, conversationId, null, 20))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.CONVERSATION_FORBIDDEN);
    }

    @Test
    void listHistory_firstPage_returnsMessagesIncludingTombstones() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(directConversation(conversationId)));
        when(participantRepository.existsByIdConversationIdAndIdUserIdAndLeftAtIsNull(
                        conversationId, actorId))
                .thenReturn(true);
        Message tombstone =
                Message.builder()
                        .id(UUID.randomUUID())
                        .conversationId(conversationId)
                        .messageType(MessageType.TEXT)
                        .isDeleted(true)
                        .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                        .build();
        when(messageRepository.findFirstByConversation(eq(conversationId), any(Pageable.class)))
                .thenReturn(List.of(tombstone));

        CursorPageResponse<MessageResponse> result =
                service.listHistory(actorId, conversationId, null, 20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).isDeleted()).isTrue();
    }

    @Test
    void listHistory_pageCursor_carriesTimestampAndIdOfTheBoundaryMessage() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        openHistory(actorId, conversationId);
        OffsetDateTime sharedInstant = OffsetDateTime.parse("2026-01-01T12:00:00.123456Z");
        Message first = historyMessage(conversationId, sharedInstant);
        Message second = historyMessage(conversationId, sharedInstant);
        Message probe = historyMessage(conversationId, sharedInstant);
        when(messageRepository.findFirstByConversation(eq(conversationId), any(Pageable.class)))
                .thenReturn(List.of(first, second, probe));

        CursorPageResponse<MessageResponse> page =
                service.listHistory(actorId, conversationId, null, 2);
        service.listHistory(actorId, conversationId, page.getPageInfo().getEndCursor(), 2);

        verify(messageRepository)
                .findByConversationBefore(
                        eq(conversationId),
                        eq(sharedInstant),
                        eq(second.getId()),
                        any(Pageable.class));
    }

    @Test
    void listHistory_cursorWithoutId_resumesStrictlyBeforeTheTimestamp() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        openHistory(actorId, conversationId);
        OffsetDateTime instant = OffsetDateTime.parse("2026-01-01T12:00:00.123456Z");
        String legacyCursor =
                Base64.getEncoder()
                        .encodeToString(instant.toString().getBytes(StandardCharsets.UTF_8));

        service.listHistory(actorId, conversationId, legacyCursor, 20);

        // The nil UUID sorts below every id, so the tie-break clause matches nothing and the page
        // starts strictly before the timestamp as the old cursor promised.
        verify(messageRepository)
                .findByConversationBefore(
                        eq(conversationId), eq(instant), eq(new UUID(0L, 0L)), any(Pageable.class));
    }

    @Test
    void listHistory_malformedCursor_throwsBadRequest() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        openHistory(actorId, conversationId);
        String malformed =
                Base64.getEncoder()
                        .encodeToString(
                                "2026-01-01T12:00:00Z|not-a-uuid".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.listHistory(actorId, conversationId, malformed, 20))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.BAD_REQUEST);
    }

    private void openHistory(UUID actorId, UUID conversationId) {
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(directConversation(conversationId)));
        when(participantRepository.existsByIdConversationIdAndIdUserIdAndLeftAtIsNull(
                        conversationId, actorId))
                .thenReturn(true);
    }

    private static Message historyMessage(UUID conversationId, OffsetDateTime createdAt) {
        return Message.builder()
                .id(UUID.randomUUID())
                .conversationId(conversationId)
                .messageType(MessageType.TEXT)
                .content("hello")
                .createdAt(createdAt)
                .build();
    }

    @Test
    void deleteMessage_bySender_softDeletesAndEnqueuesOutbox() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        Message message =
                Message.builder()
                        .id(messageId)
                        .conversationId(conversationId)
                        .senderId(actorId)
                        .messageType(MessageType.TEXT)
                        .content("to be deleted")
                        .build();
        when(messageRepository.findByIdAndConversationId(messageId, conversationId))
                .thenReturn(Optional.of(message));

        service.deleteMessage(actorId, conversationId, messageId);

        assertThat(message.isDeleted()).isTrue();
        assertThat(message.getDeletedAt()).isNotNull();
        assertThat(message.getContent()).isNull();
        verify(messageRepository).save(message);
        // deletedAt must be present: the PR#116 consumer reads it to timestamp the real-time
        // WebSocket delete event it pushes to clients.
        verify(outboxService)
                .enqueue(
                        eq(MessageEventTypes.MESSAGE_DELETED_V1),
                        eq(MessageEventTypes.MESSAGE_DELETED_V1),
                        eq("message"),
                        eq(messageId),
                        eq(actorId),
                        argThat(
                                data ->
                                        message.getDeletedAt()
                                                .toString()
                                                .equals(data.get("deletedAt"))));
    }

    @Test
    void deleteMessage_byNonSender_throwsForbidden() {
        UUID actorId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        Message message =
                Message.builder()
                        .id(messageId)
                        .conversationId(conversationId)
                        .senderId(senderId)
                        .messageType(MessageType.TEXT)
                        .content("not yours")
                        .build();
        when(messageRepository.findByIdAndConversationId(messageId, conversationId))
                .thenReturn(Optional.of(message));

        assertThatThrownBy(() -> service.deleteMessage(actorId, conversationId, messageId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.MESSAGE_FORBIDDEN);
        verify(messageRepository, never()).save(any());
    }

    @Test
    void deleteMessage_notFound_throwsMessageNotFound() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        when(messageRepository.findByIdAndConversationId(messageId, conversationId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteMessage(actorId, conversationId, messageId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.MESSAGE_NOT_FOUND);
    }

    @Test
    void markRead_activeParticipant_setsLastReadAt() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(directConversation(conversationId)));
        ConversationParticipant participant = participant(conversationId, actorId, null);
        when(participantRepository.findByIdConversationIdAndIdUserId(conversationId, actorId))
                .thenReturn(Optional.of(participant));

        service.markRead(actorId, conversationId);

        assertThat(participant.getLastReadAt()).isNotNull();
        verify(participantRepository).save(participant);
    }

    @Test
    void markRead_manuallyUnreadParticipant_clearsManualFlag() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(directConversation(conversationId)));
        ConversationParticipant participant = participant(conversationId, actorId, null);
        participant.setManuallyUnread(true);
        when(participantRepository.findByIdConversationIdAndIdUserId(conversationId, actorId))
                .thenReturn(Optional.of(participant));

        service.markRead(actorId, conversationId);

        assertThat(participant.isManuallyUnread()).isFalse();
    }

    @Test
    void markRead_nonParticipant_throwsConversationForbidden() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(directConversation(conversationId)));
        when(participantRepository.findByIdConversationIdAndIdUserId(conversationId, actorId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead(actorId, conversationId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.CONVERSATION_FORBIDDEN);
    }

    @Test
    void markUnread_activeParticipant_setsManualFlagWithoutTouchingLastReadAt() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(directConversation(conversationId)));
        ConversationParticipant participant = participant(conversationId, actorId, null);
        OffsetDateTime lastReadAt = OffsetDateTime.now(ZoneOffset.UTC);
        participant.setLastReadAt(lastReadAt);
        when(participantRepository.findByIdConversationIdAndIdUserId(conversationId, actorId))
                .thenReturn(Optional.of(participant));

        service.markUnread(actorId, conversationId);

        assertThat(participant.isManuallyUnread()).isTrue();
        assertThat(participant.getLastReadAt()).isEqualTo(lastReadAt);
        verify(participantRepository).save(participant);
    }

    @Test
    void markUnread_nonParticipant_throwsConversationForbidden() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(directConversation(conversationId)));
        when(participantRepository.findByIdConversationIdAndIdUserId(conversationId, actorId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markUnread(actorId, conversationId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.CONVERSATION_FORBIDDEN);
    }

    @Test
    void sendMessage_success_reactivatesOtherParticipantWhoHadLeft() {
        UUID actorId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        stubActiveGroupParticipant(conversationId, actorId);
        ConversationParticipant otherParticipant =
                participant(
                        conversationId, otherId, OffsetDateTime.now(ZoneOffset.UTC).minusDays(2));
        when(participantRepository.findByIdConversationIdOrderByJoinedAtAsc(conversationId))
                .thenReturn(List.of(participant(conversationId, actorId, null), otherParticipant));
        when(messageRepository.saveAndFlush(any()))
                .thenReturn(
                        Message.builder()
                                .id(UUID.randomUUID())
                                .conversationId(conversationId)
                                .senderId(actorId)
                                .messageType(MessageType.TEXT)
                                .content("hello")
                                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                                .build());

        service.sendMessage(actorId, conversationId, textRequest("hello"), null);

        // Deleting a conversation only hides it for the deleter; a new message from the other side
        // is what un-hides it again, so the recipient's own left_at must be cleared here.
        assertThat(otherParticipant.getLeftAt()).isNull();
        verify(participantRepository).save(otherParticipant);
    }

    @Test
    void sendMessage_pendingRequesterRefusedByPolicy_throwsAwaitingAcceptance() {
        UUID actorId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        stubPendingRequest(conversationId, actorId, recipientId, ConversationRequestStatus.PENDING);
        when(messageRepository.countByConversationIdAndSenderId(conversationId, actorId))
                .thenReturn(1L);
        when(interactionPolicy.evaluate(any()))
                .thenReturn(
                        new InteractionDecision(
                                InteractionOutcome.DENY, InteractionReason.AWAITING_ACCEPTANCE));

        assertThatThrownBy(
                        () ->
                                service.sendMessage(
                                        actorId, conversationId, textRequest("again"), null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.MESSAGE_REQUEST_AWAITING_ACCEPTANCE);
        verify(messageRepository, never()).saveAndFlush(any());
    }

    @Test
    void sendMessage_pendingRequesterContext_carriesCountTypeAndRequesterFlag() {
        UUID actorId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        stubPendingRequest(conversationId, actorId, recipientId, ConversationRequestStatus.PENDING);
        when(messageRepository.countByConversationIdAndSenderId(conversationId, actorId))
                .thenReturn(0L);
        stubSavedMessage(conversationId, actorId);

        service.sendMessage(actorId, conversationId, textRequest("hello"), null);

        verify(interactionPolicy)
                .evaluate(
                        argThat(
                                request ->
                                        request.targetUserId().equals(recipientId)
                                                && request.conversation().actorIsRequester()
                                                && request.conversation().actorMessagesSoFar() == 0
                                                && "text"
                                                        .equals(
                                                                request.conversation()
                                                                        .messageType())
                                                && request.conversation().state()
                                                        == ConversationRequestState.PENDING));
    }

    @Test
    void sendMessage_routedToHiddenRequests_marksTheRequestHidden() {
        UUID actorId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Conversation conversation =
                stubPendingRequest(
                        conversationId, actorId, recipientId, ConversationRequestStatus.PENDING);
        when(messageRepository.countByConversationIdAndSenderId(conversationId, actorId))
                .thenReturn(0L);
        when(interactionPolicy.evaluate(any()))
                .thenReturn(
                        new InteractionDecision(
                                InteractionOutcome.ROUTE_TO_HIDDEN_REQUESTS,
                                InteractionReason.HIDDEN_WORD));
        stubSavedMessage(conversationId, actorId);

        service.sendMessage(actorId, conversationId, textRequest("hello"), null);

        assertThat(conversation.isRequestHidden()).isTrue();
        verify(conversationRepository).save(conversation);
    }

    @Test
    void sendMessage_recipientAnsweringPendingRequest_acceptsIt() {
        UUID recipientId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Conversation conversation =
                stubPendingRequest(
                        conversationId,
                        recipientId,
                        requesterId,
                        ConversationRequestStatus.PENDING);
        conversation.setRequestRecipientId(recipientId);
        conversation.setRequestHidden(true);
        stubSavedMessage(conversationId, recipientId);

        service.sendMessage(recipientId, conversationId, textRequest("hi"), null);

        assertThat(conversation.getRequestStatus()).isEqualTo(ConversationRequestStatus.ACCEPTED);
        assertThat(conversation.isRequestHidden()).isFalse();
        assertThat(conversation.getRequestResolvedAt()).isNotNull();
        verify(messageRepository, never()).countByConversationIdAndSenderId(any(), any());
    }

    @Test
    void sendMessage_declinedRequest_neverRevivesTheRecipientWhoLeft() {
        UUID actorId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        stubPendingRequest(
                conversationId, actorId, recipientId, ConversationRequestStatus.DECLINED);
        ConversationParticipant recipient =
                participant(
                        conversationId,
                        recipientId,
                        OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
        when(participantRepository.findByIdConversationIdOrderByJoinedAtAsc(conversationId))
                .thenReturn(List.of(participant(conversationId, actorId, null), recipient));
        when(messageRepository.countByConversationIdAndSenderId(conversationId, actorId))
                .thenReturn(0L);
        stubSavedMessage(conversationId, actorId);

        service.sendMessage(actorId, conversationId, textRequest("hello"), null);

        assertThat(recipient.getLeftAt()).isNotNull();
        verify(participantRepository, never()).save(recipient);
    }

    @Test
    void sendMessage_retryOfRecordedKey_replaysBeforeThePolicyIsAsked() {
        UUID actorId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        stubActiveGroupParticipant(conversationId, actorId);
        String hash = sha256(conversationId + "|TEXT|hello|null|null|null|null");
        when(idempotencyRepository.findByUserIdAndIdempotencyKey(actorId, "key-3"))
                .thenReturn(
                        Optional.of(
                                MessageWriteIdempotency.builder()
                                        .userId(actorId)
                                        .idempotencyKey("key-3")
                                        .requestHash(hash)
                                        .responseBody("cached-json")
                                        .build()));
        MessageResponse cached =
                new MessageResponse(
                        UUID.randomUUID(),
                        conversationId,
                        actorId,
                        MessageType.TEXT,
                        "hello",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        null,
                        OffsetDateTime.now(ZoneOffset.UTC),
                        null);
        when(objectMapper.readValue("cached-json", MessageResponse.class)).thenReturn(cached);

        MessageResponse response =
                service.sendMessage(actorId, conversationId, textRequest("hello"), "key-3");

        assertThat(response).isSameAs(cached);
        verify(interactionPolicy, never()).evaluate(any());
    }

    @Test
    void getRequestCount_delegatesToRepository() {
        UUID actorId = UUID.randomUUID();
        when(messageRepository.countPendingRequests(actorId)).thenReturn(3L);

        assertThat(service.getRequestCount(actorId)).isEqualTo(3L);
    }

    @Test
    void getUnreadCount_delegatesToRepository() {
        UUID actorId = UUID.randomUUID();
        when(messageRepository.countTotalUnreadForUser(actorId)).thenReturn(7L);

        assertThat(service.getUnreadCount(actorId)).isEqualTo(7L);
    }

    private void stubActiveGroupParticipant(UUID conversationId, UUID actorId) {
        when(conversationRepository.lockById(conversationId))
                .thenReturn(Optional.of(directConversation(conversationId)));
        when(participantRepository.existsByIdConversationIdAndIdUserIdAndLeftAtIsNull(
                        conversationId, actorId))
                .thenReturn(true);
    }

    // The actor is a participant of a request whose recipient is the other account
    private Conversation stubPendingRequest(
            UUID conversationId, UUID actorId, UUID otherId, ConversationRequestStatus status) {
        Conversation conversation =
                Conversation.builder()
                        .id(conversationId)
                        .requestStatus(status)
                        .requestRecipientId(otherId)
                        .build();
        when(conversationRepository.lockById(conversationId)).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByIdConversationIdAndIdUserIdAndLeftAtIsNull(
                        conversationId, actorId))
                .thenReturn(true);
        lenient()
                .when(
                        participantRepository.findByIdConversationIdOrderByJoinedAtAsc(
                                conversationId))
                .thenReturn(
                        List.of(
                                participant(conversationId, actorId, null),
                                participant(conversationId, otherId, null)));
        return conversation;
    }

    private void stubSavedMessage(UUID conversationId, UUID senderId) {
        when(messageRepository.saveAndFlush(any()))
                .thenReturn(
                        Message.builder()
                                .id(UUID.randomUUID())
                                .conversationId(conversationId)
                                .senderId(senderId)
                                .messageType(MessageType.TEXT)
                                .content("hello")
                                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                                .build());
    }

    private static SendMessageRequest textRequest(String content) {
        return new SendMessageRequest(MessageType.TEXT, content, null, null, null, null);
    }

    private static Conversation directConversation(UUID id) {
        return Conversation.builder().id(id).build();
    }

    private static ConversationParticipant participant(
            UUID conversationId, UUID userId, OffsetDateTime leftAt) {
        return ConversationParticipant.builder()
                .id(new ConversationParticipantId(conversationId, userId))
                .leftAt(leftAt)
                .build();
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
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
