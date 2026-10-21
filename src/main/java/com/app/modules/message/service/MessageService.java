package com.app.modules.message.service;

import java.util.UUID;

import com.app.common.response.CursorPageResponse;
import com.app.modules.message.dto.request.SendMessageRequest;
import com.app.modules.message.dto.response.MessageResponse;

/** Message send, history, delete, and read-state lifecycle within a conversation. */
public interface MessageService {

    /**
     * Sends a message into a conversation.
     *
     * <p>A retried request with the same {@code idempotencyKey} and identical payload replays the
     * original response instead of creating a duplicate message; the same key with a different
     * payload is rejected as a conflict.
     *
     * @param actorId the authenticated sender, who must be an active participant
     * @param conversationId the target conversation
     * @param request the message payload; required fields depend on {@code messageType}
     * @param idempotencyKey client-supplied replay key, or null to skip idempotency
     * @return the created (or replayed) message
     * @throws com.app.common.exception.AppException CONVERSATION_NOT_FOUND when missing or, for a
     *     1-1 conversation with a block relationship, stealthed as not-found;
     *     CONVERSATION_FORBIDDEN when the caller is not an active participant;
     *     MESSAGE_INVALID_PAYLOAD when the payload does not match {@code messageType} or a
     *     referenced media/post/story/reply does not exist; MESSAGE_IDEMPOTENCY_CONFLICT when the
     *     key is reused with a different payload; MESSAGE_REQUEST_AWAITING_ACCEPTANCE when the
     *     caller started a request the recipient has not accepted and has used the one text message
     *     or story reply it allows, which a declined request answers identically
     */
    MessageResponse sendMessage(
            UUID actorId, UUID conversationId, SendMessageRequest request, String idempotencyKey);

    /**
     * Lists a conversation's message history, newest first, including tombstoned messages.
     *
     * @param actorId the authenticated caller, who must be an active participant
     * @param conversationId the conversation to read
     * @param cursor opaque continuation cursor from a previous page, or null for the first page
     * @param limit requested page size, clamped between 1 and 100
     * @return a cursor page of messages
     * @throws com.app.common.exception.AppException CONVERSATION_NOT_FOUND when missing;
     *     CONVERSATION_FORBIDDEN when the caller is not an active participant
     */
    CursorPageResponse<MessageResponse> listHistory(
            UUID actorId, UUID conversationId, String cursor, int limit);

    /**
     * Soft-deletes a message the caller sent, clearing its content to a tombstone. Both {@code
     * is_deleted} and {@code deleted_at} are set; the row remains visible in history.
     *
     * @param actorId the authenticated caller, who must be the message's sender
     * @param conversationId the conversation the message belongs to
     * @param messageId the message to delete
     * @throws com.app.common.exception.AppException MESSAGE_NOT_FOUND when missing;
     *     MESSAGE_FORBIDDEN when the caller did not send it
     */
    void deleteMessage(UUID actorId, UUID conversationId, UUID messageId);

    /**
     * Marks a conversation read for the caller as of now, resetting their unread count to zero and
     * clearing any manual unread flag set via {@link #markUnread}.
     *
     * @param actorId the authenticated caller, who must be an active participant
     * @param conversationId the conversation to mark read
     * @throws com.app.common.exception.AppException CONVERSATION_NOT_FOUND when missing;
     *     CONVERSATION_FORBIDDEN when the caller is not an active participant
     */
    void markRead(UUID actorId, UUID conversationId);

    /**
     * Flags a conversation unread for the caller. This is a visual-only marker independent of the
     * counted unread total - it does not touch the caller's read marker, so it still has a visible
     * effect even when the caller sent the conversation's own newest messages. Cleared
     * automatically the next time the caller marks the conversation read.
     *
     * @param actorId the authenticated caller, who must be an active participant
     * @param conversationId the conversation to mark unread
     * @throws com.app.common.exception.AppException CONVERSATION_NOT_FOUND when missing;
     *     CONVERSATION_FORBIDDEN when the caller is not an active participant
     */
    void markUnread(UUID actorId, UUID conversationId);

    /**
     * Returns the caller's total unread message count across every active conversation, leaving out
     * incoming message requests, which {@link #getRequestCount} reports instead.
     *
     * @param actorId the authenticated caller
     * @return total unread count
     */
    long getUnreadCount(UUID actorId);

    /**
     * Returns how many message requests are waiting for the caller: incoming, pending, not filed as
     * hidden, and carrying at least one message.
     *
     * @param actorId the authenticated caller
     * @return the request count behind the requests badge
     */
    long getRequestCount(UUID actorId);
}
