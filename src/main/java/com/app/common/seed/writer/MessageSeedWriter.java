package com.app.common.seed.writer;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.app.common.seed.loader.SeedContent;
import com.app.common.seed.model.ConversationSeed;
import com.app.common.seed.model.MessageRequestSeed;
import com.app.common.seed.model.MessageSeed;
import com.app.common.seed.model.ReactionSeed;
import com.app.common.seed.model.ReplyParentSeed;
import com.app.common.seed.outbox.SeedOutboxBatchWriter.UserEventImport;
import com.app.common.seed.time.SeedTimeline;
import com.app.modules.recommendation.enums.UserEventType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds {@code conversations} (strictly 2-participant, one row per {@code conversations.json}
 * entry), {@code conversation_participants} and {@code messages} from {@code conversations.json}.
 *
 * <p><b>Column set</b>: the running schema (V50) removed {@code is_group}/{@code group_name}/{@code
 * group_avatar_url} from {@code conversations} - group conversations were a product decision that
 * was reversed, and every group-shaped row now lives in {@code archived_group_conversations}
 * instead. This writer never references those three columns; every conversation it inserts is the
 * live 2-participant shape, matching {@code direct_pair_key}'s uniqueness constraint.
 *
 * <p><b>{@code last_message_at}</b>: computed only after every one of a conversation's messages has
 * been inserted (the newest {@code created_at} among its non-deleted messages, {@code null} if
 * every message in the conversation is sender-deleted or the conversation has none), never guessed
 * up front, matching the invariant this table's derived-data contract states.
 *
 * <p><b>Reply quotes and reactions</b>: the {@code reply_parents} of {@code
 * safety/interaction_control.json} set {@code reply_to_id} on messages of {@code
 * conversations.json}, and its {@code reactions} become {@code message_reactions} rows dated
 * minutes after the message they react to, including reactions on messages a sender deleted or a
 * moderator removed. A reaction marked undone writes no row.
 *
 * <p><b>Message requests</b>: each {@code message_requests} entry becomes a conversation between
 * two accounts that follow neither way, with the requester's one text message. A pending or hidden
 * request waits for its recipient. A declined one has the state the real delete leaves: the request
 * marked declined, the resolution time set and the recipient's own membership ended, and nothing a
 * sender can read differs from a pending request. An accepted one carries the recipient's reply,
 * which quotes the request.
 *
 * <p><b>Two independent tombstones</b> (see {@code message/DATA_RULES.md} Section 1 and the
 * Domain-Specific Invariants in {@code STRUCT.md}): a sender's own deletion sets {@code is_deleted
 * = TRUE} and {@code deleted_at}, and clears {@code content}, which is irreversible by design. An
 * administrative removal sets {@code admin_removed_at} alone and preserves {@code content} so a
 * restore can return the message. The two are independent - this writer applies whichever one (or
 * neither, or in principle both) {@code conversations.json}'s per-message {@code is_deleted} /
 * {@code admin_removed_at_offset_minutes} fields encode.
 */
@Slf4j
@Service
@Profile("seed & (dev | prod)")
@RequiredArgsConstructor
public class MessageSeedWriter {

