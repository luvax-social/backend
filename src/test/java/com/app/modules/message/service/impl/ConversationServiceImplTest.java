package com.app.modules.message.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import com.app.common.pagination.Cursor;
import com.app.common.pagination.CursorCodec;
import com.app.common.pagination.CursorScope;
import com.app.common.pagination.TimeCursors;
import com.app.common.response.CursorPageResponse;
import com.app.modules.message.dto.request.CreateDirectConversationRequest;
import com.app.modules.message.dto.response.ConversationResponse;
import com.app.modules.message.dto.response.ConversationSummaryResponse;
import com.app.modules.message.dto.response.MessageResponse;
import com.app.modules.message.dto.response.ParticipantResponse;
import com.app.modules.message.entity.Conversation;
import com.app.modules.message.entity.ConversationParticipant;
import com.app.modules.message.entity.ConversationParticipantId;
import com.app.modules.message.enums.ConversationFolder;
import com.app.modules.message.enums.ConversationRequestStatus;
import com.app.modules.message.mapper.MessageMapper;
import com.app.modules.message.repository.ConversationParticipantRepository;
import com.app.modules.message.repository.ConversationRepository;
import com.app.modules.message.repository.MessageMediaAssetRepository;
import com.app.modules.message.repository.MessageRepository;
import com.app.modules.message.repository.MessageUserRepository;
import com.app.modules.social.policy.InteractionDecision;
import com.app.modules.social.policy.InteractionOutcome;
import com.app.modules.social.policy.InteractionPolicyService;
import com.app.modules.social.policy.InteractionReason;
import com.app.modules.social.policy.InteractionType;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.entity.User;

@ExtendWith(MockitoExtension.class)
class ConversationServiceImplTest {

    @Mock private ConversationRepository conversationRepository;
    @Mock private ConversationParticipantRepository participantRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private MessageUserRepository userRepository;
    @Mock private InteractionPolicyService interactionPolicy;
    @Mock private MessageMapper mapper;
    @Mock private MessageMediaAssetRepository mediaAssetRepository;
    @Mock private SocialService socialService;
    @Mock private OutboxService outboxService;

    private ConversationServiceImpl service;

    @BeforeEach
    void setUp() {
        service =
                new ConversationServiceImpl(
                        conversationRepository,
                        participantRepository,
                        messageRepository,
                        userRepository,
                        interactionPolicy,
                        mapper,
                        mediaAssetRepository,
                        socialService,
                        outboxService);

        lenient()
                .when(interactionPolicy.evaluate(any()))
                .thenReturn(
                        new InteractionDecision(InteractionOutcome.ALLOW, InteractionReason.NONE));
        lenient()
                .when(mapper.toParticipantResponse(any(), any()))
                .thenAnswer(
                        inv -> {
                            ConversationParticipant p = inv.getArgument(0);
                            User u = inv.getArgument(1);
                            return new ParticipantResponse(
                                    p.getId().getUserId(),
                                    u == null ? null : u.getUsername(),
                                    u != null && u.isVerified(),
                                    u == null ? null : u.getVerifiedCategory(),
                                    u == null ? null : u.getDisplayName(),
                                    u == null ? null : u.getAvatarUrl(),
                                    p.getJoinedAt(),
                                    p.getLeftAt(),
                                    p.getNickname());
                        });
        lenient()
                .when(
                        mapper.toConversationResponse(
                                any(),
                                any(),
                                org.mockito.ArgumentMatchers.anyBoolean(),
                                org.mockito.ArgumentMatchers.anyBoolean(),
                                any(),
                                org.mockito.ArgumentMatchers.anyBoolean(),
                                any()))
                .thenAnswer(
                        inv -> {
                            Conversation c = inv.getArgument(0);
                            List<ParticipantResponse> participants = inv.getArgument(1);
                            boolean pinned = inv.getArgument(2);
                            boolean muted = inv.getArgument(3);
                            return new ConversationResponse(
                                    c.getId(),
                                    c.getCreatedBy(),
                                    participants,
                                    c.getLastMessageAt(),
                                    c.getCreatedAt(),
                                    pinned,
                                    muted,
                                    inv.getArgument(4),
                                    inv.getArgument(5),
                                    inv.getArgument(6));
                        });
        lenient()
                .when(
                        mapper.toSummaryResponse(
                                any(),
                                any(),
                                org.mockito.ArgumentMatchers.anyLong(),
                                any(),
                                org.mockito.ArgumentMatchers.anyBoolean(),
                                org.mockito.ArgumentMatchers.anyBoolean(),
                                org.mockito.ArgumentMatchers.anyBoolean(),
                                any(),
                                org.mockito.ArgumentMatchers.anyBoolean(),
                                org.mockito.ArgumentMatchers.anyBoolean(),
                                any()))
                .thenAnswer(
                        inv -> {
                            Conversation c = inv.getArgument(0);
                            List<ParticipantResponse> participants = inv.getArgument(1);
                            long unread = inv.getArgument(2);
                            MessageResponse lastMessage = inv.getArgument(3);
                            boolean pinned = inv.getArgument(4);
                            boolean muted = inv.getArgument(5);
                            boolean manuallyUnread = inv.getArgument(6);
                            return new ConversationSummaryResponse(
                                    c.getId(),
                                    participants,
                                    unread,
                                    c.getLastMessageAt(),
                                    lastMessage,
                                    pinned,
                                    muted,
                                    manuallyUnread,
                                    inv.getArgument(7),
                                    inv.getArgument(8),
                                    inv.getArgument(9),
                                    inv.getArgument(10));
                        });
    }

