package com.app.common.security.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/**
 * Deny-by-default guard on every client SUBSCRIBE that names a pattern instead of one concrete
 * destination.
 *
 * <p>Each module's SUBSCRIBE interceptor judges only destinations under its own prefix, because the
 * concrete address is what carries the authorization (an account's own id, a conversation the
 * account takes part in). The simple broker, however, treats a subscription destination containing
 * {@code *} or {@code ?} as an Ant pattern and matches it against every concrete topic the server
 * publishes to, so a subscription to {@code /topic/*} or {@code /topic/conversations.*.messages}
 * begins with no guarded prefix, passes every module guard, and receives other accounts'
 * notification, inbox, message and comment frames. No legitimate client subscribes to a pattern,
 * since every topic the application uses is addressed by id, so the whole class is refused here
 * rather than taught to each module guard.
 *
 * <p>The characters refused are those the broker's path matcher or a STOMP relay could expand: the
 * Ant wildcards {@code *} and {@code ?}, the AMQP-style multi-level wildcard {@code #}, and the
 * URI-template braces. The check ignores the destination prefix so a pattern under any prefix is
 * refused. Registered immediately after {@link BrokerSendGuardInterceptor}, ahead of every module
 * interceptor, and applies to every STOMP endpoint because they share one inbound channel.
 */
@Component
public class WildcardSubscriptionGuardInterceptor implements ChannelInterceptor {

    private static final Logger log =
            LoggerFactory.getLogger(WildcardSubscriptionGuardInterceptor.class);

    private static final String PATTERN_CHARACTERS = "*?#{}";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (!StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return message;
        }
        String destination = accessor.getDestination();
        if (destination == null || !containsPatternCharacter(destination)) {
            return message;
        }
        // The destination is client-controlled and may carry line breaks, so it is not logged.
        log.warn("Wildcard subscription rejected: sessionId={}", accessor.getSessionId());
        throw new MessageDeliveryException(message, "Subscription not permitted");
    }

    private static boolean containsPatternCharacter(String destination) {
        for (int i = 0; i < destination.length(); i++) {
            if (PATTERN_CHARACTERS.indexOf(destination.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }
}