    private static final String INSERT_CONVERSATION_SQL =
            "INSERT INTO conversations (id, direct_pair_key, created_by, created_at, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?)";
    private static final String INSERT_PARTICIPANT_SQL =
            "INSERT INTO conversation_participants (conversation_id, user_id, joined_at,"
                    + " is_manually_unread, nickname) VALUES (?, ?, ?, ?, ?)";
    private static final String INSERT_MESSAGE_SQL =
            "INSERT INTO messages (id, conversation_id, sender_id, message_type, content,"
                    + " media_asset_id, shared_post_id, shared_story_id, is_deleted, deleted_at,"
                    + " admin_removed_at, created_at, reply_to_id) VALUES (?, ?, ?, ?::message_type, ?, ?, ?, ?,"
                    + " ?, ?, ?, ?, ?)";
    private static final String INSERT_REACTION_SQL =
            "INSERT INTO message_reactions (message_id, conversation_id, user_id, emoji,"
                    + " reacted_at) VALUES (?, ?, ?, ?, ?)";
    private static final String INSERT_REQUEST_CONVERSATION_SQL =
            "INSERT INTO conversations (id, direct_pair_key, created_by, last_message_at,"
                    + " created_at, updated_at, request_status, request_recipient_id, request_hidden,"
                    + " request_resolved_at) VALUES (?, ?, ?, ?, ?, ?, ?::conversation_request_status,"
                    + " ?, ?, ?)";
    private static final String INSERT_REQUEST_PARTICIPANT_SQL =
            "INSERT INTO conversation_participants (conversation_id, user_id, joined_at, left_at,"
                    + " last_read_at) VALUES (?, ?, ?, ?, ?)";
    private static final String INSERT_REQUEST_MESSAGE_SQL =
            "INSERT INTO messages (id, conversation_id, sender_id, message_type, content, created_at,"
                    + " reply_to_id) VALUES (?, ?, ?, 'text'::message_type, ?, ?, ?)";
    private static final String COUNT_PAIR_RELATIONS_SQL =
            "SELECT (SELECT COUNT(*) FROM follows WHERE (follower_id = ? AND following_id = ?) OR"
                    + " (follower_id = ? AND following_id = ?)) + (SELECT COUNT(*) FROM blocks WHERE"
                    + " (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?))";
    private static final String UPDATE_LAST_MESSAGE_AT_SQL =
            "UPDATE conversations SET last_message_at = ? WHERE id = ?";
    private static final String UPDATE_LAST_READ_AT_SQL =
            "UPDATE conversation_participants SET last_read_at = ?"
                    + " WHERE conversation_id = ? AND user_id = ?";

    // How long after a message the first reaction to it lands, and how far the reactions spread
    // out, so reactions on different messages do not all arrive the same number of minutes later.
    private static final int REACTION_MIN_DELAY_MINUTES = 4;
    private static final int REACTION_DELAY_SPREAD_MINUTES = 55;

    private final JdbcTemplate jdbc;
    private final InteractionSignalLedger signalLedger;

