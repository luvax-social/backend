package com.app.modules.message.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import com.app.common.ApiConstants;
import com.app.common.config.openapi.AuthenticationRequiredResponse;
import com.app.common.config.openapi.CursorErrorResponses;
import com.app.common.config.openapi.MalformedBodyErrorResponses;
import com.app.common.response.ApiResponse;
import com.app.common.response.CursorPageResponse;
import com.app.modules.message.dto.request.CreateDirectConversationRequest;
import com.app.modules.message.dto.request.MessageReactionRequest;
import com.app.modules.message.dto.request.SendMessageRequest;
import com.app.modules.message.dto.request.SetNicknameRequest;
import com.app.modules.message.dto.response.ConversationResponse;
import com.app.modules.message.dto.response.ConversationSummaryResponse;
import com.app.modules.message.dto.response.MessageReactionResponse;
import com.app.modules.message.dto.response.MessageResponse;
import com.app.modules.message.dto.response.UnreadCountResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/** OpenAPI contract for conversation creation, membership, and group management. */
@Tag(name = "Messages", description = "1-1 and group conversation lifecycle and membership")
public interface MessageApi {

    @Operation(
            summary = "Start or reuse a 1-1 conversation",
            description =
                    "Starts a 1-1 conversation with the target user, or returns the existing one"
                            + " between the same two users. Requires authentication.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "201",
                description = "Conversation created or reused"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Blocked, or target does not accept message requests from the caller",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Target user not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @MalformedBodyErrorResponses
    @AuthenticationRequiredResponse
    @PostMapping(ApiConstants.Messages.ROOT)
    ResponseEntity<ApiResponse<ConversationResponse>> createDirectConversation(
            @Valid @RequestBody CreateDirectConversationRequest request);

    @Operation(
            summary = "List the caller's conversations",
            description =
                    "Cursor-paginated list of the caller's active conversations, newest activity"
                            + " first, with unread counts. Requires authentication.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Cursor page of conversation summaries")
    })
    @CursorErrorResponses
    @AuthenticationRequiredResponse
    @GetMapping(ApiConstants.Messages.ROOT)
    ResponseEntity<ApiResponse<CursorPageResponse<ConversationSummaryResponse>>>
            listMyConversations(
                    @Parameter(
                                    description =
                                            "Folder to list: primary (default), requests or"
                                                    + " hidden_requests")
                            @RequestParam(value = "folder", defaultValue = "primary")
                            String folder,
                    @Parameter(description = "Opaque cursor from the previous page")
                            @RequestParam(value = "cursor", required = false)
                            String cursor,
                    @Parameter(description = "Page size, 1-100, default 20")
                            @RequestParam(value = "limit", defaultValue = "20")
                            @Min(1)
                            @Max(100)
                            int limit);

    @Operation(
            summary = "Get a conversation's detail",
            description =
                    "Returns one conversation's detail, including active and former participants."
                            + " Requires authentication as an active participant.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Conversation detail"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Caller is not an active participant",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Conversation not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @GetMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.BY_ID)
    ResponseEntity<ApiResponse<ConversationResponse>> getConversation(
            @PathVariable("conversationId") UUID conversationId);

    @Operation(
            summary = "Delete a conversation for the caller",
            description =
                    "Deletes a conversation from the caller's own inbox only, leaving the other"
                            + " participant and the message history untouched. A new message from"
                            + " them reactivates it. Requires authentication as an active"
                            + " participant.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Conversation deleted for the caller"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Caller is not an active participant",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Conversation not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @DeleteMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.BY_ID)
    ResponseEntity<ApiResponse<Void>> leaveConversation(
            @PathVariable("conversationId") UUID conversationId);

    @Operation(
            summary = "Send a message",
            description =
                    "Sends a message into a conversation. Required fields depend on {@code"
                            + " messageType}. A retried request with the same Idempotency-Key and"
                            + " an identical payload replays the original response. Requires"
                            + " authentication as an active participant.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "201",
                description = "Message sent (or replayed)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Caller is not an active participant, or a block applies",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description =
                        "Idempotency key reused with a different payload, or the sender of an"
                                + " unaccepted message request has already used the one message it allows",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @MalformedBodyErrorResponses
    @AuthenticationRequiredResponse
    @PostMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.CONVERSATION_MESSAGES)
    ResponseEntity<ApiResponse<MessageResponse>> sendMessage(
            @PathVariable("conversationId") UUID conversationId,
            @Valid @RequestBody SendMessageRequest request,
            @Parameter(description = "Client-supplied replay key for a retried send")
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey);

    @Operation(
            summary = "List a conversation's message history",
            description =
                    "Cursor-paginated message history, newest first, including a placeholder for a"
                            + " deleted message. Requires authentication as an active participant.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Cursor page of messages"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Caller is not an active participant",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @CursorErrorResponses
    @AuthenticationRequiredResponse
    @GetMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.CONVERSATION_MESSAGES)
    ResponseEntity<ApiResponse<CursorPageResponse<MessageResponse>>> listHistory(
            @PathVariable("conversationId") UUID conversationId,
            @Parameter(description = "Opaque cursor from the previous page")
                    @RequestParam(value = "cursor", required = false)
                    String cursor,
            @Parameter(description = "Page size, 1-100, default 20")
                    @RequestParam(value = "limit", defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int limit);

    @Operation(
            summary = "Delete a sent message",
            description =
                    "Soft-deletes a message the caller sent, clearing its content to a placeholder;"
                            + " the row remains visible in history. Requires authentication as the"
                            + " message's sender.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Message deleted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Caller did not send this message",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Message not found in this conversation",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @DeleteMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.MESSAGE_BY_ID)
    ResponseEntity<ApiResponse<Void>> deleteMessage(
            @PathVariable("conversationId") UUID conversationId,
            @PathVariable("messageId") UUID messageId);

    @Operation(
            summary = "Mark a conversation read",
            description =
                    "Marks a conversation read for the caller as of now, resetting their unread"
                            + " count to zero. Requires authentication as an active participant.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Conversation marked read"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Caller is not an active participant",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @PostMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.READ)
    ResponseEntity<ApiResponse<Void>> markRead(@PathVariable("conversationId") UUID conversationId);

    @Operation(
            summary = "Mark a conversation unread",
            description =
                    "Clears the caller's read marker for a conversation, so every message in it"
                            + " counts toward their unread total again. Requires authentication as"
                            + " an active participant.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Conversation marked unread"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Caller is not an active participant",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @PostMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.UNREAD)
    ResponseEntity<ApiResponse<Void>> markUnread(
            @PathVariable("conversationId") UUID conversationId);

    @Operation(
            summary = "Get the caller's total unread message count",
            description =
                    "Returns the caller's total unread message count across every active"
                            + " conversation. Requires authentication.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Total unread count")
    })
    @AuthenticationRequiredResponse
    @GetMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.UNREAD_COUNT)
    ResponseEntity<ApiResponse<UnreadCountResponse>> getUnreadCount();

    @Operation(
            summary = "Pin a conversation",
            description =
                    "Pins a conversation to the top of the caller's own conversation list."
                            + " Idempotent. Requires authentication as an active participant.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Conversation pinned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Caller is not an active participant",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @PostMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.PIN)
    ResponseEntity<ApiResponse<Void>> pinConversation(
            @PathVariable("conversationId") UUID conversationId);

    @Operation(
            summary = "Unpin a conversation",
            description =
                    "Unpins a conversation for the caller; a no-op if it was not pinned. Requires"
                            + " authentication as an active participant.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Conversation unpinned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Caller is not an active participant",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @DeleteMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.PIN)
    ResponseEntity<ApiResponse<Void>> unpinConversation(
            @PathVariable("conversationId") UUID conversationId);

    @Operation(
            summary = "Mute a conversation",
            description =
                    "Suppresses message notifications from this conversation for the caller. The"
                            + " conversation still counts toward their unread total. Requires"
                            + " authentication as an active participant.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Conversation muted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Caller is not an active participant",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @PostMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.MUTE)
    ResponseEntity<ApiResponse<Void>> muteConversation(
            @PathVariable("conversationId") UUID conversationId);

    @Operation(
            summary = "Unmute a conversation",
            description =
                    "Restores message notifications from this conversation for the caller."
                            + " Requires authentication as an active participant.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Conversation unmuted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Caller is not an active participant",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @DeleteMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.MUTE)
    ResponseEntity<ApiResponse<Void>> unmuteConversation(
            @PathVariable("conversationId") UUID conversationId);

    @Operation(
            summary = "Set or clear the caller's nickname for a conversation",
            description =
                    "Sets the caller's own private label for the other participant in this"
                            + " conversation, or clears it when the nickname is null or blank."
                            + " Visible only to the caller. Requires authentication as an active"
                            + " participant.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Nickname set or cleared"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Caller is not an active participant",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @MalformedBodyErrorResponses
    @AuthenticationRequiredResponse
    @PutMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.NICKNAME)
    ResponseEntity<ApiResponse<Void>> setNickname(
            @PathVariable("conversationId") UUID conversationId,
            @Valid @RequestBody SetNicknameRequest request);

    @Operation(
            summary = "Accept a message request",
            description =
                    "Accepts a message request the caller received, which moves the conversation"
                            + " into their primary folder. Requires authentication as the"
                            + " recipient of a pending request that carries a message.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "The accepted conversation"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "CONVERSATION_NOT_FOUND: no such request for the caller",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @PostMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.ACCEPT)
    ResponseEntity<ApiResponse<ConversationResponse>> acceptRequest(
            @PathVariable("conversationId") UUID conversationId);

    @Operation(
            summary = "Delete a message request",
            description =
                    "Declines a message request the caller received and removes it from their"
                            + " inbox. The sender keeps seeing a request that was never answered."
                            + " Requires authentication as the recipient of a pending request that"
                            + " carries a message.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "204",
                description = "Request deleted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "CONVERSATION_NOT_FOUND: no such request for the caller",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @DeleteMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.REQUEST)
    ResponseEntity<Void> deleteRequest(@PathVariable("conversationId") UUID conversationId);

    @Operation(
            summary = "Delete a message request and block its sender",
            description =
                    "Declines a message request the caller received and blocks its sender in one"
                            + " transaction. Requires authentication as the recipient of a"
                            + " pending request that carries a message.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "204",
                description = "Request deleted and sender blocked"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "CONVERSATION_NOT_FOUND: no such request for the caller",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @PostMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.BLOCK)
    ResponseEntity<Void> blockRequester(@PathVariable("conversationId") UUID conversationId);

    @Operation(
            summary = "React to a message",
            description =
                    "Sets or changes the caller's single emoji reaction to a message. Reacting with"
                            + " the emoji already set changes nothing. A participant of a pending"
                            + " message request may react. Requires authentication as an active"
                            + " participant.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "The caller's reaction"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "MESSAGE_REACTION_INVALID_EMOJI: not exactly one emoji",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Caller is not an active participant",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description =
                        "CONVERSATION_NOT_FOUND (missing, or blocked) or MESSAGE_NOT_FOUND"
                                + " (missing, deleted by its sender or removed by an administrator)",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @MalformedBodyErrorResponses
    @AuthenticationRequiredResponse
    @PutMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.REACTION)
    ResponseEntity<ApiResponse<MessageReactionResponse>> reactToMessage(
            @PathVariable("conversationId") UUID conversationId,
            @PathVariable("messageId") UUID messageId,
            @RequestBody MessageReactionRequest request);

    @Operation(
            summary = "Remove the caller's reaction to a message",
            description =
                    "Removes the caller's reaction. Removing a reaction that does not exist, or one"
                            + " on a message that is no longer shown, succeeds without effect."
                            + " Requires authentication as an active participant.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "204",
                description = "Reaction removed or already absent"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Caller is not an active participant",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "CONVERSATION_NOT_FOUND (missing, or blocked) or MESSAGE_NOT_FOUND",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @DeleteMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.REACTION)
    ResponseEntity<Void> removeMessageReaction(
            @PathVariable("conversationId") UUID conversationId,
            @PathVariable("messageId") UUID messageId);
}
