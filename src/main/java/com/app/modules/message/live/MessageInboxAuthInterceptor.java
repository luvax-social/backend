package com.app.modules.message.live;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import com.app.common.security.user.UserPrincipal;
import com.app.common.security.websocket.JwtHandshakeInterceptor;
import com.app.modules.message.repository.MessageUserRepository;
import com.app.modules.users.enums.UserStatus;

/**
 * Enforces deny-by-default subscription authorization on every {@code /topic/inbox.*} SUBSCRIBE:
 * the destination must name exactly the handshake principal's own user id.
 *
 * <p>The inbox topic carries one account's conversation-list updates, so unlike a thread topic it
 * has no membership to check; the id in the destination is the whole address. Anything under the
 * prefix that is not exactly {@code /topic/inbox.{ownUserId}} is rejected, which covers another
 * user's id, a wildcard, a trailing segment and a malformed id alike. The account is re-checked
 * here because a socket can outlive a ban, suspension or deletion made after its handshake.
 */
@Component
@ConditionalOnProperty(prefix = "app.message.live", name = "enabled", havingValue = "true")
public class MessageInboxAuthInterceptor implements ChannelInterceptor {

    private static final String PREFIX = "/topic/inbox.";
    private static final Pattern DESTINATION =
            Pattern.compile("/topic/inbox\\.([0-9a-fA-F\\-]{36})");

    private final MessageUserRepository userRepository;

    public MessageInboxAuthInterceptor(MessageUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (!StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return message;
        }
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(PREFIX)) {
            return message;
        }
        UUID viewerId = resolveViewer(accessor);
        Matcher matcher = DESTINATION.matcher(destination);
        if (viewerId == null
                || !matcher.matches()
                || !matcher.group(1).equalsIgnoreCase(viewerId.toString())
                || userRepository
                        .findByIdAndDeletedAtIsNullAndStatus(viewerId, UserStatus.ACTIVE)
                        .isEmpty()) {
            throw new MessageDeliveryException(message, "Subscription not permitted");
        }
        return message;
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
