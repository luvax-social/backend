package com.app.modules.message.service.impl;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
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
import com.app.common.pagination.Cursor;
import com.app.common.pagination.CursorCodec;
import com.app.common.pagination.CursorScope;
import com.app.common.pagination.TimeCursors;
import com.app.common.response.CursorPageResponse;
import com.app.modules.media.entity.MediaAsset;
import com.app.modules.message.dto.request.CreateDirectConversationRequest;
import com.app.modules.message.dto.response.ConversationResponse;
import com.app.modules.message.dto.response.ConversationSummaryResponse;
import com.app.modules.message.dto.response.MessageMediaResponse;
import com.app.modules.message.dto.response.MessageResponse;
import com.app.modules.message.dto.response.ParticipantResponse;
import com.app.modules.message.entity.Conversation;
import com.app.modules.message.entity.ConversationParticipant;
import com.app.modules.message.entity.ConversationParticipantId;
import com.app.modules.message.entity.Message;
import com.app.modules.message.mapper.MessageMapper;
import com.app.modules.message.repository.ConversationParticipantRepository;
import com.app.modules.message.repository.ConversationRepository;
import com.app.modules.message.repository.ConversationUnreadCount;
import com.app.modules.message.repository.MessageMediaAssetRepository;
import com.app.modules.message.repository.MessageRepository;
import com.app.modules.message.repository.MessageUserRepository;
import com.app.modules.message.repository.MessageUserSettingsRepository;
import com.app.modules.message.service.ConversationService;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.entity.User;
import com.app.modules.users.entity.UserSettings;

@Service
public class ConversationServiceImpl implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ConversationRepository conversationRepository;
    private final ConversationParticipantRepository participantRepository;
    private final MessageRepository messageRepository;
    private final MessageUserRepository userRepository;
    private final MessageUserSettingsRepository userSettingsRepository;
    private final SocialService socialService;
    private final MessageMapper mapper;
    // Read-only view of the media module, mirroring MessageServiceImpl. The conversation list
    // renders the newest message, which may be an attachment.
    private final MessageMediaAssetRepository mediaAssetRepository;

    public ConversationServiceImpl(
            ConversationRepository conversationRepository,
            ConversationParticipantRepository participantRepository,
            MessageRepository messageRepository,
            MessageUserRepository userRepository,
            MessageUserSettingsRepository userSettingsRepository,
            SocialService socialService,
            MessageMapper mapper,
            MessageMediaAssetRepository mediaAssetRepository) {
        this.conversationRepository = conversationRepository;
        this.participantRepository = participantRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.userSettingsRepository = userSettingsRepository;
        this.socialService = socialService;
        this.mapper = mapper;
        this.mediaAssetRepository = mediaAssetRepository;
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
        assertNotBlocked(actorId, targetId);
        assertMessageRequestAllowed(actorId, targetId);

        // Serializes concurrent createDirectConversation calls for this pair so the
        // check-then-create
        // below cannot race into two conversations; the lock is released automatically at
        // transaction
        // end, and the returned key is reused on insert as a database-level uniqueness backstop.
        String pairKey = conversationRepository.lockDirectConversationPair(actorId, targetId);

        Optional<Conversation> existing =
                conversationRepository.findDirectConversationBetween(actorId, targetId);
        Conversation conversation;
        if (existing.isPresent()) {
            conversation = existing.get();
            // Reactivate the caller's own membership if they had previously left; the other
            // participant's state is untouched.
            ConversationParticipant actorParticipant =
                    participantRepository
                            .findByIdConversationIdAndIdUserId(conversation.getId(), actorId)
                            .orElseThrow(
                                    () -> new AppException(ApiErrorCode.CONVERSATION_NOT_FOUND));
            if (actorParticipant.getLeftAt() != null) {
                actorParticipant.setLeftAt(null);
                participantRepository.save(actorParticipant);
            }
        } else {
            conversation = Conversation.builder().createdBy(actorId).directPairKey(pairKey).build();
            conversationRepository.saveAndFlush(conversation);
            participantRepository.save(newParticipant(conversation.getId(), actorId));
            participantRepository.save(newParticipant(conversation.getId(), targetId));
            log.info("Direct conversation created: conversationId={}", conversation.getId());
        }
        return assembleDetail(conversation, actorId);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<ConversationSummaryResponse> listMyConversations(
            UUID actorId, String cursor, int limit) {
        int pageSize = normalizeLimit(limit);
        ConversationCursor decoded = decodeCursor(cursor);
        PageRequest page = PageRequest.of(0, pageSize + 1);
        List<Conversation> conversations =
                decoded.isEmpty()
                        ? conversationRepository.findFirstMyConversations(actorId, page)
                        : conversationRepository.findMyConversationsBefore(
                                actorId, decoded.lastMessageAt(), decoded.conversationId(), page);
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
        // page only; see ConversationRepository.findMyPinnedConversations.
        List<Conversation> pinned =
                decoded.isEmpty()
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
        Map<UUID, MessageResponse> lastMessageByConversation =
                lastMessages.stream()
                        .collect(
                                Collectors.toMap(
                                        Message::getConversationId,
                                        m ->
                                                mapper.toMessageResponse(
                                                        m, mediaFor(lastMessageMedia, m))));

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
                                    return mapper.toSummaryResponse(
                                            c,
                                            participantsByConversation.getOrDefault(
                                                    c.getId(), List.of()),
                                            unreadByConversation.getOrDefault(c.getId(), 0L),
                                            lastMessageByConversation.get(c.getId()),
                                            isPinned,
                                            isMuted,
                                            isManuallyUnread);
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
        fetchConversation(conversationId);
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

    private ConversationParticipant fetchActiveParticipant(UUID conversationId, UUID actorId) {
        fetchConversation(conversationId);
        return participantRepository
                .findByIdConversationIdAndIdUserId(conversationId, actorId)
                .filter(p -> p.getLeftAt() == null)
                .orElseThrow(() -> new AppException(ApiErrorCode.CONVERSATION_FORBIDDEN));
    }

    // Stealth block model: matches assemblePublicProfile's reference behaviour - a block in
    // either direction must be indistinguishable from userB not existing, not a status that
    // confirms a block relationship. Shared by direct-conversation creation, group creation, and
    // adding a participant, so a blocked target looks the same across all three.
    private void assertNotBlocked(UUID userA, UUID userB) {
        if (socialService.isBlockedBetween(userA, userB)) {
            throw new AppException(ApiErrorCode.NOT_FOUND, "Target user not found");
        }
    }

    /**
     * A target with {@code allowMessageRequests=false} only accepts new conversations from users
     * they already follow back; this is the "non-follower" gate the setting is meant to enforce.
     */
    private void assertMessageRequestAllowed(UUID actorId, UUID targetId) {
        boolean allowRequests =
                userSettingsRepository
                        .findById(targetId)
                        .map(UserSettings::isAllowMessageRequests)
                        .orElse(true);
        if (!allowRequests && !socialService.hasAcceptedFollow(targetId, actorId)) {
            throw new AppException(ApiErrorCode.MESSAGE_REQUEST_NOT_ALLOWED);
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
        return mapper.toConversationResponse(
                conversation, assembleParticipants(conversation.getId()), pinned, muted);
    }

    private List<ParticipantResponse> assembleParticipants(UUID conversationId) {
        List<ConversationParticipant> participants =
                participantRepository.findByIdConversationIdOrderByJoinedAtAsc(conversationId);
        Map<UUID, User> users = hydrateUsers(participants);
        return participants.stream()
                .map(p -> mapper.toParticipantResponse(p, users.get(p.getId().getUserId())))
                .toList();
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