    /**
     * Inserts every conversation from {@code conversations.json} with its participants and
     * messages, then back-fills each conversation's {@code last_message_at} and each read
     * participant's {@code last_read_at} once every message is known.
     *
     * @param usersByUsername username-to-id map produced by {@link UserSeedWriter#write}
     * @param mediaByCompositeKey composite-key map produced by {@link MediaSeedWriter#write},
     *     resolving an image/video message's {@code media_ref} (owned by its sender) to a real
     *     {@code media_assets.id}
     * @param postIdBySeedId seed-id-to-generated-id map produced by {@link PostSeedWriter#write},
     *     resolving a {@code post_share} message's {@code shared_post_seed_id}
     */
    public void write(
            SeedContent content,
            Map<String, UUID> usersByUsername,
            Map<String, UUID> mediaByCompositeKey,
            Map<String, UUID> postIdBySeedId,
            SeedTimeline timeline) {
        Map<UUID, Instant> createdAtByUserId = fetchCreatedAtByUserId();
        Map<String, UUID> liveStoryIdByOwnerUsername =
                fetchLiveStoryIdByOwnerUsername(timeline.referenceNow());

        List<Object[]> conversationRows = new ArrayList<>();
        List<Object[]> participantRows = new ArrayList<>();
        List<Object[]> messageRows = new ArrayList<>();
        List<PendingConversation> pending = new ArrayList<>();
        List<Object[]> reactionRows = new ArrayList<>();
        int reactionCount = 0;
        Map<String, Map<Integer, Integer>> replyParents = replyParentsByConversation(content);
        Map<String, List<ReactionSeed>> reactions = reactionsByConversation(content);

        for (ConversationSeed conversation : content.conversations()) {
            List<String> participants = conversation.participants();
            if (participants.size() != 2) {
                throw new IllegalStateException(
                        "MessageSeedWriter: conversation '"
                                + conversation.id()
                                + "' has "
                                + participants.size()
                                + " participants - only strictly 2-participant conversations are"
                                + " supported");
            }
            String usernameA = participants.get(0);
            String usernameB = participants.get(1);
            UUID userIdA = requireUser(usersByUsername, usernameA, conversation.id());
            UUID userIdB = requireUser(usersByUsername, usernameB, conversation.id());

            Instant conversationCreatedAt =
                    timeline.conversationCreatedAt(
                            createdAtByUserId.get(userIdA), createdAtByUserId.get(userIdB));
            UUID conversationId = UUID.randomUUID();
            String directPairKey = directPairKey(userIdA, userIdB);

            conversationRows.add(
                    new Object[] {
                        conversationId,
                        directPairKey,
                        userIdA,
                        Timestamp.from(conversationCreatedAt),
                        Timestamp.from(conversationCreatedAt)
                    });

            boolean unreadA = conversation.unreadFor().contains(usernameA);
            boolean unreadB = conversation.unreadFor().contains(usernameB);
            // manuallyUnreadFor is independent of unreadFor and never touches last_read_at below,
            // matching MessageServiceImpl.markUnread - it is the purely visual V53 toggle, not a
            // second way to express "this conversation genuinely has unread messages".
            List<String> manuallyUnreadFor = nullToEmpty(conversation.manuallyUnreadFor());
            boolean manuallyUnreadA = unreadA || manuallyUnreadFor.contains(usernameA);
            boolean manuallyUnreadB = unreadB || manuallyUnreadFor.contains(usernameB);
            Map<String, String> nicknames =
                    conversation.nicknames() == null ? Map.of() : conversation.nicknames();
            participantRows.add(
                    new Object[] {
                        conversationId,
                        userIdA,
                        Timestamp.from(conversationCreatedAt),
                        manuallyUnreadA,
                        nicknames.get(usernameA)
                    });
            participantRows.add(
                    new Object[] {
                        conversationId,
                        userIdB,
                        Timestamp.from(conversationCreatedAt),
                        manuallyUnreadB,
                        nicknames.get(usernameB)
                    });

            Instant latestNonDeleted = null;
            Map<Integer, Integer> parentIndexByMessageIndex =
                    replyParents.getOrDefault(conversation.id(), Map.of());
            List<UUID> messageIds = new ArrayList<>();
            List<Instant> messageTimes = new ArrayList<>();
            for (int messageIndex = 0;
                    messageIndex < conversation.messages().size();
                    messageIndex++) {
                MessageSeed message = conversation.messages().get(messageIndex);
                UUID senderId = requireUser(usersByUsername, message.sender(), conversation.id());
                Instant messageCreatedAt =
                        timeline.messageCreatedAt(message, conversationCreatedAt);

                boolean isDeleted = message.isDeleted();
                // The sender tombstone (is_deleted + deleted_at) is set together and clears
                // content;
                // a fixed 1-second offset is enough realism for "deleted shortly after sending" and
                // needs no dedicated randomness generator since no invariant in this task depends
                // on
                // its exact spacing, only on it never preceding the message's own created_at.
                Instant deletedAt = isDeleted ? messageCreatedAt.plusSeconds(1) : null;
                Instant adminRemovedAt =
                        message.adminRemovedAtOffsetMinutes() == null
                                ? null
                                : messageCreatedAt.plusSeconds(
                                        message.adminRemovedAtOffsetMinutes() * 60L);

                // A sender's own deletion (MessageServiceImpl.deleteMessage) clears content only -
                // media_asset_id/shared_post_id/shared_story_id are left exactly as sent, and the
                // read path (MessageMapper) withholds them only when admin_removed_at is set. This
                // writer mirrors that: the three reference columns are resolved and written
                // regardless of isDeleted, matching what the real write path would leave behind.
                UUID mediaAssetId =
                        message.mediaRef() == null
                                ? null
                                : requireMediaAsset(
                                        mediaByCompositeKey,
                                        message.mediaRef(),
                                        senderId,
                                        conversation.id());
                UUID sharedPostId =
                        message.sharedPostSeedId() == null
                                ? null
                                : requireSharedPost(
                                        postIdBySeedId,
                                        message.sharedPostSeedId(),
                                        conversation.id());
                UUID sharedStoryId =
                        message.sharedStoryOwner() == null
                                ? null
                                : requireLiveStory(
                                        liveStoryIdByOwnerUsername,
                                        message.sharedStoryOwner(),
                                        conversation.id());

                UUID messageId = UUID.randomUUID();
                Integer parentIndex = parentIndexByMessageIndex.get(messageIndex);
                messageIds.add(messageId);
                messageTimes.add(messageCreatedAt);

                messageRows.add(
                        new Object[] {
                            messageId,
                            conversationId,
                            senderId,
                            message.messageType(),
                            isDeleted ? null : message.text(),
                            mediaAssetId,
                            sharedPostId,
                            sharedStoryId,
                            isDeleted,
                            deletedAt == null ? null : Timestamp.from(deletedAt),
                            adminRemovedAt == null ? null : Timestamp.from(adminRemovedAt),
                            Timestamp.from(messageCreatedAt),
                            parentIndex == null ? null : messageIds.get(parentIndex)
                        });

                if (!isDeleted
                        && (latestNonDeleted == null
                                || messageCreatedAt.isAfter(latestNonDeleted))) {
                    latestNonDeleted = messageCreatedAt;
                }
            }

            for (ReactionSeed reaction : reactions.getOrDefault(conversation.id(), List.of())) {
                signalLedger.rememberMessage(
                        conversation.id(),
                        reaction.messageIndex(),
                        messageIds.get(reaction.messageIndex()));
                UUID reactorId =
                        requireUser(usersByUsername, reaction.reactor(), conversation.id());
                UUID messageId = messageIds.get(reaction.messageIndex());
                Instant reactedAt =
                        messageTimes
                                .get(reaction.messageIndex())
                                .plus(
                                        Duration.ofMinutes(
                                                REACTION_MIN_DELAY_MINUTES
                                                        + (reactionCount++ * 7L)
                                                                % REACTION_DELAY_SPREAD_MINUTES));
                if (reactedAt.isAfter(timeline.referenceNow())) {
                    reactedAt = timeline.referenceNow();
                }
                String key =
                        conversation.id()
                                + "#"
                                + reaction.messageIndex()
                                + "#"
                                + reaction.reactor();
                signalLedger.record(
                        new UserEventImport(
                                InteractionSignalLedger.signalId("reaction", key),
                                reactorId,
                                UserEventType.MESSAGE_REACTION,
                                "message",
                                messageId,
                                "{\"emoji\":\"" + reaction.emoji() + "\"}",
                                reactedAt));
                if (reaction.undone()) {
                    Instant removedAt = reactedAt.plus(Duration.ofHours(3));
                    signalLedger.record(
                            new UserEventImport(
                                    InteractionSignalLedger.signalId("reaction_remove", key),
                                    reactorId,
                                    UserEventType.MESSAGE_REACTION_REMOVE,
                                    "message",
                                    messageId,
                                    removedAt.isAfter(timeline.referenceNow())
                                            ? timeline.referenceNow()
                                            : removedAt));
                    continue;
                }
                reactionRows.add(
                        new Object[] {
                            messageId,
                            conversationId,
                            reactorId,
                            reaction.emoji(),
                            Timestamp.from(reactedAt)
                        });
            }

            pending.add(
                    new PendingConversation(
                            conversationId, userIdA, unreadA, userIdB, unreadB, latestNonDeleted));
        }

        jdbc.batchUpdate(
                INSERT_CONVERSATION_SQL,
                conversationRows,
                conversationRows.size(),
                this::bindConversationRow);
        jdbc.batchUpdate(
                INSERT_PARTICIPANT_SQL,
                participantRows,
                participantRows.size(),
                this::bindParticipantRow);
        jdbc.batchUpdate(INSERT_MESSAGE_SQL, messageRows, messageRows.size(), this::bindMessageRow);
        jdbc.batchUpdate(INSERT_REACTION_SQL, reactionRows);
        log.info(
                "[seed] conversations: {} rows written, messages: {} rows written, reactions: {} rows"
                        + " written",
                conversationRows.size(),
                messageRows.size(),
                reactionRows.size());

        backfillLastMessageAtAndReadState(pending);
        writeMessageRequests(content, usersByUsername, createdAtByUserId, timeline);
    }

