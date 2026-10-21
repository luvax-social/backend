package com.app.modules.message.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import com.app.common.security.user.UserPrincipal;
import com.app.common.security.websocket.JwtHandshakeInterceptor;
import com.app.modules.message.repository.MessageUserRepository;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserStatus;

@ExtendWith(MockitoExtension.class)
class MessageInboxAuthInterceptorTest {

    @Mock private MessageUserRepository userRepository;
    @Mock private MessageChannel channel;

    private MessageInboxAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new MessageInboxAuthInterceptor(userRepository);
    }

    @Test
    void preSend_ownInboxTopic_passesThroughUnchanged() {
        UUID userId = UUID.randomUUID();
        stubActiveAccount(userId);
        Message<?> message = subscribe("/topic/inbox." + userId, userId);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void preSend_ownInboxTopicInUpperCase_passesThroughUnchanged() {
        UUID userId = UUID.randomUUID();
        stubActiveAccount(userId);
        Message<?> message = subscribe("/topic/inbox." + userId.toString().toUpperCase(), userId);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void preSend_anotherUsersInboxTopic_throwsMessageDeliveryException() {
        UUID userId = UUID.randomUUID();
        Message<?> message = subscribe("/topic/inbox." + UUID.randomUUID(), userId);

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(MessageDeliveryException.class);
        verify(userRepository, never()).findByIdAndDeletedAtIsNullAndStatus(any(), any());
    }

    @Test
    void preSend_wildcardInboxTopic_throwsMessageDeliveryException() {
        UUID userId = UUID.randomUUID();

        for (String destination :
                new String[] {"/topic/inbox.*", "/topic/inbox.**", "/topic/inbox."}) {
            Message<?> message = subscribe(destination, userId);

            assertThatThrownBy(() -> interceptor.preSend(message, channel))
                    .isInstanceOf(MessageDeliveryException.class);
        }
    }

    @Test
    void preSend_ownIdWithTrailingSegment_throwsMessageDeliveryException() {
        UUID userId = UUID.randomUUID();
        Message<?> message = subscribe("/topic/inbox." + userId + ".extra", userId);

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void preSend_inactiveAccount_throwsMessageDeliveryException() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findByIdAndDeletedAtIsNullAndStatus(userId, UserStatus.ACTIVE))
                .thenReturn(Optional.empty());
        Message<?> message = subscribe("/topic/inbox." + userId, userId);

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void preSend_noPrincipal_throwsMessageDeliveryException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/inbox." + UUID.randomUUID());
        accessor.setSessionAttributes(new HashMap<>());
        Message<?> message =
                MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void preSend_nonSubscribeCommand_passesThroughWithoutChecking() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/topic/inbox." + UUID.randomUUID());
        accessor.setSessionAttributes(new HashMap<>());
        Message<?> message =
                MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        verify(userRepository, never()).findByIdAndDeletedAtIsNullAndStatus(any(), any());
    }

    @Test
    void preSend_foreignDestination_passesThroughWithoutChecking() {
        UUID userId = UUID.randomUUID();
        Message<?> message =
                subscribe("/topic/conversations." + UUID.randomUUID() + ".messages", userId);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        verify(userRepository, never()).findByIdAndDeletedAtIsNullAndStatus(any(), any());
    }

    private void stubActiveAccount(UUID userId) {
        when(userRepository.findByIdAndDeletedAtIsNullAndStatus(userId, UserStatus.ACTIVE))
                .thenReturn(
                        Optional.of(User.builder().id(userId).status(UserStatus.ACTIVE).build()));
    }

    private static Message<?> subscribe(String destination, UUID userId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(
                JwtHandshakeInterceptor.PRINCIPAL_ATTRIBUTE,
                new UserPrincipal(userId, null, "user", "ACTIVE"));
        accessor.setSessionAttributes(attributes);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
