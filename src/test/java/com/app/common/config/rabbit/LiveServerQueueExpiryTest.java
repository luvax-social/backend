package com.app.common.config.rabbit;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;

import com.app.modules.comment.live.CommentLiveServerQueueInitializer;
import com.app.modules.message.live.MessageLiveServerQueueInitializer;
import com.app.modules.notification.live.NotificationLiveServerQueueInitializer;
import com.app.modules.post.live.PostLiveServerQueueInitializer;

class LiveServerQueueExpiryTest {

    @Test
    void everyLiveServerQueue_autoDeletesAndExpiresWhenIdle() throws Exception {
        List<Object> initializers =
                List.of(
                        new CommentLiveServerQueueInitializer(),
                        new MessageLiveServerQueueInitializer(),
                        new NotificationLiveServerQueueInitializer(),
                        new PostLiveServerQueueInitializer());

        for (Object initializer : initializers) {
            Queue queue = declaredQueue(initializer);

            assertThat(queue.isAutoDelete()).as(queue.getName()).isTrue();
            assertThat(queue.getArguments())
                    .as(queue.getName())
                    .containsEntry(
                            "x-expires", RabbitMqTopologyConfig.LIVE_SERVER_QUEUE_EXPIRES_MILLIS);
        }
    }

    // Selected by return type rather than by position: declared-method order is unspecified.
    private static Queue declaredQueue(Object initializer) throws Exception {
        Method method =
                Arrays.stream(initializer.getClass().getDeclaredMethods())
                        .filter(candidate -> candidate.getReturnType() == Queue.class)
                        .filter(candidate -> candidate.getParameterCount() == 0)
                        .filter(candidate -> !candidate.isSynthetic())
                        .findFirst()
                        .orElseThrow();
        method.setAccessible(true);
        return (Queue) method.invoke(initializer);
    }
}