    @Test
    void createDirectConversation_selfConversation_throwsInvalidParticipants() {
        UUID actorId = UUID.randomUUID();

        assertThatThrownBy(
                        () ->
                                service.createDirectConversation(
                                        actorId, new CreateDirectConversationRequest(actorId)))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.CONVERSATION_INVALID_PARTICIPANTS);
        verify(userRepository, never()).findByIdAndDeletedAtIsNull(any());
    }

    @Test
    void createDirectConversation_targetNotFound_throwsUserNotFound() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(userRepository.findByIdAndDeletedAtIsNull(targetId)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.createDirectConversation(
                                        actorId, new CreateDirectConversationRequest(targetId)))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.USER_NOT_FOUND);
    }

    @Test
    void createDirectConversation_blockedPair_throwsNotFound() {
        // Stealth block model: a blocked target must be indistinguishable from a nonexistent one.
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(userRepository.findByIdAndDeletedAtIsNull(targetId))
                .thenReturn(Optional.of(user(targetId)));
        policyAnswers(InteractionOutcome.DENY, InteractionReason.BLOCKED);

        assertThatThrownBy(
                        () ->
                                service.createDirectConversation(
                                        actorId, new CreateDirectConversationRequest(targetId)))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.NOT_FOUND);
        verify(conversationRepository, never()).saveAndFlush(any(Conversation.class));
    }

    @Test
    void
            createDirectConversation_requestsDisabledAndNotFollowedBack_throwsMessageRequestNotAllowed() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(userRepository.findByIdAndDeletedAtIsNull(targetId))
                .thenReturn(Optional.of(user(targetId)));
        policyAnswers(InteractionOutcome.DENY, InteractionReason.PERMISSION_FOLLOWING_ONLY);

        assertThatThrownBy(
                        () ->
                                service.createDirectConversation(
                                        actorId, new CreateDirectConversationRequest(targetId)))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.MESSAGE_REQUEST_NOT_ALLOWED);
        verify(conversationRepository, never()).saveAndFlush(any(Conversation.class));
    }

    @Test
    void
            createDirectConversation_permissionNobodyAndNotFollowedBack_throwsMessageRequestNotAllowed() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(userRepository.findByIdAndDeletedAtIsNull(targetId))
                .thenReturn(Optional.of(user(targetId)));
        policyAnswers(InteractionOutcome.DENY, InteractionReason.PERMISSION_NOBODY);

        assertThatThrownBy(
                        () ->
                                service.createDirectConversation(
                                        actorId, new CreateDirectConversationRequest(targetId)))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.MESSAGE_REQUEST_NOT_ALLOWED);
        verify(conversationRepository, never()).saveAndFlush(any(Conversation.class));
    }

    @Test
    void createDirectConversation_requestsDisabledButFollowedBack_createsConversation() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(userRepository.findByIdAndDeletedAtIsNull(targetId))
                .thenReturn(Optional.of(user(targetId)));
        policyAnswers(InteractionOutcome.ALLOW, InteractionReason.NONE);
        when(conversationRepository.findDirectConversationBetween(actorId, targetId))
                .thenReturn(Optional.empty());
        when(conversationRepository.saveAndFlush(any(Conversation.class)))
                .thenAnswer(
                        inv -> {
                            Conversation c = inv.getArgument(0);
                            c.setId(conversationId);
                            return c;
                        });
        when(participantRepository.findByIdConversationIdOrderByJoinedAtAsc(conversationId))
                .thenReturn(List.of());

        ConversationResponse result =
                service.createDirectConversation(
                        actorId, new CreateDirectConversationRequest(targetId));

        assertThat(result).isNotNull();
        verify(participantRepository).save(argThat(p -> p.getId().getUserId().equals(actorId)));
        verify(participantRepository).save(argThat(p -> p.getId().getUserId().equals(targetId)));
    }

    @Test
    void createDirectConversation_newConversation_locksPairAndPersistsPairKey() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        String pairKey = actorId + ":" + targetId;
        when(userRepository.findByIdAndDeletedAtIsNull(targetId))
                .thenReturn(Optional.of(user(targetId)));
        when(conversationRepository.lockDirectConversationPair(actorId, targetId))
                .thenReturn(pairKey);
        when(conversationRepository.findDirectConversationBetween(actorId, targetId))
                .thenReturn(Optional.empty());
        when(conversationRepository.saveAndFlush(any(Conversation.class)))
                .thenAnswer(
                        inv -> {
                            Conversation c = inv.getArgument(0);
                            c.setId(conversationId);
                            return c;
                        });
        when(participantRepository.findByIdConversationIdOrderByJoinedAtAsc(conversationId))
                .thenReturn(List.of());

        service.createDirectConversation(actorId, new CreateDirectConversationRequest(targetId));

        // The lock must be acquired before the check-then-create so a concurrent call for the same
        // pair serializes behind it instead of racing into a duplicate conversation.
        verify(conversationRepository).lockDirectConversationPair(actorId, targetId);
        verify(conversationRepository)
                .saveAndFlush(argThat(c -> pairKey.equals(c.getDirectPairKey())));
    }

    @Test
    void createDirectConversation_routeToRequests_persistsPendingRequestForTarget() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(userRepository.findByIdAndDeletedAtIsNull(targetId))
                .thenReturn(Optional.of(user(targetId)));
        when(conversationRepository.findDirectConversationBetween(actorId, targetId))
                .thenReturn(Optional.empty());
        policyAnswers(InteractionOutcome.ROUTE_TO_REQUESTS, InteractionReason.NOT_FOLLOWED_REQUEST);
        when(conversationRepository.saveAndFlush(any(Conversation.class)))
                .thenAnswer(
                        inv -> {
                            Conversation c = inv.getArgument(0);
                            c.setId(conversationId);
                            return c;
                        });

        service.createDirectConversation(actorId, new CreateDirectConversationRequest(targetId));

        verify(conversationRepository)
                .saveAndFlush(
                        argThat(
                                (Conversation c) ->
                                        c.getRequestStatus() == ConversationRequestStatus.PENDING
                                                && targetId.equals(c.getRequestRecipientId())
                                                && !c.isRequestHidden()));
    }

    @Test
    void createDirectConversation_routeToHiddenRequests_persistsHiddenPendingRequest() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(userRepository.findByIdAndDeletedAtIsNull(targetId))
                .thenReturn(Optional.of(user(targetId)));
        when(conversationRepository.findDirectConversationBetween(actorId, targetId))
                .thenReturn(Optional.empty());
        policyAnswers(InteractionOutcome.ROUTE_TO_HIDDEN_REQUESTS, InteractionReason.HIDDEN_WORD);
        when(conversationRepository.saveAndFlush(any(Conversation.class)))
                .thenAnswer(
                        inv -> {
                            Conversation c = inv.getArgument(0);
                            c.setId(conversationId);
                            return c;
                        });

        service.createDirectConversation(actorId, new CreateDirectConversationRequest(targetId));

        verify(conversationRepository)
                .saveAndFlush(
                        argThat(
                                (Conversation c) ->
                                        c.getRequestStatus() == ConversationRequestStatus.PENDING
                                                && c.isRequestHidden()));
    }

    @Test
    void createDirectConversation_existingAcceptedConversation_skipsMessagePermission() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Conversation existing = Conversation.builder().id(conversationId).build();
        ConversationParticipant actorParticipant = participant(conversationId, actorId, null);
        when(userRepository.findByIdAndDeletedAtIsNull(targetId))
                .thenReturn(Optional.of(user(targetId)));
        when(conversationRepository.findDirectConversationBetween(actorId, targetId))
                .thenReturn(Optional.of(existing));
        when(participantRepository.findByIdConversationIdAndIdUserId(conversationId, actorId))
                .thenReturn(Optional.of(actorParticipant));
        when(participantRepository.findByIdConversationIdOrderByJoinedAtAsc(conversationId))
                .thenReturn(List.of(actorParticipant));

        service.createDirectConversation(actorId, new CreateDirectConversationRequest(targetId));

        verify(interactionPolicy, never())
                .evaluate(argThat(r -> r.type() == InteractionType.MESSAGE_INITIATE));
        verify(conversationRepository, never()).retireDirectPairKey(any());
    }

    @Test
    void createDirectConversation_recipientWhoDeclined_retiresOldConversationAndStartsFresh() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID declinedId = UUID.randomUUID();
        UUID freshId = UUID.randomUUID();
        Conversation declined =
                Conversation.builder()
                        .id(declinedId)
                        .requestStatus(ConversationRequestStatus.DECLINED)
                        .requestRecipientId(actorId)
                        .build();
        when(userRepository.findByIdAndDeletedAtIsNull(targetId))
                .thenReturn(Optional.of(user(targetId)));
        when(conversationRepository.findDirectConversationBetween(actorId, targetId))
                .thenReturn(Optional.of(declined));
        when(conversationRepository.saveAndFlush(any(Conversation.class)))
                .thenAnswer(
                        inv -> {
                            Conversation c = inv.getArgument(0);
                            c.setId(freshId);
                            return c;
                        });

        ConversationResponse result =
                service.createDirectConversation(
                        actorId, new CreateDirectConversationRequest(targetId));

        assertThat(result.id()).isEqualTo(freshId);
        verify(conversationRepository).retireDirectPairKey(declinedId);
    }

    @Test
    void createDirectConversation_existingConversationWithLeftActor_reactivatesAndReuses() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Conversation existing = Conversation.builder().id(conversationId).build();
        ConversationParticipant actorParticipant =
                participant(
                        conversationId, actorId, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

        when(userRepository.findByIdAndDeletedAtIsNull(targetId))
                .thenReturn(Optional.of(user(targetId)));
        when(conversationRepository.findDirectConversationBetween(actorId, targetId))
                .thenReturn(Optional.of(existing));
        when(participantRepository.findByIdConversationIdAndIdUserId(conversationId, actorId))
                .thenReturn(Optional.of(actorParticipant));
        when(participantRepository.findByIdConversationIdOrderByJoinedAtAsc(conversationId))
                .thenReturn(List.of(actorParticipant));
        when(userRepository.findAllByIdInAndDeletedAtIsNull(Set.of(actorId)))
                .thenReturn(List.of(user(actorId)));

        ConversationResponse result =
                service.createDirectConversation(
                        actorId, new CreateDirectConversationRequest(targetId));

        assertThat(result).isNotNull();
        assertThat(actorParticipant.getLeftAt()).isNull();
        verify(participantRepository).save(actorParticipant);
        verify(conversationRepository, never()).saveAndFlush(any());
    }

    @Test
    void getConversation_callerNotActiveParticipant_throwsConversationForbidden() {
        UUID conversationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Conversation conversation = Conversation.builder().id(conversationId).build();
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        when(participantRepository.existsByIdConversationIdAndIdUserIdAndLeftAtIsNull(
                        conversationId, actorId))
                .thenReturn(false);

        assertThatThrownBy(() -> service.getConversation(actorId, conversationId))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.CONVERSATION_FORBIDDEN);
    }

    @Test
    void leaveConversation_activeParticipant_setsLeftAt() {
        UUID conversationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Conversation conversation = Conversation.builder().id(conversationId).build();
        ConversationParticipant actorParticipant = participant(conversationId, actorId, null);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        when(participantRepository.findByIdConversationIdAndIdUserId(conversationId, actorId))
                .thenReturn(Optional.of(actorParticipant));

        service.leaveConversation(actorId, conversationId);

        assertThat(actorParticipant.getLeftAt()).isNotNull();
        verify(participantRepository).save(actorParticipant);
    }

    @Test
    void leaveConversation_alreadyLeft_throwsConversationForbidden() {
        UUID conversationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Conversation conversation = Conversation.builder().id(conversationId).build();
        ConversationParticipant leftParticipant =
                participant(
                        conversationId, actorId, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        when(participantRepository.findByIdConversationIdAndIdUserId(conversationId, actorId))
                .thenReturn(Optional.of(leftParticipant));

        assertThatThrownBy(() -> service.leaveConversation(actorId, conversationId))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.CONVERSATION_FORBIDDEN);
        verify(participantRepository, never()).save(any());
    }

    @Test
    void leaveConversation_nonParticipant_throwsConversationForbidden() {
        UUID conversationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Conversation conversation = Conversation.builder().id(conversationId).build();
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        when(participantRepository.findByIdConversationIdAndIdUserId(conversationId, actorId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.leaveConversation(actorId, conversationId))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.CONVERSATION_FORBIDDEN);
    }

    @Test
    void pinConversation_activeParticipant_setsPinnedAt() {
        UUID conversationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Conversation conversation = Conversation.builder().id(conversationId).build();
        ConversationParticipant actorParticipant = participant(conversationId, actorId, null);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        when(participantRepository.findByIdConversationIdAndIdUserId(conversationId, actorId))
                .thenReturn(Optional.of(actorParticipant));

        service.pinConversation(actorId, conversationId);

        assertThat(actorParticipant.getPinnedAt()).isNotNull();
        verify(participantRepository).save(actorParticipant);
    }

    @Test
    void pinConversation_nonParticipant_throwsConversationForbidden() {
        UUID conversationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Conversation conversation = Conversation.builder().id(conversationId).build();
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        when(participantRepository.findByIdConversationIdAndIdUserId(conversationId, actorId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pinConversation(actorId, conversationId))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.CONVERSATION_FORBIDDEN);
        verify(participantRepository, never()).save(any());
    }

    @Test
    void unpinConversation_pinnedParticipant_clearsPinnedAt() {
        UUID conversationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Conversation conversation = Conversation.builder().id(conversationId).build();
        ConversationParticipant actorParticipant = participant(conversationId, actorId, null);
        actorParticipant.setPinnedAt(OffsetDateTime.now(ZoneOffset.UTC));
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        when(participantRepository.findByIdConversationIdAndIdUserId(conversationId, actorId))
                .thenReturn(Optional.of(actorParticipant));

        service.unpinConversation(actorId, conversationId);

        assertThat(actorParticipant.getPinnedAt()).isNull();
        verify(participantRepository).save(actorParticipant);
    }

    @Test
    void muteConversation_activeParticipant_setsMuted() {
        UUID conversationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Conversation conversation = Conversation.builder().id(conversationId).build();
        ConversationParticipant actorParticipant = participant(conversationId, actorId, null);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        when(participantRepository.findByIdConversationIdAndIdUserId(conversationId, actorId))
                .thenReturn(Optional.of(actorParticipant));

        service.muteConversation(actorId, conversationId);

        assertThat(actorParticipant.isMuted()).isTrue();
        verify(participantRepository).save(actorParticipant);
    }

    @Test
    void unmuteConversation_mutedParticipant_clearsMuted() {
        UUID conversationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Conversation conversation = Conversation.builder().id(conversationId).build();
        ConversationParticipant actorParticipant = participant(conversationId, actorId, null);
        actorParticipant.setMuted(true);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        when(participantRepository.findByIdConversationIdAndIdUserId(conversationId, actorId))
                .thenReturn(Optional.of(actorParticipant));

        service.unmuteConversation(actorId, conversationId);

        assertThat(actorParticipant.isMuted()).isFalse();
        verify(participantRepository).save(actorParticipant);
    }

    @Test
    void setNickname_blankValue_clearsNickname() {
        UUID conversationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Conversation conversation = Conversation.builder().id(conversationId).build();
        ConversationParticipant actorParticipant = participant(conversationId, actorId, null);
        actorParticipant.setNickname("Old Name");
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        when(participantRepository.findByIdConversationIdAndIdUserId(conversationId, actorId))
                .thenReturn(Optional.of(actorParticipant));

        service.setNickname(actorId, conversationId, "   ");

        assertThat(actorParticipant.getNickname()).isNull();
        verify(participantRepository).save(actorParticipant);
    }

    @Test
    void setNickname_nonBlankValue_setsNickname() {
        UUID conversationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Conversation conversation = Conversation.builder().id(conversationId).build();
        ConversationParticipant actorParticipant = participant(conversationId, actorId, null);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        when(participantRepository.findByIdConversationIdAndIdUserId(conversationId, actorId))
                .thenReturn(Optional.of(actorParticipant));

        service.setNickname(actorId, conversationId, "Best Friend");

        assertThat(actorParticipant.getNickname()).isEqualTo("Best Friend");
        verify(participantRepository).save(actorParticipant);
    }

    @Test
    void listMyConversations_manuallyUnreadRow_carriesFlagIntoSummary() {
        UUID actorId = UUID.randomUUID();
        UUID convId = UUID.randomUUID();
        Conversation conv = Conversation.builder().id(convId).build();
        ConversationParticipant myRow = participant(convId, actorId, null);
        myRow.setManuallyUnread(true);
        when(conversationRepository.findFirstMyConversations(
                        eq(actorId), eq("primary"), any(Pageable.class)))
                .thenReturn(List.of(conv));
        when(participantRepository.findByIdConversationIdInAndLeftAtIsNull(List.of(convId)))
                .thenReturn(List.of(myRow));
        when(messageRepository.countUnreadPerConversation(eq(actorId), eq(List.of(convId))))
                .thenReturn(List.of());

        CursorPageResponse<ConversationSummaryResponse> result =
                service.listMyConversations(actorId, ConversationFolder.PRIMARY, null, 20);

        assertThat(result.getContent().get(0).manuallyUnread()).isTrue();
    }

    @Test
    void listMyConversations_firstPage_returnsSummariesWithCursors() {
        UUID actorId = UUID.randomUUID();
        UUID conv1Id = UUID.randomUUID();
        UUID conv2Id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Conversation conv1 = Conversation.builder().id(conv1Id).lastMessageAt(now).build();
        Conversation conv2 =
                Conversation.builder().id(conv2Id).lastMessageAt(now.minusMinutes(5)).build();
        when(conversationRepository.findFirstMyConversations(
                        eq(actorId), eq("primary"), any(Pageable.class)))
                .thenReturn(List.of(conv1, conv2));
        when(participantRepository.findByIdConversationIdInAndLeftAtIsNull(
                        List.of(conv1Id, conv2Id)))
                .thenReturn(List.of());
        when(messageRepository.countUnreadPerConversation(
                        eq(actorId), eq(List.of(conv1Id, conv2Id))))
                .thenReturn(List.of());

        CursorPageResponse<ConversationSummaryResponse> result =
                service.listMyConversations(actorId, ConversationFolder.PRIMARY, null, 20);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getPageInfo().getStartCursor()).isNotBlank();
        assertThat(result.getPageInfo().getEndCursor()).isNotBlank();
        assertThat(result.getPageInfo().isHasPreviousPage()).isFalse();
    }

    @Test
    void listMyConversations_firstPageWithPinned_prependsPinnedAheadOfUnpinnedPage() {
        UUID actorId = UUID.randomUUID();
        UUID pinnedConvId = UUID.randomUUID();
        UUID unpinnedConvId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Conversation pinnedConv =
                Conversation.builder().id(pinnedConvId).lastMessageAt(now.minusDays(3)).build();
        Conversation unpinnedConv =
                Conversation.builder().id(unpinnedConvId).lastMessageAt(now).build();
        ConversationParticipant myPinnedRow = participant(pinnedConvId, actorId, null);
        myPinnedRow.setPinnedAt(now.minusHours(1));
        when(conversationRepository.findFirstMyConversations(
                        eq(actorId), eq("primary"), any(Pageable.class)))
                .thenReturn(List.of(unpinnedConv));
        when(conversationRepository.findMyPinnedConversations(actorId))
                .thenReturn(List.of(pinnedConv));
        when(participantRepository.findByIdConversationIdInAndLeftAtIsNull(
                        List.of(pinnedConvId, unpinnedConvId)))
                .thenReturn(List.of(myPinnedRow));
        when(messageRepository.countUnreadPerConversation(
                        eq(actorId), eq(List.of(pinnedConvId, unpinnedConvId))))
                .thenReturn(List.of());

        CursorPageResponse<ConversationSummaryResponse> result =
                service.listMyConversations(actorId, ConversationFolder.PRIMARY, null, 20);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).id()).isEqualTo(pinnedConvId);
        assertThat(result.getContent().get(0).pinned()).isTrue();
        assertThat(result.getContent().get(1).id()).isEqualTo(unpinnedConvId);
        assertThat(result.getContent().get(1).pinned()).isFalse();
        // The end cursor must still anchor on the unpinned page's own last row, not the pinned one,
        // so continuation resumes inside the keyset-paginated sequence.
        assertThat(
                        CursorCodec.decode(
                                        result.getPageInfo().getEndCursor(),
                                        CursorScope.CONVERSATIONS)
                                .id())
                .isEqualTo(unpinnedConvId);
    }

    @Test
    void listMyConversations_noActiveConversations_returnsEmptyPageWithNullCursors() {
        UUID actorId = UUID.randomUUID();
        when(conversationRepository.findFirstMyConversations(
                        eq(actorId), eq("primary"), any(Pageable.class)))
                .thenReturn(List.of());

        CursorPageResponse<ConversationSummaryResponse> result =
                service.listMyConversations(actorId, ConversationFolder.PRIMARY, null, 20);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getPageInfo().getStartCursor()).isNull();
        assertThat(result.getPageInfo().getEndCursor()).isNull();
        assertThat(result.getPageInfo().isHasPreviousPage()).isFalse();
    }

    @Test
    void listMyConversations_withCursor_decodesAndQueriesContinuation() {
        UUID actorId = UUID.randomUUID();
        UUID conv1Id = UUID.randomUUID();
        // Truncated to microsecond resolution: the cursor round-trips through epoch-micros, and a
        // nanosecond remainder would make the decoded value compare unequal to this one.
        OffsetDateTime cursorTime =
                TimeCursors.fromMicros(
                        TimeCursors.toMicros(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1)));
        UUID cursorConvId = UUID.randomUUID();
        String cursor =
                CursorCodec.encode(
                        new Cursor(TimeCursors.toMicros(cursorTime), cursorConvId),
                        CursorScope.CONVERSATIONS);
        Conversation conv1 =
                Conversation.builder()
                        .id(conv1Id)
                        .lastMessageAt(cursorTime.minusMinutes(10))
                        .build();
        when(conversationRepository.findMyConversationsBefore(
                        eq(actorId),
                        eq("primary"),
                        eq(cursorTime),
                        eq(cursorConvId),
                        any(Pageable.class)))
                .thenReturn(List.of(conv1));
        when(participantRepository.findByIdConversationIdInAndLeftAtIsNull(List.of(conv1Id)))
                .thenReturn(List.of());
        when(messageRepository.countUnreadPerConversation(eq(actorId), eq(List.of(conv1Id))))
                .thenReturn(List.of());

        CursorPageResponse<ConversationSummaryResponse> result =
                service.listMyConversations(actorId, ConversationFolder.PRIMARY, cursor, 20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getPageInfo().isHasPreviousPage()).isTrue();
    }

    @Test
    void listMyConversations_cursorFromNullLastMessageAtRow_decodesToNullCursorTime() {
        UUID actorId = UUID.randomUUID();
        UUID conv1Id = UUID.randomUUID();
        Conversation conv1 = Conversation.builder().id(conv1Id).lastMessageAt(null).build();
        when(conversationRepository.findFirstMyConversations(
                        eq(actorId), eq("primary"), any(Pageable.class)))
                .thenReturn(List.of(conv1));
        when(participantRepository.findByIdConversationIdInAndLeftAtIsNull(List.of(conv1Id)))
                .thenReturn(List.of());
        when(messageRepository.countUnreadPerConversation(eq(actorId), eq(List.of(conv1Id))))
                .thenReturn(List.of());

        CursorPageResponse<ConversationSummaryResponse> firstPage =
                service.listMyConversations(actorId, ConversationFolder.PRIMARY, null, 20);
        String endCursor = firstPage.getPageInfo().getEndCursor();
        assertThat(endCursor).isNotBlank();

        UUID conv2Id = UUID.randomUUID();
        Conversation conv2 = Conversation.builder().id(conv2Id).lastMessageAt(null).build();
        // Previously this cursor decoded to OffsetDateTime.MIN (a sentinel for "no message yet"),
        // which is outside PostgreSQL's timestamptz range and caused a bind-time 500; it must now
        // decode back to a genuine null so the repository receives real SQL NULL, not a sentinel.
        when(conversationRepository.findMyConversationsBefore(
                        eq(actorId), eq("primary"), isNull(), eq(conv1Id), any(Pageable.class)))
                .thenReturn(List.of(conv2));
        when(participantRepository.findByIdConversationIdInAndLeftAtIsNull(List.of(conv2Id)))
                .thenReturn(List.of());
        when(messageRepository.countUnreadPerConversation(eq(actorId), eq(List.of(conv2Id))))
                .thenReturn(List.of());

        CursorPageResponse<ConversationSummaryResponse> secondPage =
                service.listMyConversations(actorId, ConversationFolder.PRIMARY, endCursor, 20);

        assertThat(secondPage.getContent()).hasSize(1);
        verify(conversationRepository)
                .findMyConversationsBefore(
                        eq(actorId), eq("primary"), isNull(), eq(conv1Id), any(Pageable.class));
    }

    private void policyAnswers(InteractionOutcome outcome, InteractionReason reason) {
        when(interactionPolicy.evaluate(any()))
                .thenReturn(new InteractionDecision(outcome, reason));
    }

    private static User user(UUID id) {
        return User.builder().id(id).username("user-" + id).displayName("Display " + id).build();
    }

    private static ConversationParticipant participant(
            UUID conversationId, UUID userId, OffsetDateTime leftAt) {
        return ConversationParticipant.builder()
                .id(new ConversationParticipantId(conversationId, userId))
                .leftAt(leftAt)
                .build();
    }
}