    private Map<String, Map<Integer, Integer>> replyParentsByConversation(SeedContent content) {
        Map<String, Map<Integer, Integer>> byConversation = new HashMap<>();
        for (ReplyParentSeed parent : content.interactionControl().replyParents()) {
            byConversation
                    .computeIfAbsent(parent.conversationId(), key -> new HashMap<>())
                    .put(parent.messageIndex(), parent.replyToIndex());
        }
        return byConversation;
    }

    private Map<String, List<ReactionSeed>> reactionsByConversation(SeedContent content) {
        Map<String, List<ReactionSeed>> byConversation = new HashMap<>();
        for (ReactionSeed reaction : content.interactionControl().reactions()) {
            byConversation
                    .computeIfAbsent(reaction.conversationId(), key -> new ArrayList<>())
                    .add(reaction);
        }
        return byConversation;
    }

    // Runs after the authored conversations, so a request pair that already shares one would have
    // collided on direct_pair_key; the loader rejects that before anything is written. A request
    // is written whole, with its messages, because it never changes after the one message (and the
    // reply that accepts it): last_message_at and the read marks are known up front.
    private void writeMessageRequests(
            SeedContent content,
            Map<String, UUID> usersByUsername,
            Map<UUID, Instant> createdAtByUserId,
            SeedTimeline timeline) {
        Instant referenceNow = timeline.referenceNow();
        List<Object[]> conversations = new ArrayList<>();
        List<Object[]> participants = new ArrayList<>();
        List<Object[]> messages = new ArrayList<>();
        int index = 0;
        for (MessageRequestSeed request : content.interactionControl().messageRequests()) {
            UUID requesterId = requireUser(usersByUsername, request.requester(), request.id());
            UUID recipientId = requireUser(usersByUsername, request.recipient(), request.id());
            requireStrangers(requesterId, recipientId, request.id());

            Instant earliest =
                    createdAtByUserId.get(requesterId).isAfter(createdAtByUserId.get(recipientId))
                            ? createdAtByUserId.get(requesterId)
                            : createdAtByUserId.get(recipientId);
            Instant startedAt =
                    referenceNow
                            .minus(Duration.ofDays(request.daysAgo()))
                            .minus(Duration.ofMinutes(17L * index));
            if (startedAt.isBefore(earliest.plus(Duration.ofDays(1)))) {
                startedAt = earliest.plus(Duration.ofDays(1));
            }
            Instant sentAt = startedAt.plus(Duration.ofMinutes(1));
            boolean accepted = "accepted".equals(request.status());
            boolean declined = "declined".equals(request.status());
            Instant repliedAt =
                    accepted
                            ? sentAt.plus(Duration.ofHours(3))
                                    .plus(Duration.ofMinutes(20L * (index % 5)))
                            : null;
            Instant resolvedAt =
                    accepted
                            ? repliedAt
                            : declined
                                    ? sentAt.plus(Duration.ofDays(1)).plus(Duration.ofHours(2))
                                    : null;
            if (resolvedAt != null && resolvedAt.isAfter(referenceNow)) {
                throw new IllegalStateException(
                        "MessageSeedWriter: message request '"
                                + request.id()
                                + "' would be resolved after the seed clock; give it a larger days_ago");
            }
            Instant lastMessageAt = accepted ? repliedAt : sentAt;

            UUID conversationId = UUID.randomUUID();
            UUID requestMessageId = UUID.randomUUID();
            conversations.add(
                    new Object[] {
                        conversationId,
                        directPairKey(requesterId, recipientId),
                        requesterId,
                        Timestamp.from(lastMessageAt),
                        Timestamp.from(startedAt),
                        Timestamp.from(resolvedAt == null ? lastMessageAt : resolvedAt),
                        accepted ? "accepted" : declined ? "declined" : "pending",
                        recipientId,
                        "hidden".equals(request.status()),
                        resolvedAt == null ? null : Timestamp.from(resolvedAt)
                    });
            participants.add(
                    new Object[] {
                        conversationId,
                        requesterId,
                        Timestamp.from(startedAt),
                        null,
                        Timestamp.from(sentAt)
                    });
            participants.add(
                    new Object[] {
                        conversationId,
                        recipientId,
                        Timestamp.from(startedAt),
                        declined ? Timestamp.from(resolvedAt) : null,
                        accepted
                                ? Timestamp.from(repliedAt)
                                : declined
                                        ? Timestamp.from(sentAt.plus(Duration.ofMinutes(30)))
                                        : null
                    });
            messages.add(
                    new Object[] {
                        requestMessageId,
                        conversationId,
                        requesterId,
                        request.text(),
                        Timestamp.from(sentAt),
                        null
                    });
            if (accepted) {
                messages.add(
                        new Object[] {
                            UUID.randomUUID(),
                            conversationId,
                            recipientId,
                            request.replyText(),
                            Timestamp.from(repliedAt),
                            requestMessageId
                        });
            }
            if (accepted || declined) {
                signalLedger.record(
                        new UserEventImport(
                                InteractionSignalLedger.signalId("message_request", request.id()),
                                recipientId,
                                accepted
                                        ? UserEventType.MESSAGE_REQUEST_ACCEPT
                                        : UserEventType.MESSAGE_REQUEST_DELETE,
                                "conversation",
                                conversationId,
                                accepted ? null : "{\"blocked\":false}",
                                resolvedAt));
            }
            index++;
        }
        jdbc.batchUpdate(INSERT_REQUEST_CONVERSATION_SQL, conversations);
        jdbc.batchUpdate(INSERT_REQUEST_PARTICIPANT_SQL, participants);
        jdbc.batchUpdate(INSERT_REQUEST_MESSAGE_SQL, messages);
        log.info(
                "[seed] message requests: {} conversations, {} messages written",
                conversations.size(),
                messages.size());
    }

