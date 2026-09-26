package com.app.modules.notification.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.app.modules.social.messaging.SocialEventTypes;
import com.app.modules.support.messaging.SupportEventTypes;

/** Binds the social-graph and identity events that change the activity feed to its queue. */
@Configuration
public class NotificationRabbitBindingConfig {

    @Bean
    Binding notificationFollowedBinding(
            Queue notificationQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(socialEventsExchange)
                .with(SocialEventTypes.USER_FOLLOWED_V1);
    }

    @Bean
    Binding notificationFollowRequestedBinding(
            Queue notificationQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(socialEventsExchange)
                .with(SocialEventTypes.USER_FOLLOW_REQUESTED_V1);
    }

    @Bean
    Binding notificationUnfollowedBinding(
            Queue notificationQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(socialEventsExchange)
                .with(SocialEventTypes.USER_UNFOLLOWED_V1);
    }

    @Bean
    Binding notificationFollowRequestApprovedBinding(
            Queue notificationQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(socialEventsExchange)
                .with(SocialEventTypes.USER_FOLLOW_REQUEST_APPROVED_V1);
    }

    @Bean
    Binding notificationFollowRequestRejectedBinding(
            Queue notificationQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(socialEventsExchange)
                .with(SocialEventTypes.USER_FOLLOW_REQUEST_REJECTED_V1);
    }

    @Bean
    Binding notificationBlockedBinding(
            Queue notificationQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(socialEventsExchange)
                .with(SocialEventTypes.USER_BLOCKED_V1);
    }

    @Bean
    Binding notificationVerificationChangedBinding(
            Queue notificationQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(socialEventsExchange)
                .with(SupportEventTypes.USER_VERIFICATION_CHANGED_V1);
    }
}
