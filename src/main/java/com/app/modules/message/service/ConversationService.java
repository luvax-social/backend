package com.app.modules.message.service;

import java.util.UUID;

import com.app.common.response.CursorPageResponse;
import com.app.modules.message.dto.request.CreateDirectConversationRequest;
import com.app.modules.message.dto.response.ConversationResponse;
import com.app.modules.message.dto.response.ConversationSummaryResponse;
import com.app.modules.message.enums.ConversationFolder;

/** Conversation lifecycle: creation, membership, group management, and listing. */
public interface ConversationService {

    /**
     * Starts (or reuses) a 1-1 conversation with the target user.
     *
     * <p>An existing 1-1 between the same two users is reused rather than duplicated, without
     * asking for message permission again; if the caller had previously left it, their
     * participation is reactivated. A new conversation is accepted straight away when the target
     * follows the caller and otherwise becomes a pending request in the target's requests (or
     * hidden requests) folder. A recipient who declined a request and now writes to the sender
     * starts a fresh conversation.
     *
     * @param actorId the authenticated caller
     * @param request the target user to converse with
     * @return the conversation, existing or newly created
     * @throws com.app.common.exception.AppException CONVERSATION_INVALID_PARTICIPANTS for a
     *     self-conversation; NOT_FOUND when the target does not exist, or on a block in either
     *     direction - the stealth block model requires the two to be indistinguishable;
     *     MESSAGE_REQUEST_NOT_ALLOWED when the target restricts message requests and does not
     *     already follow the caller
     */
    ConversationResponse createDirectConversation(
            UUID actorId, CreateDirectConversationRequest request);

    /**
     * Lists the caller's active conversations in one folder, newest activity first.
     *
     * <p>The primary folder holds accepted conversations and the caller's own outgoing requests;
     * the requests and hidden requests folders hold the incoming pending requests that carry a
     * message.
     *
     * @param actorId the authenticated caller
     * @param folder which folder to list
     * @param cursor opaque continuation cursor from a previous page, or null for the first page
     * @param limit requested page size, clamped between 1 and 100
     * @return a cursor page of conversation summaries with unread counts
     */
    CursorPageResponse<ConversationSummaryResponse> listMyConversations(
            UUID actorId, ConversationFolder folder, String cursor, int limit);

    /**
     * Returns one conversation's detail, including active and former participants.
     *
     * @param actorId the authenticated caller
     * @param conversationId the conversation to fetch
     * @return the conversation detail
     * @throws com.app.common.exception.AppException CONVERSATION_NOT_FOUND when missing;
     *     CONVERSATION_FORBIDDEN when the caller is not an active participant
     */
    ConversationResponse getConversation(UUID actorId, UUID conversationId);

    /**
     * Deletes a conversation for the caller only, by setting their own {@code left_at}. The other
     * participant, the conversation row, and its message history are untouched; a new message from
     * them reactivates the caller's membership, so the conversation reappears the next time it has
     * activity.
     *
     * @param actorId the authenticated caller, who must be an active participant
     * @param conversationId the conversation to leave
     * @throws com.app.common.exception.AppException CONVERSATION_NOT_FOUND when missing;
     *     CONVERSATION_FORBIDDEN when the caller is not an active participant
     */
    void leaveConversation(UUID actorId, UUID conversationId);

    /**
     * Pins a conversation to the top of the caller's own conversation list. Idempotent: pinning an
     * already-pinned conversation only refreshes its pin time.
     *
     * @param actorId the authenticated caller, who must be an active participant
     * @param conversationId the conversation to pin
     * @throws com.app.common.exception.AppException CONVERSATION_NOT_FOUND when missing;
     *     CONVERSATION_FORBIDDEN when the caller is not an active participant
     */
    void pinConversation(UUID actorId, UUID conversationId);

    /**
     * Unpins a conversation for the caller. A no-op, not an error, when it was not pinned.
     *
     * @param actorId the authenticated caller, who must be an active participant
     * @param conversationId the conversation to unpin
     * @throws com.app.common.exception.AppException CONVERSATION_NOT_FOUND when missing;
     *     CONVERSATION_FORBIDDEN when the caller is not an active participant
     */
    void unpinConversation(UUID actorId, UUID conversationId);

    /**
     * Mutes a conversation for the caller: the {@code message} notification is no longer created
     * for them from this conversation. The conversation still counts toward their unread total.
     *
     * @param actorId the authenticated caller, who must be an active participant
     * @param conversationId the conversation to mute
     * @throws com.app.common.exception.AppException CONVERSATION_NOT_FOUND when missing;
     *     CONVERSATION_FORBIDDEN when the caller is not an active participant
     */
    void muteConversation(UUID actorId, UUID conversationId);

    /**
     * Unmutes a conversation for the caller.
     *
     * @param actorId the authenticated caller, who must be an active participant
     * @param conversationId the conversation to unmute
     * @throws com.app.common.exception.AppException CONVERSATION_NOT_FOUND when missing;
     *     CONVERSATION_FORBIDDEN when the caller is not an active participant
     */
    void unmuteConversation(UUID actorId, UUID conversationId);

    /**
     * Sets or clears the caller's own private label for the other participant in this conversation.
     * Visible only to the caller; the other participant's own view is unaffected.
     *
     * @param actorId the authenticated caller, who must be an active participant
     * @param conversationId the conversation to customize
     * @param nickname the label to show, or null to clear it
     * @throws com.app.common.exception.AppException CONVERSATION_NOT_FOUND when missing;
     *     CONVERSATION_FORBIDDEN when the caller is not an active participant
     */
    void setNickname(UUID actorId, UUID conversationId, String nickname);
}