    // A follow either way would have routed the first message past the requests folder, and a
    // block would have refused it; SocialGraphSeedWriter keeps these pairs apart, and this check
    // turns a regression there into a failed run instead of a quietly inconsistent request.
    private void requireStrangers(UUID requesterId, UUID recipientId, String requestId) {
        Integer relations =
                jdbc.queryForObject(
                        COUNT_PAIR_RELATIONS_SQL,
                        Integer.class,
                        requesterId,
                        recipientId,
                        recipientId,
                        requesterId,
                        requesterId,
                        recipientId,
                        recipientId,
                        requesterId);
        if (relations != null && relations > 0) {
            throw new IllegalStateException(
                    "MessageSeedWriter: message request '"
                            + requestId
                            + "' is between accounts that follow or block each other");
        }
    }

    // Runs only after every message row above is committed to the batch, so "the newest created_at
    // among a conversation's non-deleted messages" reflects every message that conversation will
    // ever have in this seed run, never a partial view computed before the last one was known.
    private void backfillLastMessageAtAndReadState(List<PendingConversation> pending) {
        List<Object[]> lastMessageAtRows = new ArrayList<>();
        List<Object[]> lastReadAtRows = new ArrayList<>();
        for (PendingConversation conversation : pending) {
            if (conversation.latestNonDeletedMessageAt() != null) {
                lastMessageAtRows.add(
                        new Object[] {
                            Timestamp.from(conversation.latestNonDeletedMessageAt()),
                            conversation.conversationId()
                        });
                // A participant not named in unread_for has read up to the newest message; one
                // named in unread_for keeps last_read_at unset (never read) per the JSON's own
                // is_manually_unread flag set at insert time.
                if (!conversation.unreadA()) {
                    lastReadAtRows.add(
                            new Object[] {
                                Timestamp.from(conversation.latestNonDeletedMessageAt()),
                                conversation.conversationId(),
                                conversation.userIdA()
                            });
                }
                if (!conversation.unreadB()) {
                    lastReadAtRows.add(
                            new Object[] {
                                Timestamp.from(conversation.latestNonDeletedMessageAt()),
                                conversation.conversationId(),
                                conversation.userIdB()
                            });
                }
            }
        }
        jdbc.batchUpdate(
                UPDATE_LAST_MESSAGE_AT_SQL,
                lastMessageAtRows,
                lastMessageAtRows.size(),
                this::bindLastMessageAtRow);
        jdbc.batchUpdate(
                UPDATE_LAST_READ_AT_SQL,
                lastReadAtRows,
                lastReadAtRows.size(),
                this::bindLastReadAtRow);
    }

