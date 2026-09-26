package com.app.modules.admin.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the moderation events that carry a side effect to their own queues.
 *
 * <p>The in-app notification and the outbound mail are bound separately, on different routing keys
 * and to different queues, so either can be disabled without touching the other.
 */
@Configuration
public class AdminRabbitBindingConfig {

    @Bean
    Binding userWarnedNotificationBinding(
            Queue adminNotificationQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(adminNotificationQueue)
                .to(socialEventsExchange)
                .with(AdminEventTypes.USER_WARNED_V1);
    }

    @Bean
    Binding moderationNoticeMailBinding(
            Queue moderationMailQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(moderationMailQueue)
                .to(socialEventsExchange)
                .with(AdminEventTypes.MODERATION_NOTICE_REQUESTED_V1);
    }
}
