package com.app.modules.message.controller;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.app.common.ApiConstants;
import com.app.common.base.BaseController;
import com.app.common.enums.ApiSuccessCode;
import com.app.common.response.ApiResponse;
import com.app.common.response.CursorPageResponse;
import com.app.common.security.util.SecurityUtils;
import com.app.modules.message.api.MessageApi;
import com.app.modules.message.dto.request.CreateDirectConversationRequest;
import com.app.modules.message.dto.request.MessageReactionRequest;
import com.app.modules.message.dto.request.SendMessageRequest;
import com.app.modules.message.dto.request.SetNicknameRequest;
import com.app.modules.message.dto.response.ConversationResponse;
import com.app.modules.message.dto.response.ConversationSummaryResponse;
import com.app.modules.message.dto.response.MessageReactionResponse;
import com.app.modules.message.dto.response.MessageResponse;
import com.app.modules.message.dto.response.UnreadCountResponse;
import com.app.modules.message.enums.ConversationFolder;
import com.app.modules.message.service.ConversationService;
import com.app.modules.message.service.MessageReactionService;
import com.app.modules.message.service.MessageService;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/** HTTP surface for conversation creation, membership, group management, and messaging. */
@RestController
public class MessageController extends BaseController implements MessageApi {

    private final ConversationService conversationService;
    private final MessageService messageService;
    private final MessageReactionService reactionService;

    public MessageController(
            ConversationService conversationService,
            MessageService messageService,
            MessageReactionService reactionService) {
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.reactionService = reactionService;
    }

    /** Starts or reuses a 1-1 conversation with the target user; returns 201. */
    @Override
    @PostMapping(ApiConstants.Messages.ROOT)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<ConversationResponse>> createDirectConversation(
            @Valid @RequestBody CreateDirectConversationRequest request) {
        ConversationResponse body =
                conversationService.createDirectConversation(
                        SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(ApiSuccessCode.CREATED, body));
    }