    private UUID requireUser(
            Map<String, UUID> usersByUsername, String username, String conversationId) {
        UUID userId = usersByUsername.get(username);
        if (userId == null) {
            throw new IllegalStateException(
                    "MessageSeedWriter: conversation '"
                            + conversationId
                            + "' has participant '"
                            + username
                            + "' which does not exist in users.json");
        }
        return userId;
    }

    private UUID requireMediaAsset(
            Map<String, UUID> mediaByCompositeKey,
            String mediaRef,
            UUID senderId,
            String conversationId) {
        UUID mediaAssetId =
                mediaByCompositeKey.get(MediaSeedWriter.compositeKey(mediaRef, senderId));
        if (mediaAssetId == null) {
            throw new IllegalStateException(
                    "MessageSeedWriter: conversation '"
                            + conversationId
                            + "' has a message with media_ref '"
                            + mediaRef
                            + "' with no media_assets row for sender "
                            + senderId
                            + " - MediaSeedWriter must run before MessageSeedWriter");
        }
        return mediaAssetId;
    }

    private UUID requireSharedPost(
            Map<String, UUID> postIdBySeedId, String sharedPostSeedId, String conversationId) {
        UUID postId = postIdBySeedId.get(sharedPostSeedId);
        if (postId == null) {
            throw new IllegalStateException(
                    "MessageSeedWriter: conversation '"
                            + conversationId
                            + "' has a post_share message referencing shared_post_seed_id '"
                            + sharedPostSeedId
                            + "' which does not exist in posts.json");
        }
        return postId;
    }

