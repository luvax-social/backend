package com.app.modules.post.live;

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
 * Enforces per-post visibility on every STOMP SUBSCRIBE naming a post live topic.
 *
 * <p>Deny-by-default within its own destination prefix: a destination starting with {@code
 * /topic/posts.} that does not match the full per-post pattern is rejected rather than passed
 * through, so a client cannot subscribe to a topic string it constructed itself. This does not
 * extend to a blanket deny over all of {@code /topic/**}; this interceptor is scoped to the post
 * module and must not become authoritative over another module's topics.
 *
 * <p>Unconditional (not gated on {@code app.post.live.enabled}) so the shared inbound channel
 * config can depend on it regardless of which WebSocket surface is enabled; it is inert for any
 * destination outside {@code /topic/posts.*}.
 */
@Component
public class PostWebSocketAuthInterceptor implements ChannelInterceptor {

    private static final String SUBSCRIBE_PREFIX = "/topic/posts.";
    private static final Pattern SUBSCRIBE_DESTINATION =
            Pattern.compile("/topic/posts\\.([0-9a-fA-F\\-]{36})\\..+");

    private final PostRepository postRepository;
    private final PostVisibilityService postVisibilityService;

    public PostWebSocketAuthInterceptor(
            PostRepository postRepository, PostVisibilityService postVisibilityService) {
        this.postRepository = postRepository;
        this.postVisibilityService = postVisibilityService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (!StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return message;
        }
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(SUBSCRIBE_PREFIX)) {
            return message;
        }
        Matcher matcher = SUBSCRIBE_DESTINATION.matcher(destination);
        if (!matcher.matches()) {
            throw new MessageDeliveryException(message, "Subscription not permitted");
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