    /** Lists the authenticated user's active conversations, newest activity first. */
    @Override
    @GetMapping(ApiConstants.Messages.ROOT)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<ConversationSummaryResponse>>>
            listMyConversations(
                    @RequestParam(value = "folder", defaultValue = "primary") String folder,
                    @RequestParam(value = "cursor", required = false) String cursor,
                    @RequestParam(value = "limit", defaultValue = "20") int limit) {
        CursorPageResponse<ConversationSummaryResponse> body =
                conversationService.listMyConversations(
                        SecurityUtils.getCurrentUserId(),
                        ConversationFolder.fromParam(folder),
                        cursor,
                        limit);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Returns one conversation's detail for an active participant. */
    @Override
    @GetMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.BY_ID)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<ConversationResponse>> getConversation(
            @PathVariable("conversationId") UUID conversationId) {
        ConversationResponse body =
                conversationService.getConversation(
                        SecurityUtils.getCurrentUserId(), conversationId);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Deletes a conversation from the caller's own inbox, leaving the other side untouched. */
    @Override
    @DeleteMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.BY_ID)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<Void>> leaveConversation(
            @PathVariable("conversationId") UUID conversationId) {
        conversationService.leaveConversation(SecurityUtils.getCurrentUserId(), conversationId);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK));
    }

    /** Sends a message into a conversation for an active participant; returns 201. */
    @Override
    @PostMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.CONVERSATION_MESSAGES)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<MessageResponse>> sendMessage(
            @PathVariable("conversationId") UUID conversationId,
            @Valid @RequestBody SendMessageRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        MessageResponse body =
                messageService.sendMessage(
                        SecurityUtils.getCurrentUserId(), conversationId, request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(ApiSuccessCode.CREATED, body));
    }

    /** Lists a conversation's message history for an active participant, newest first. */
    @Override
    @GetMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.CONVERSATION_MESSAGES)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<CursorPageResponse<MessageResponse>>> listHistory(
            @PathVariable("conversationId") UUID conversationId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        CursorPageResponse<MessageResponse> body =
                messageService.listHistory(
                        SecurityUtils.getCurrentUserId(), conversationId, cursor, limit);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Soft-deletes a message the authenticated caller sent. */
    @Override
    @DeleteMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.MESSAGE_BY_ID)
    @RateLimiter(name = "lowTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<Void>> deleteMessage(
            @PathVariable("conversationId") UUID conversationId,
            @PathVariable("messageId") UUID messageId) {
        messageService.deleteMessage(SecurityUtils.getCurrentUserId(), conversationId, messageId);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK));
    }

    /** Marks a conversation read for the authenticated caller as of now. */
    @Override
    @PostMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.READ)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<Void>> markRead(
            @PathVariable("conversationId") UUID conversationId) {
        messageService.markRead(SecurityUtils.getCurrentUserId(), conversationId);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK));
    }

    /** Clears the authenticated caller's read marker for a conversation. */
    @Override
    @PostMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.UNREAD)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<Void>> markUnread(
            @PathVariable("conversationId") UUID conversationId) {
        messageService.markUnread(SecurityUtils.getCurrentUserId(), conversationId);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK));
    }

    /** Returns the authenticated caller's total unread message count. */
    @Override
    @GetMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.UNREAD_COUNT)
    @RateLimiter(name = "highTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<UnreadCountResponse>> getUnreadCount() {
        UUID actorId = SecurityUtils.getCurrentUserId();
        long count = messageService.getUnreadCount(actorId);
        long requestCount = messageService.getRequestCount(actorId);
        return ResponseEntity.ok(
                ApiResponse.success(
                        ApiSuccessCode.OK, new UnreadCountResponse(count, requestCount)));
    }

    /** Pins a conversation to the top of the authenticated caller's own conversation list. */
    @Override
    @PostMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.PIN)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<Void>> pinConversation(
            @PathVariable("conversationId") UUID conversationId) {
        conversationService.pinConversation(SecurityUtils.getCurrentUserId(), conversationId);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK));
    }

    /** Unpins a conversation for the authenticated caller. */
    @Override
    @DeleteMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.PIN)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<Void>> unpinConversation(
            @PathVariable("conversationId") UUID conversationId) {
        conversationService.unpinConversation(SecurityUtils.getCurrentUserId(), conversationId);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK));
    }

    /** Mutes a conversation's notifications for the authenticated caller. */
    @Override
    @PostMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.MUTE)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<Void>> muteConversation(
            @PathVariable("conversationId") UUID conversationId) {
        conversationService.muteConversation(SecurityUtils.getCurrentUserId(), conversationId);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK));
    }

    /** Unmutes a conversation for the authenticated caller. */
    @Override
    @DeleteMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.MUTE)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<Void>> unmuteConversation(
            @PathVariable("conversationId") UUID conversationId) {
        conversationService.unmuteConversation(SecurityUtils.getCurrentUserId(), conversationId);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK));
    }

    /** Sets or clears the authenticated caller's private label for the other participant. */
    @Override
    @PutMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.NICKNAME)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<Void>> setNickname(
            @PathVariable("conversationId") UUID conversationId,
            @Valid @RequestBody SetNicknameRequest request) {
        conversationService.setNickname(
                SecurityUtils.getCurrentUserId(), conversationId, request.nickname());
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK));
    }

    /** Accepts a message request the authenticated caller received. */
    @Override
    @PostMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.ACCEPT)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<ConversationResponse>> acceptRequest(
            @PathVariable("conversationId") UUID conversationId) {
        ConversationResponse body =
                conversationService.acceptRequest(SecurityUtils.getCurrentUserId(), conversationId);
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Declines and deletes a message request the authenticated caller received; returns 204. */
    @Override
    @DeleteMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.REQUEST)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<Void> deleteRequest(@PathVariable("conversationId") UUID conversationId) {
        conversationService.deleteRequest(SecurityUtils.getCurrentUserId(), conversationId);
        return ResponseEntity.noContent().build();
    }

    /** Declines a message request and blocks its sender in one transaction; returns 204. */
    @Override
    @PostMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.BLOCK)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<Void> blockRequester(
            @PathVariable("conversationId") UUID conversationId) {
        conversationService.blockRequester(SecurityUtils.getCurrentUserId(), conversationId);
        return ResponseEntity.noContent().build();
    }

    /** Sets or changes the authenticated caller's emoji reaction to a message. */
    @Override
    @PutMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.REACTION)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<ApiResponse<MessageReactionResponse>> reactToMessage(
            @PathVariable("conversationId") UUID conversationId,
            @PathVariable("messageId") UUID messageId,
            @RequestBody MessageReactionRequest request) {
        MessageReactionResponse body =
                reactionService.react(
                        SecurityUtils.getCurrentUserId(),
                        conversationId,
                        messageId,
                        request == null ? null : request.emoji());
        return ResponseEntity.ok(ApiResponse.success(ApiSuccessCode.OK, body));
    }

    /** Removes the authenticated caller's reaction to a message; returns 204 either way. */
    @Override
    @DeleteMapping(ApiConstants.Messages.ROOT + ApiConstants.Messages.REACTION)
    @RateLimiter(name = "mediumTraffic", fallbackMethod = "rateLimit")
    public ResponseEntity<Void> removeMessageReaction(
            @PathVariable("conversationId") UUID conversationId,
            @PathVariable("messageId") UUID messageId) {
        reactionService.unreact(SecurityUtils.getCurrentUserId(), conversationId, messageId);
        return ResponseEntity.noContent().build();
    }
}
