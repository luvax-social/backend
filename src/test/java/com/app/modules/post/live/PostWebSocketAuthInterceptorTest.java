package com.app.modules.post.live;

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

class PostWebSocketAuthInterceptorTest {

    private PostRepository postRepository;
    private PostVisibilityService postVisibilityService;
    private PostWebSocketAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        postRepository = mock(PostRepository.class);
        postVisibilityService = mock(PostVisibilityService.class);
        interceptor = new PostWebSocketAuthInterceptor(postRepository, postVisibilityService);
    }

    @Test
    void preSend_subscribeWithoutPrincipal_throwsNotPermittedWithoutVisibilityLookup() {
        UUID postId = UUID.randomUUID();
        when(postRepository.findById(postId)).thenReturn(Optional.of(mock(Post.class)));
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/posts." + postId + ".likes");
        accessor.setSessionAttributes(new HashMap<>());
        Message<byte[]> message =
                MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, mock(MessageChannel.class)))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Subscription not permitted");
        verifyNoInteractions(postVisibilityService);
    }
}
