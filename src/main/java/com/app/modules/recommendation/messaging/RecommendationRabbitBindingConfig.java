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
import com.app.modules.social.messaging.SocialEventTypes;

/**
 * Binds engagement events consumed as recommender feedback to the recommendation queue, and
 * imported behavioural events and the interaction control signals to the queue that stores them in
 * ClickHouse.
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

    // These two keys are not in DomainSignalMappings: RecommendationFeedbackConsumer writes their
    // ClickHouse rows itself because it also sends them to Gorse. RabbitMqTopologyConfigTest fails
    // when either is unbound or reaches any other queue.
    @Bean
    Binding recommendationPostNotInterestedBinding(
            Queue recommendationFeedbackQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(recommendationFeedbackQueue)
                .to(socialEventsExchange)
                .with(PostEventTypes.POST_NOT_INTERESTED_V1);
    }

    @Bean
    Binding recommendationPostNotInterestedUndoneBinding(
            Queue recommendationFeedbackQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(recommendationFeedbackQueue)
                .to(socialEventsExchange)
                .with(PostEventTypes.POST_NOT_INTERESTED_UNDONE_V1);
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

    // The signals below are the keys of DomainSignalMappings. A signal needs one binding here and
    // one entry there; RabbitMqTopologyConfigTest fails when a mapped key has no binding to the
    // import queue.
    @Bean
    Binding accountShowFewerSignalBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return signalBinding(
                userEventImportQueue, socialEventsExchange, SocialEventTypes.USER_SHOW_FEWER_V1);
    }

    @Bean
    Binding accountShowFewerUndoSignalBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return signalBinding(
                userEventImportQueue,
                socialEventsExchange,
                SocialEventTypes.USER_SHOW_FEWER_UNDONE_V1);
    }

    @Bean
    Binding accountMuteSignalBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return signalBinding(
                userEventImportQueue, socialEventsExchange, SocialEventTypes.USER_MUTED_V1);
    }

    @Bean
    Binding accountUnmuteSignalBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return signalBinding(
                userEventImportQueue, socialEventsExchange, SocialEventTypes.USER_UNMUTED_V1);
    }

    @Bean
    Binding interactionLimitChangeSignalBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return signalBinding(
                userEventImportQueue,
                socialEventsExchange,
                SocialEventTypes.USER_INTERACTION_LIMIT_CHANGED_V1);
    }

    @Bean
    Binding commentUnhideSignalBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return signalBinding(
                userEventImportQueue, socialEventsExchange, CommentEventTypes.COMMENT_UNHIDDEN_V1);
    }

    @Bean
    Binding commentHiddenSignalBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return signalBinding(
                userEventImportQueue, socialEventsExchange, CommentEventTypes.COMMENT_HIDDEN_V1);
    }

    @Bean
    Binding messageRequestResolvedSignalBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return signalBinding(
                userEventImportQueue,
                socialEventsExchange,
                MessageEventTypes.MESSAGE_REQUEST_RESOLVED_V1);
    }

    @Bean
    Binding messageReactionSignalBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return signalBinding(
                userEventImportQueue,
                socialEventsExchange,
                MessageEventTypes.MESSAGE_REACTION_CHANGED_V1);
    }

    @Bean
    Binding postPublishedSignalBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return signalBinding(
                userEventImportQueue, socialEventsExchange, PostEventTypes.POST_PUBLISHED_V1);
    }

    @Bean
    Binding postScheduledSignalBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return signalBinding(
                userEventImportQueue, socialEventsExchange, PostEventTypes.POST_SCHEDULED_V1);
    }

    @Bean
    Binding postScheduleFailedSignalBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return signalBinding(
                userEventImportQueue, socialEventsExchange, PostEventTypes.POST_SCHEDULE_FAILED_V1);
    }

    @Bean
    Binding postDraftSavedSignalBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return signalBinding(
                userEventImportQueue, socialEventsExchange, PostEventTypes.POST_DRAFT_SAVED_V1);
    }

    @Bean
    Binding postArchivedSignalBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return signalBinding(
                userEventImportQueue, socialEventsExchange, PostEventTypes.POST_ARCHIVED_V1);
    }

    @Bean
    Binding postUnarchivedSignalBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return signalBinding(
                userEventImportQueue, socialEventsExchange, PostEventTypes.POST_UNARCHIVED_V1);
    }

    @Bean
    Binding postTagCreatedSignalBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return signalBinding(
                userEventImportQueue, socialEventsExchange, PostEventTypes.POST_TAG_CREATED_V1);
    }

    @Bean
    Binding postTagApprovedSignalBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return signalBinding(
                userEventImportQueue, socialEventsExchange, PostEventTypes.POST_TAG_APPROVED_V1);
    }

    @Bean
    Binding postTagRemovedSignalBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return signalBinding(
                userEventImportQueue, socialEventsExchange, PostEventTypes.POST_TAG_REMOVED_V1);
    }

    @Bean
    Binding postCollaborationInvitedSignalBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return signalBinding(
                userEventImportQueue,
                socialEventsExchange,
                PostEventTypes.POST_COLLABORATION_INVITED_V1);
    }

    @Bean
    Binding postCollaborationAcceptedSignalBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return signalBinding(
                userEventImportQueue,
                socialEventsExchange,
                PostEventTypes.POST_COLLABORATION_ACCEPTED_V1);
    }

    @Bean
    Binding postCollaborationDeclinedSignalBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return signalBinding(
                userEventImportQueue,
                socialEventsExchange,
                PostEventTypes.POST_COLLABORATION_DECLINED_V1);
    }

    @Bean
    Binding postCollaborationLeftSignalBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return signalBinding(
                userEventImportQueue,
                socialEventsExchange,
                PostEventTypes.POST_COLLABORATION_LEFT_V1);
    }

    @Bean
    Binding postMentionCreatedSignalBinding(
            Queue userEventImportQueue, TopicExchange socialEventsExchange) {
        return signalBinding(
                userEventImportQueue, socialEventsExchange, PostEventTypes.POST_MENTION_CREATED_V1);
    }

    private static Binding signalBinding(Queue queue, TopicExchange exchange, String routingKey) {
        return BindingBuilder.bind(queue).to(exchange).with(routingKey);
    }
}
