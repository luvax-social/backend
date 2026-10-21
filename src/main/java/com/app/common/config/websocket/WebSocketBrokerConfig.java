package com.app.common.config.websocket;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import com.app.common.security.websocket.BrokerSendGuardInterceptor;
import com.app.common.security.websocket.SessionTrackingWebSocketHandlerDecoratorFactory;
import com.app.common.security.websocket.WildcardSubscriptionGuardInterceptor;
import com.app.modules.comment.live.CommentLiveBlockFilterInterceptor;
import com.app.modules.comment.live.CommentWebSocketAuthInterceptor;
import com.app.modules.notification.live.NotificationWebSocketAuthInterceptor;
import com.app.modules.post.live.PostLiveBlockFilterInterceptor;
import com.app.modules.post.live.PostWebSocketAuthInterceptor;

import tools.jackson.databind.json.JsonMapper;

/**
 * Owns the broker-wide STOMP concerns shared by every WebSocket endpoint in the application: the
 * message broker itself, the one inbound channel's interceptors, and the session-tracking transport
 * decorator that backs revocation. Active when any live endpoint flag is true.
 *
 * <p>The condition must name every live flag. It originally omitted {@code
 * app.message.live.enabled}, so enabling messaging on its own produced a STOMP broker with neither
 * {@link BrokerSendGuardInterceptor} nor the session-tracking decorator: a client could address a
 * SEND frame straight at a {@code /topic/**} destination and be relayed to every subscriber, and no
 * session was enrolled for revocation. Adding a live endpoint therefore means adding its flag here.
 *
 * <p>There is exactly one {@code clientInboundChannel} and one simple broker regardless of how many
 * {@code registerStompEndpoints} calls contribute endpoints to it -
 * {@code @EnableWebSocketMessageBroker} must therefore be declared exactly once, and every {@link
 * WebSocketMessageBrokerConfigurer} bean's overrides are aggregated onto that single broker.
 * Keeping this declaration on a per-endpoint config (as it previously was on the comment module's
 * config) meant enabling the notification endpoint alone, with the comment endpoint left off,
 * produced no STOMP infrastructure at all: a silently non-existent endpoint. Centralizing it here,
 * gated on either flag, removes that trap.
 */
@Configuration
@EnableWebSocketMessageBroker
@ConditionalOnExpression(
        "${app.comment.live.enabled:false} or ${app.notification.live.enabled:false}"
                + " or ${app.post.live.enabled:false} or ${app.message.live.enabled:false}")
public class WebSocketBrokerConfig implements WebSocketMessageBrokerConfigurer {

    private final BrokerSendGuardInterceptor brokerSendGuardInterceptor;
    private final WildcardSubscriptionGuardInterceptor wildcardSubscriptionGuardInterceptor;
    private final CommentWebSocketAuthInterceptor commentAuthInterceptor;
    private final NotificationWebSocketAuthInterceptor notificationAuthInterceptor;
    private final SessionTrackingWebSocketHandlerDecoratorFactory sessionTrackingDecoratorFactory;
    private final CommentLiveBlockFilterInterceptor commentLiveBlockFilterInterceptor;
    private final PostWebSocketAuthInterceptor postAuthInterceptor;
    private final PostLiveBlockFilterInterceptor postLiveBlockFilterInterceptor;
    private final JsonMapper jsonMapper;

    public WebSocketBrokerConfig(
            BrokerSendGuardInterceptor brokerSendGuardInterceptor,
            WildcardSubscriptionGuardInterceptor wildcardSubscriptionGuardInterceptor,
            CommentWebSocketAuthInterceptor commentAuthInterceptor,
            NotificationWebSocketAuthInterceptor notificationAuthInterceptor,
            SessionTrackingWebSocketHandlerDecoratorFactory sessionTrackingDecoratorFactory,
            CommentLiveBlockFilterInterceptor commentLiveBlockFilterInterceptor,
            PostWebSocketAuthInterceptor postAuthInterceptor,
            PostLiveBlockFilterInterceptor postLiveBlockFilterInterceptor,
            JsonMapper jsonMapper) {
        this.brokerSendGuardInterceptor = brokerSendGuardInterceptor;
        this.wildcardSubscriptionGuardInterceptor = wildcardSubscriptionGuardInterceptor;
        this.commentAuthInterceptor = commentAuthInterceptor;
        this.notificationAuthInterceptor = notificationAuthInterceptor;
        this.sessionTrackingDecoratorFactory = sessionTrackingDecoratorFactory;
        this.commentLiveBlockFilterInterceptor = commentLiveBlockFilterInterceptor;
        this.postAuthInterceptor = postAuthInterceptor;
        this.postLiveBlockFilterInterceptor = postLiveBlockFilterInterceptor;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Serializes STOMP payloads with the application's own {@link JsonMapper} rather than the
     * private one Spring builds by default for this converter, which reads none of the {@code
     * spring.jackson.*} configuration.
     *
     * <p>This aligns the surface for any value the converter itself serializes. It does not govern
     * a domain event's {@code data}, which reaches the socket already serialized by {@code
     * DomainEventEnvelopeJson} and is re-emitted verbatim; that codec sets its own timestamp zone
     * for the same reason. Returning {@code true} keeps the framework's String and byte-array
     * converters registered after this one; the composite picks the first that can handle the
     * payload, so JSON is served from here.
     */
    @Override
    public boolean configureMessageConverters(List<MessageConverter> messageConverters) {
        messageConverters.add(new JacksonJsonMessageConverter(jsonMapper));
        return true;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes(
                BrokerSendGuardInterceptor.APPLICATION_DESTINATION_PREFIX);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // brokerSendGuardInterceptor runs first so a forged SEND at a /topic/** destination is
        // rejected before any module-specific interceptor logic runs; the wildcard guard follows
        // so a pattern SUBSCRIBE, which no module guard owns, never reaches the broker.
        registration.interceptors(
                brokerSendGuardInterceptor,
                wildcardSubscriptionGuardInterceptor,
                commentAuthInterceptor,
                notificationAuthInterceptor,
                postAuthInterceptor);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(
                commentLiveBlockFilterInterceptor, postLiveBlockFilterInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(sessionTrackingDecoratorFactory);
    }
}
