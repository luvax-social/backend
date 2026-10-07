package com.app.modules.comment.live;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import com.app.modules.post.entity.Post;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.service.PostVisibilityService;

class CommentWebSocketAuthInterceptorTest {

    private PostRepository postRepository;
    private PostVisibilityService postVisibilityService;
    private CommentWebSocketAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        postRepository = mock(PostRepository.class);
        postVisibilityService = mock(PostVisibilityService.class);
        interceptor = new CommentWebSocketAuthInterceptor(postRepository, postVisibilityService);
    }

    @Test
    void preSend_subscribeWithoutPrincipal_throwsNotPermittedWithoutVisibilityLookup() {
        UUID postId = UUID.randomUUID();
        Message<byte[]> message =
                messageWithoutPrincipal(
                        StompCommand.SUBSCRIBE, "/topic/comments." + postId + ".new");
        when(postRepository.findById(postId)).thenReturn(Optional.of(mock(Post.class)));

        assertThatThrownBy(() -> interceptor.preSend(message, mock(MessageChannel.class)))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Subscription not permitted");
        verifyNoInteractions(postVisibilityService);
    }

    @Test
    void preSend_sendWithoutPrincipal_throwsNotPermittedWithoutVisibilityLookup() {
        UUID postId = UUID.randomUUID();
        Message<byte[]> message =
                messageWithoutPrincipal(StompCommand.SEND, "/app/watch/" + postId);
        when(postRepository.findById(postId)).thenReturn(Optional.of(mock(Post.class)));

        assertThatThrownBy(() -> interceptor.preSend(message, mock(MessageChannel.class)))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Subscription not permitted");
        verifyNoInteractions(postVisibilityService);
    }

    private static Message<byte[]> messageWithoutPrincipal(
            StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        accessor.setSessionAttributes(new HashMap<>());
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
