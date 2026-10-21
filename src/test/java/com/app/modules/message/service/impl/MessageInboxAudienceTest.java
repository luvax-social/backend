package com.app.modules.message.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.modules.message.entity.Conversation;
import com.app.modules.message.entity.ConversationParticipant;
import com.app.modules.message.entity.ConversationParticipantId;
import com.app.modules.message.enums.ConversationRequestStatus;
import com.app.modules.message.repository.ConversationParticipantRepository;
import com.app.modules.social.service.SocialService;

@ExtendWith(MockitoExtension.class)
class MessageInboxAudienceTest {

    @Mock private ConversationParticipantRepository participantRepository;
    @Mock private SocialService socialService;

    private MessageInboxAudience audience;
    private final UUID conversationId = UUID.randomUUID();
    private final UUID requester = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        audience = new MessageInboxAudience(participantRepository, socialService);
    }

    @Test
    void recipients_activeOtherParticipant_isTheAudience() {
        Conversation conversation = conversation(ConversationRequestStatus.ACCEPTED, false);
        stubParticipants(null, null);

        assertThat(audience.recipients(conversation, requester)).containsExactly(recipient);
    }

    @Test
    void recipients_theActorIsNeverInTheAudience() {
        Conversation conversation = conversation(ConversationRequestStatus.ACCEPTED, false);
        stubParticipants(null, null);

        assertThat(audience.recipients(conversation, recipient)).containsExactly(requester);
    }

    @Test
    void recipients_participantWhoLeft_isExcluded() {
        Conversation conversation = conversation(ConversationRequestStatus.ACCEPTED, false);
        stubParticipants(null, OffsetDateTime.now(ZoneOffset.UTC));

        assertThat(audience.recipients(conversation, requester)).isEmpty();
    }

    @Test
    void recipients_declinedRequestRecipient_isExcludedEvenIfStillMarkedActive() {
        Conversation conversation = conversation(ConversationRequestStatus.DECLINED, false);
        stubParticipants(null, null);

        assertThat(audience.recipients(conversation, requester)).isEmpty();
    }

    @Test
    void recipients_blockedPair_isExcluded() {
        Conversation conversation = conversation(ConversationRequestStatus.ACCEPTED, false);
        stubParticipants(null, null);
        when(socialService.isBlockedBetween(requester, recipient)).thenReturn(true);

        assertThat(audience.recipients(conversation, requester)).isEmpty();
    }

    @Test
    void reactionRecipients_requesterReactsWhilePending_isNobody() {
        Conversation conversation = conversation(ConversationRequestStatus.PENDING, false);

        assertThat(audience.reactionRecipients(conversation, requester)).isEmpty();
    }

    @Test
    void reactionRecipients_requesterReactsWhileDeclined_isNobody() {
        Conversation conversation = conversation(ConversationRequestStatus.DECLINED, false);

        assertThat(audience.reactionRecipients(conversation, requester)).isEmpty();
    }

    @Test
    void reactionRecipients_requestRecipientReactsWhilePending_reachesTheRequester() {
        Conversation conversation = conversation(ConversationRequestStatus.PENDING, false);
        stubParticipants(null, null);

        assertThat(audience.reactionRecipients(conversation, recipient)).containsExactly(requester);
    }

    @Test
    void reactionRecipients_acceptedConversation_behavesLikeRecipients() {
        Conversation conversation = conversation(ConversationRequestStatus.ACCEPTED, false);
        stubParticipants(null, null);

        assertThat(audience.reactionRecipients(conversation, requester)).containsExactly(recipient);
    }

    @Test
    void folderOf_acceptedConversation_isPrimary() {
        assertThat(
                        MessageInboxAudience.folderOf(
                                conversation(ConversationRequestStatus.ACCEPTED, false)))
                .isEqualTo("primary");
    }

    @Test
    void folderOf_pendingRequest_isRequests() {
        assertThat(
                        MessageInboxAudience.folderOf(
                                conversation(ConversationRequestStatus.PENDING, false)))
                .isEqualTo("requests");
    }

    @Test
    void folderOf_hiddenPendingRequest_isHiddenRequests() {
        assertThat(
                        MessageInboxAudience.folderOf(
                                conversation(ConversationRequestStatus.PENDING, true)))
                .isEqualTo("hidden_requests");
    }

    private Conversation conversation(ConversationRequestStatus status, boolean hidden) {
        return Conversation.builder()
                .id(conversationId)
                .requestStatus(status)
                .requestRecipientId(recipient)
                .requestHidden(hidden)
                .build();
    }

    private void stubParticipants(OffsetDateTime requesterLeftAt, OffsetDateTime recipientLeftAt) {
        when(participantRepository.findByIdConversationIdOrderByJoinedAtAsc(conversationId))
                .thenReturn(
                        List.of(
                                ConversationParticipant.builder()
                                        .id(
                                                new ConversationParticipantId(
                                                        conversationId, requester))
                                        .leftAt(requesterLeftAt)
                                        .build(),
                                ConversationParticipant.builder()
                                        .id(
                                                new ConversationParticipantId(
                                                        conversationId, recipient))
                                        .leftAt(recipientLeftAt)
                                        .build()));
    }
}
