package com.app.modules.message.config;

import java.util.Arrays;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import com.app.common.security.config.CorsProperties;
import com.app.common.security.websocket.JwtHandshakeInterceptor;
import com.app.modules.message.live.MessageWebSocketAuthInterceptor;

/**
 * STOMP/SockJS WebSocket configuration for real-time message delivery.
 *
 * <p>Authentication uses the application-wide {@link JwtHandshakeInterceptor} rather than a
 * module-local copy, so this endpoint cannot drift from the REST path on what counts as an
 * authenticated caller. The shared interceptor also records the raw token in the handshake
 * attributes, which is what enrols the session for the periodic revocation sweep. A module-local
 * interceptor previously stored only the resolved principal, so a direct-message socket was never
 * enrolled and survived logout, ban, and suspension until its access token expired.
 *
 * <p>The channel interceptor authorizes each SUBSCRIBE against active conversation membership.
 * Active only when {@code app.message.live.enabled} is true.
 *
 * <p>Contributes only this module's endpoint and its SUBSCRIBE authorization. The broker itself,
 * the destination prefixes, the send guard, and the session-tracking decorator all belong to {@link
 * com.app.common.config.websocket.WebSocketBrokerConfig}, which is guaranteed to be active whenever
 * this configuration is. Declaring {@code @EnableWebSocketMessageBroker} and a second {@code
 * configureMessageBroker} here duplicated broker-wide setup that only one class may own.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.message.live", name = "enabled", havingValue = "true")
public class MessageWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtHandshakeInterceptor handshakeInterceptor;
    private final MessageWebSocketAuthInterceptor authInterceptor;
    private final CorsProperties corsProperties;

    public MessageWebSocketConfig(
            JwtHandshakeInterceptor handshakeInterceptor,
            MessageWebSocketAuthInterceptor authInterceptor,
            CorsProperties corsProperties) {
        this.handshakeInterceptor = handshakeInterceptor;
        this.authInterceptor = authInterceptor;
        this.corsProperties = corsProperties;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/messages")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns(allowedOrigins())
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }

    String[] allowedOrigins() {
        String origins = corsProperties.allowedOrigins();
        if (origins == null || origins.isBlank()) {
            return new String[] {"http://localhost:*"};
        }
        return Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
    }
}