    // Resolved from the DB rather than from conversations.json, since a story owner's liveness is
    // a runtime property StorySeedWriter decides (see its own Javadoc on GUARANTEED_STORY_OWNERS
    // for the same technique). Fails loudly rather than silently degrading a story_share into no
    // share at all, so an author picking a partner with no live story finds out at seed time.
    private UUID requireLiveStory(
            Map<String, UUID> liveStoryIdByOwnerUsername,
            String sharedStoryOwner,
            String conversationId) {
        UUID storyId = liveStoryIdByOwnerUsername.get(sharedStoryOwner);
        if (storyId == null) {
            throw new IllegalStateException(
                    "MessageSeedWriter: conversation '"
                            + conversationId
                            + "' has a story_share message referencing shared_story_owner '"
                            + sharedStoryOwner
                            + "' which owns no live, non-removed story - StorySeedWriter must run"
                            + " before MessageSeedWriter");
        }
        return storyId;
    }

    private List<String> nullToEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }

    private Map<UUID, Instant> fetchCreatedAtByUserId() {
        Map<UUID, Instant> createdAtByUserId = new HashMap<>();
        jdbc.query(
                "SELECT id, created_at FROM users",
                rs -> {
                    UUID id = (UUID) rs.getObject("id");
                    Instant createdAt = rs.getTimestamp("created_at").toInstant();
                    createdAtByUserId.put(id, createdAt);
                });
        return createdAtByUserId;
    }

    // One row per owner: the most recently created story that is live (not yet expired) as of the
    // seed run's own referenceNow - not SQL NOW(), since a test harness may anchor referenceNow to
    // a fixed instant in the past for determinism (see DomainWritersSeedWriterIT), and
    // StorySeedWriter computed every "live" story's expires_at relative to that same referenceNow,
    // not to the wall clock. Not administratively removed either. Read back after StorySeedWriter
    // has already run in this same seed pass, the same technique ModerationSeedWriter's
    // loadStoryOwners uses for "any story this username owns" - this query additionally requires
    // the story to still be visible.
    private Map<String, UUID> fetchLiveStoryIdByOwnerUsername(Instant referenceNow) {
        Map<String, UUID> byOwner = new HashMap<>();
        jdbc.query(
                "SELECT DISTINCT ON (u.username) u.username, s.id FROM stories s JOIN users u ON"
                        + " u.id = s.user_id WHERE s.deleted_at IS NULL AND s.expires_at > ?"
                        + " ORDER BY u.username, s.created_at DESC",
                rs -> {
                    String username = rs.getString("username");
                    UUID storyId = (UUID) rs.getObject("id");
                    byOwner.put(username, storyId);
                },
                Timestamp.from(referenceNow));
        return byOwner;
    }

    /**
     * Builds the two-participant pair key {@code direct_pair_key} is stored and looked up by: the
     * smaller UUID (by {@link UUID#compareTo}) then the larger, joined with {@code "_"}, so the key
     * is identical regardless of which participant is passed first.
     *
     * <p>Package-private so {@link ModerationSeedWriter} can rebuild the same key from a {@code
     * conversations.json} entry's participant usernames to resolve a moderation timeline's {@code
     * target_conversation_id} back to the real generated conversation.
     */
    static String directPairKey(UUID userIdA, UUID userIdB) {
        return userIdA.compareTo(userIdB) <= 0 ? userIdA + "_" + userIdB : userIdB + "_" + userIdA;
    }

    private void bindConversationRow(PreparedStatement ps, Object[] row) throws SQLException {
        ps.setObject(1, row[0]);
        ps.setString(2, (String) row[1]);
        ps.setObject(3, row[2]);
        ps.setTimestamp(4, (Timestamp) row[3]);
        ps.setTimestamp(5, (Timestamp) row[4]);
    }

    private void bindParticipantRow(PreparedStatement ps, Object[] row) throws SQLException {
        ps.setObject(1, row[0]);
        ps.setObject(2, row[1]);
        ps.setTimestamp(3, (Timestamp) row[2]);
        ps.setBoolean(4, (Boolean) row[3]);
        if (row[4] == null) {
            ps.setNull(5, Types.VARCHAR);
        } else {
            ps.setString(5, (String) row[4]);
        }
    }

    private void bindMessageRow(PreparedStatement ps, Object[] row) throws SQLException {
        ps.setObject(1, row[0]);
        ps.setObject(2, row[1]);
        ps.setObject(3, row[2]);
        ps.setString(4, (String) row[3]);
        if (row[4] == null) {
            ps.setNull(5, Types.VARCHAR);
        } else {
            ps.setString(5, (String) row[4]);
        }
        bindNullableUuid(ps, 6, (UUID) row[5]);
        bindNullableUuid(ps, 7, (UUID) row[6]);
        bindNullableUuid(ps, 8, (UUID) row[7]);
        ps.setBoolean(9, (Boolean) row[8]);
        if (row[9] == null) {
            ps.setNull(10, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            ps.setTimestamp(10, (Timestamp) row[9]);
        }
        if (row[10] == null) {
            ps.setNull(11, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            ps.setTimestamp(11, (Timestamp) row[10]);
        }
        ps.setTimestamp(12, (Timestamp) row[11]);
        bindNullableUuid(ps, 13, (UUID) row[12]);
    }

    private void bindNullableUuid(PreparedStatement ps, int index, UUID value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.OTHER);
        } else {
            ps.setObject(index, value);
        }
    }

    private void bindLastMessageAtRow(PreparedStatement ps, Object[] row) throws SQLException {
        ps.setTimestamp(1, (Timestamp) row[0]);
        ps.setObject(2, row[1]);
    }

    private void bindLastReadAtRow(PreparedStatement ps, Object[] row) throws SQLException {
        ps.setTimestamp(1, (Timestamp) row[0]);
        ps.setObject(2, row[1]);
        ps.setObject(3, row[2]);
    }

    // Carries just enough about each conversation to run the last_message_at / last_read_at
    // back-fill after every message row is known.
    private record PendingConversation(
            UUID conversationId,
            UUID userIdA,
            boolean unreadA,
            UUID userIdB,
            boolean unreadB,
            Instant latestNonDeletedMessageAt) {}
}
