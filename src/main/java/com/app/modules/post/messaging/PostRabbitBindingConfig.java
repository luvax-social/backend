package com.app.modules.post.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds post index-sync events and the like events that produce notifications. */
@Configuration
public class PostRabbitBindingConfig {

    @Bean
    Binding postIndexSyncBinding(Queue postIndexSyncQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(postIndexSyncQueue)
                .to(socialEventsExchange)
                .with("post.index.#");
    }

    @Bean
    Binding postLikedNotificationBinding(
            Queue postNotificationQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(postNotificationQueue)
                .to(socialEventsExchange)
                .with(PostEventTypes.POST_LIKED_V1);
    }

    @Bean
    Binding postUnlikedNotificationBinding(
            Queue postNotificationQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(postNotificationQueue)
                .to(socialEventsExchange)
                .with(PostEventTypes.POST_UNLIKED_V1);
    }
}
