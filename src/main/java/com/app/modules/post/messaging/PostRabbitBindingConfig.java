package com.app.modules.post.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds post index-sync events and the events that produce notifications: likes, tags, invitations
 * to co-author and their acceptance, caption mentions, and a scheduled post that failed to publish.
 */
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

    @Bean
    Binding postTagCreatedNotificationBinding(
            Queue postNotificationQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(postNotificationQueue)
                .to(socialEventsExchange)
                .with(PostEventTypes.POST_TAG_CREATED_V1);
    }

    @Bean
    Binding postCollaborationInvitedNotificationBinding(
            Queue postNotificationQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(postNotificationQueue)
                .to(socialEventsExchange)
                .with(PostEventTypes.POST_COLLABORATION_INVITED_V1);
    }

    @Bean
    Binding postCollaborationAcceptedNotificationBinding(
            Queue postNotificationQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(postNotificationQueue)
                .to(socialEventsExchange)
                .with(PostEventTypes.POST_COLLABORATION_ACCEPTED_V1);
    }

    @Bean
    Binding postMentionCreatedNotificationBinding(
            Queue postNotificationQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(postNotificationQueue)
                .to(socialEventsExchange)
                .with(PostEventTypes.POST_MENTION_CREATED_V1);
    }

    @Bean
    Binding postScheduleFailedNotificationBinding(
            Queue postNotificationQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(postNotificationQueue)
                .to(socialEventsExchange)
                .with(PostEventTypes.POST_SCHEDULE_FAILED_V1);
    }
}
