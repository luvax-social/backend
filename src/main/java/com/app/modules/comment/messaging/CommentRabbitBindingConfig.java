package com.app.modules.comment.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds comment notification-bearing events to the dedicated comment notification queue. */
@Configuration
public class CommentRabbitBindingConfig {

    @Bean
    Binding commentCreatedNotificationBinding(
            Queue commentNotificationQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(commentNotificationQueue)
                .to(socialEventsExchange)
                .with(CommentEventTypes.COMMENT_CREATED_V1);
    }

    @Bean
    Binding commentLikedNotificationBinding(
            Queue commentNotificationQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(commentNotificationQueue)
                .to(socialEventsExchange)
                .with(CommentEventTypes.COMMENT_LIKED_V1);
    }

    @Bean
    Binding commentUnlikedNotificationBinding(
            Queue commentNotificationQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(commentNotificationQueue)
                .to(socialEventsExchange)
                .with(CommentEventTypes.COMMENT_UNLIKED_V1);
    }
}
