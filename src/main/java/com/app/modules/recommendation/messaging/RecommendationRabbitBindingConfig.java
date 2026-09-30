package com.app.modules.recommendation.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.app.modules.comment.messaging.CommentEventTypes;
import com.app.modules.message.messaging.MessageEventTypes;
import com.app.modules.post.messaging.PostEventTypes;

/**
 * Binds engagement events consumed as recommender feedback to the recommendation queue, and
 * imported behavioural events to the queue that stores them in ClickHouse.
 */
@Configuration
public class RecommendationRabbitBindingConfig {

    @Bean
    Binding recommendationPostLikedBinding(
            Queue recommendationFeedbackQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(recommendationFeedbackQueue)
                .to(socialEventsExchange)
                .with(PostEventTypes.POST_LIKED_V1);
    }

    @Bean
    Binding recommendationPostSavedBinding(
            Queue recommendationFeedbackQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(recommendationFeedbackQueue)
                .to(socialEventsExchange)
                .with(PostEventTypes.POST_SAVED_V1);
    }

    @Bean
    Binding recommendationCommentCreatedBinding(
            Queue recommendationFeedbackQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(recommendationFeedbackQueue)
                .to(socialEventsExchange)
                .with(CommentEventTypes.COMMENT_CREATED_V1);
    }

    @Bean
    Binding recommendationPostSharedBinding(
            Queue recommendationFeedbackQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(recommendationFeedbackQueue)
                .to(socialEventsExchange)
                .with(MessageEventTypes.POST_SHARED_V1);
    }

    @Bean
    Binding recommendationCommentLikedBinding(
            Queue recommendationFeedbackQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(recommendationFeedbackQueue)
                .to(socialEventsExchange)
                .with(CommentEventTypes.COMMENT_LIKED_V1);
    }

    @Bean
    Binding userEventImportedBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(userEventImportQueue)
                .to(socialEventsExchange)
                .with(RecommendationEventTypes.USER_EVENT_IMPORTED_V1);
    }

    @Bean
    Binding recommendationPostViewedBinding(
            Queue recommendationFeedbackQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(recommendationFeedbackQueue)
                .to(socialEventsExchange)
                .with(PostEventTypes.POST_VIEWED_V1);
    }
}
