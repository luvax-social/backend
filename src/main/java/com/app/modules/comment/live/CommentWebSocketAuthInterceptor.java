package com.app.modules.comment.live;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import com.app.common.security.user.UserPrincipal;
import com.app.common.security.websocket.JwtHandshakeInterceptor;
import com.app.modules.post.entity.Post;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.service.PostVisibilityService;

/**
 * Enforces per-post visibility on every STOMP frame that names a post: SUBSCRIBE to a comment
 * topic, and SEND to {@code /app/watch/{postId}} or {@code /app/heartbeat/{postId}}.
 *
 * <p>SEND is guarded because both handlers it covers have a caller-visible side effect on a post
 * the caller may not be entitled to see: {@code watch} registers presence and publishes cached
 * comment content to the post's topic, and {@code heartbeat} extends the presence TTL. {@code
 * unwatch} is deliberately left unguarded - it only removes the caller's own session id from a set,
 * so an unauthorised caller reaching it can only produce the outcome already wanted, and guarding
 * it would leave a legitimate watcher who is blocked mid-session unable to cleanly unregister.
 *
 * <p>SUBSCRIBE is deny-by-default within its own destination prefix: a destination starting with
 * {@code /topic/comments.} that does not match the full per-post pattern is rejected rather than
 * passed through, closing the previous fail-open. This does not extend to a blanket deny over all
 * of {@code /topic/**}; this interceptor is scoped to the comment module and must not become
 * authoritative over another module's topics.
 *
 * <p>Unconditional (not gated on {@code app.comment.live.enabled}) so the shared inbound channel
 * config can depend on it regardless of which WebSocket surface is enabled; it is inert for any
 * destination outside {@code /topic/comments.*} and {@code /app/watch|heartbeat/*}.
 */
@Component
public class CommentWebSocketAuthInterceptor implements ChannelInterceptor {

    private static final String SUBSCRIBE_PREFIX = "/topic/comments.";
    private static final Pattern SUBSCRIBE_DESTINATION =
            Pattern.compile("/topic/comments\\.([0-9a-fA-F\\-]{36})\\..+");
    private static final Pattern GUARDED_SEND_DESTINATION =
            Pattern.compile("/app/(?:watch|heartbeat)/([0-9a-fA-F\\-]{36})");

    private final PostRepository postRepository;
    private final PostVisibilityService postVisibilityService;

    public CommentWebSocketAuthInterceptor(
            PostRepository postRepository, PostVisibilityService postVisibilityService) {
        this.postRepository = postRepository;
        this.postVisibilityService = postVisibilityService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return authoriseSubscribe(message, accessor);
        }
        if (StompCommand.SEND.equals(accessor.getCommand())) {
            return authoriseSend(message, accessor);
        }
        return message;
    }

    private Message<?> authoriseSubscribe(Message<?> message, StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(SUBSCRIBE_PREFIX)) {
            return message;
        }
        Matcher matcher = SUBSCRIBE_DESTINATION.matcher(destination);
        if (!matcher.matches()) {
            // Deny-by-default within this prefix: closes the fail-open for any destination that
            // shares the comment-topic prefix but does not match the full per-post pattern.
            throw new MessageDeliveryException(message, "Subscription not permitted");
        }
        checkVisibility(message, accessor, UUID.fromString(matcher.group(1)));
        return message;
    }

    private Message<?> authoriseSend(Message<?> message, StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) {
            return message;
        }
        Matcher matcher = GUARDED_SEND_DESTINATION.matcher(destination);
        if (!matcher.matches()) {
            return message;
        }
        checkVisibility(message, accessor, UUID.fromString(matcher.group(1)));
        return message;
    }

    // A missing post and a post hidden by block or private-follow rules throw the identical
    // MessageDeliveryException message: the stealth block model requires a blocked subscriber to
    // be unable to distinguish "post does not exist" from "post exists but you are blocked" from
    // the STOMP ERROR frame, the same as every REST surface this interceptor's checks mirror.
    private void checkVisibility(Message<?> message, StompHeaderAccessor accessor, UUID postId) {
        UUID viewerId = resolveViewer(accessor);
        Post post = postRepository.findById(postId).orElse(null);
        if (viewerId == null
                || post == null
                || !postVisibilityService.isVisibleTo(viewerId, post)) {
            throw new MessageDeliveryException(message, "Subscription not permitted");
        }
    }

    private UUID resolveViewer(StompHeaderAccessor accessor) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        Object principal =
                attributes == null
                        ? null
                        : attributes.get(JwtHandshakeInterceptor.PRINCIPAL_ATTRIBUTE);
        if (principal instanceof UserPrincipal userPrincipal) {
            return userPrincipal.userId();
        }
        return null;
    }
}
