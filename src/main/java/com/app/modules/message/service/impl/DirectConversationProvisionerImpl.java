package com.app.modules.message.service.impl;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.modules.message.entity.Conversation;
import com.app.modules.message.entity.ConversationParticipant;
import com.app.modules.message.entity.ConversationParticipantId;
import com.app.modules.message.enums.ConversationRequestStatus;
import com.app.modules.message.repository.ConversationParticipantRepository;
import com.app.modules.message.repository.ConversationRepository;
import com.app.modules.message.service.DirectConversationProvisioner;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Repository-only implementation of the provisioning port.
 *
 * <p>Deliberately does not delegate to {@code ConversationServiceImpl}: that class injects {@code
 * SocialService}, and routing through it would close the dependency cycle this port exists to
 * avoid. The duplication of the create sequence is the price of that separation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DirectConversationProvisionerImpl implements DirectConversationProvisioner {

    private final ConversationRepository conversationRepository;
    private final ConversationParticipantRepository participantRepository;

    @Override
    @Transactional
    public void ensureDirectConversation(UUID userIdA, UUID userIdB) {
        if (userIdA.equals(userIdB)) {
            return;
        }

        // The same advisory lock the manual create path takes. Two people following each other
        // back at the same instant would otherwise both observe no conversation and insert one
        // each, leaving the pair with two threads and no way to merge them.
        String pairKey = conversationRepository.lockDirectConversationPair(userIdA, userIdB);

        Optional<Conversation> existing =
                conversationRepository.findDirectConversationBetween(userIdA, userIdB);
        if (existing.isPresent()) {
            Conversation current = existing.get();
            // The recipient's decline takes this same pair lock before the row lock, so the status
            // is decided here from the locked row, not from the entity read above, which may
            // predate a decline that committed while this call waited for the pair lock.
            ConversationRequestStatus status =
                    conversationRepository
                            .lockRequestStatus(current.getId())
                            .map(
                                    value ->
                                            ConversationRequestStatus.valueOf(
                                                    value.toUpperCase(Locale.ROOT)))
                            .orElse(ConversationRequestStatus.ACCEPTED);
            if (status == ConversationRequestStatus.PENDING) {
                // A mutual follow is the strongest signal that the two want to talk, so it settles
                // a request that was still waiting instead of leaving it in a folder
                accept(current);
                return;
            }
            if (status == ConversationRequestStatus.ACCEPTED) {
                return;
            }
            // A declined request is not reopened: its recipient chose to end it, so the pair gets
            // a fresh conversation and the old one stays as its sender last saw it
            conversationRepository.retireDirectPairKey(current.getId());
        }

        Conversation conversation =
                Conversation.builder()
                        .createdBy(userIdA)
                        .directPairKey(pairKey)
                        .requestStatus(ConversationRequestStatus.ACCEPTED)
                        .build();
        conversationRepository.saveAndFlush(conversation);
        participantRepository.save(participant(conversation.getId(), userIdA));
        participantRepository.save(participant(conversation.getId(), userIdB));

        log.info(
                "Direct conversation provisioned from mutual follow: conversationId={}",
                conversation.getId());
    }

    @Override
    @Transactional
    public void discardEmptyDirectConversation(UUID userIdA, UUID userIdB) {
        if (userIdA.equals(userIdB)) {
            return;
        }

        conversationRepository.lockDirectConversationPair(userIdA, userIdB);
        conversationRepository.deleteEmptyDirectConversation(userIdA, userIdB);
    }

    private void accept(Conversation conversation) {
        conversation.setRequestStatus(ConversationRequestStatus.ACCEPTED);
        conversation.setRequestHidden(false);
        conversation.setRequestResolvedAt(OffsetDateTime.now(ZoneOffset.UTC));
        conversationRepository.save(conversation);
        log.info(
                "Message request accepted by mutual follow: conversationId={}",
                conversation.getId());
    }

    private static ConversationParticipant participant(UUID conversationId, UUID userId) {
        return ConversationParticipant.builder()
                .id(new ConversationParticipantId(conversationId, userId))
                .build();
    }
}
