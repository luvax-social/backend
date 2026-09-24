package com.app.common.config.rabbit;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the active RabbitMQ topology for domain events.
 *
 * <p>Only shared broker infrastructure and active queues live here. Module-specific event bindings
 * stay with their owning module.
 */
@Configuration
public class RabbitMqTopologyConfig {

    public static final String SOCIAL_EVENTS_EXCHANGE = "social.events";
    public static final String SOCIAL_EVENTS_DEAD_LETTER_EXCHANGE = "social.events.dlx";

    public static final String MAIL_QUEUE = "mail.queue";
    public static final String MAIL_DEAD_LETTER_QUEUE = "mail.dlq";
    public static final String MAIL_DEAD_LETTER_ROUTING_KEY = "mail.dead-letter";

    public static final String MODERATION_MAIL_QUEUE = "moderation.mail.queue";
    public static final String MODERATION_MAIL_DEAD_LETTER_QUEUE = "moderation.mail.dlq";
    public static final String MODERATION_MAIL_DEAD_LETTER_ROUTING_KEY =
            "moderation.mail.dead-letter";

    public static final String NOTIFICATION_QUEUE = "notification.queue";
    public static final String NOTIFICATION_DEAD_LETTER_QUEUE = "notification.dlq";
    public static final String NOTIFICATION_DEAD_LETTER_ROUTING_KEY = "notification.dead-letter";

    public static final String HASHTAG_INDEX_SYNC_QUEUE = "hashtag.index.sync";
    public static final String HASHTAG_INDEX_SYNC_DEAD_LETTER_QUEUE = "hashtag.index.sync.dlq";
    public static final String HASHTAG_INDEX_DEAD_LETTER_ROUTING_KEY = "hashtag.index.dead-letter";

    public static final String POST_INDEX_SYNC_QUEUE = "post.index.sync";
    public static final String POST_INDEX_SYNC_DEAD_LETTER_QUEUE = "post.index.sync.dlq";
    public static final String POST_INDEX_DEAD_LETTER_ROUTING_KEY = "post.index.dead-letter";

    public static final String COMMENT_LIVE_EVENTS_EXCHANGE = "comment.live.events";
    public static final String MESSAGE_LIVE_EVENTS_EXCHANGE = "message.live.events";
    public static final String COMMENT_NOTIFICATION_QUEUE = "comment.notification.queue";
    public static final String COMMENT_NOTIFICATION_DEAD_LETTER_QUEUE = "comment.notification.dlq";
    public static final String COMMENT_NOTIFICATION_DEAD_LETTER_ROUTING_KEY =
            "comment.notification.dead-letter";

    public static final String ADMIN_NOTIFICATION_QUEUE = "admin.notification.queue";
    public static final String ADMIN_NOTIFICATION_DEAD_LETTER_QUEUE = "admin.notification.dlq";
    public static final String ADMIN_NOTIFICATION_DEAD_LETTER_ROUTING_KEY =
            "admin.notification.dead-letter";
    public static final String STORY_NOTIFICATION_QUEUE = "story.notification.queue";
    public static final String STORY_NOTIFICATION_DEAD_LETTER_QUEUE = "story.notification.dlq";
    public static final String STORY_NOTIFICATION_DEAD_LETTER_ROUTING_KEY =
            "story.notification.dead-letter";

    public static final String RECOMMENDATION_FEEDBACK_QUEUE = "recommendation.feedback.queue";
    public static final String RECOMMENDATION_FEEDBACK_DEAD_LETTER_QUEUE =
            "recommendation.feedback.dlq";
    public static final String RECOMMENDATION_FEEDBACK_DEAD_LETTER_ROUTING_KEY =
            "recommendation.feedback.dead-letter";

    /**
     * Idle lifetime of a per-instance live fanout queue: removed by the broker after this long with
     * no consumer, so a queue left by an instance that died before its listener attached does not
     * collect every live event forever.
     */
    public static final int LIVE_SERVER_QUEUE_EXPIRES_MILLIS = 60_000;

    public static final String POST_NOTIFICATION_QUEUE = "post.notification.queue";
    public static final String POST_NOTIFICATION_DEAD_LETTER_QUEUE = "post.notification.dlq";
    public static final String POST_NOTIFICATION_DEAD_LETTER_ROUTING_KEY =
            "post.notification.dead-letter";

    public static final String NOTIFICATION_LIVE_EVENTS_EXCHANGE = "notification.live.events";

    public static final String POST_LIVE_EVENTS_EXCHANGE = "post.live.events";

    public static final String AUDIT_LOG_QUEUE = "audit-log.queue";
    public static final String MODERATION_QUEUE = "moderation.queue";
    public static final String SEARCH_INDEX_QUEUE = "search-index.queue";

    @Bean
    TopicExchange socialEventsExchange() {
        return ExchangeBuilder.topicExchange(SOCIAL_EVENTS_EXCHANGE).durable(true).build();
    }

    @Bean
    TopicExchange socialEventsDeadLetterExchange() {
        return ExchangeBuilder.topicExchange(SOCIAL_EVENTS_DEAD_LETTER_EXCHANGE)
                .durable(true)
                .build();
    }

    // No x-dead-letter-exchange argument, deliberately. The broker argument only routes a message
    // that is rejected with requeue=false, expires on a TTL, or is dropped on queue overflow.
    // AuthMailEventConsumer never rejects: it publishes the failed message to social.events.dlx
    // itself through DeadLetterPublisher and then acks, and nacks with requeue=true only when that
    // publish fails. The argument would therefore never fire on this queue.
    //
    // This is the consistent rule across the topology rather than an exception to it. Every queue
    // whose consumer rejects with requeue=false carries the argument (comment, post,
    // recommendation and story notification); every queue whose consumer publishes to the
    // dead-letter exchange itself omits it (mail, notification, hashtag.index.sync,
    // post.index.sync). The one queue that breaks the rule is adminNotificationQueue below, which
    // carries the argument while AdminNotificationConsumer uses the application-level route, so
    // the argument is inert there. It is left in place because changing the arguments of an
    // existing durable queue fails redeclaration with PRECONDITION_FAILED against a live broker.
    @Bean
    Queue mailQueue() {
        return QueueBuilder.durable(MAIL_QUEUE).build();
    }

    @Bean
    Queue mailDeadLetterQueue() {
        return QueueBuilder.durable(MAIL_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding mailDeadLetterBinding(
            Queue mailDeadLetterQueue, TopicExchange socialEventsDeadLetterExchange) {
        return BindingBuilder.bind(mailDeadLetterQueue)
                .to(socialEventsDeadLetterExchange)
                .with(MAIL_DEAD_LETTER_ROUTING_KEY);
    }

    // Moderation notices travel on their own queue rather than sharing mail.queue. The auth mail
    // handler refuses any account that is not ACTIVE, which is correct for auth mail and is exactly
    // the population a moderation notice has to reach, so the two cannot share a consumer. A
    // separate queue also lets moderation mail be disabled on its own, which matters most in the
    // seed profile where a replay would otherwise send real provider mail to fabricated addresses.
    //
    // No x-dead-letter-exchange argument, following mailQueue above: this queue's consumer
    // publishes to the dead-letter exchange itself and never rejects, so the broker argument would
    // never fire.
    @Bean
    Queue moderationMailQueue() {
        return QueueBuilder.durable(MODERATION_MAIL_QUEUE).build();
    }

    @Bean
    Queue moderationMailDeadLetterQueue() {
        return QueueBuilder.durable(MODERATION_MAIL_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding moderationMailDeadLetterBinding(
            Queue moderationMailDeadLetterQueue, TopicExchange socialEventsDeadLetterExchange) {
        return BindingBuilder.bind(moderationMailDeadLetterQueue)
                .to(socialEventsDeadLetterExchange)
                .with(MODERATION_MAIL_DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    Queue notificationQueue() {
        return QueueBuilder.durable(NOTIFICATION_QUEUE).build();
    }

    @Bean
    Queue notificationDeadLetterQueue() {
        return QueueBuilder.durable(NOTIFICATION_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding notificationDeadLetterBinding(
            Queue notificationDeadLetterQueue, TopicExchange socialEventsDeadLetterExchange) {
        return BindingBuilder.bind(notificationDeadLetterQueue)
                .to(socialEventsDeadLetterExchange)
                .with(NOTIFICATION_DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    Queue hashtagIndexSyncQueue() {
        return QueueBuilder.durable(HASHTAG_INDEX_SYNC_QUEUE).build();
    }

    @Bean
    Queue hashtagIndexSyncDeadLetterQueue() {
        return QueueBuilder.durable(HASHTAG_INDEX_SYNC_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding hashtagIndexSyncDeadLetterBinding(
            Queue hashtagIndexSyncDeadLetterQueue, TopicExchange socialEventsDeadLetterExchange) {
        return BindingBuilder.bind(hashtagIndexSyncDeadLetterQueue)
                .to(socialEventsDeadLetterExchange)
                .with(HASHTAG_INDEX_DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    Queue postIndexSyncQueue() {
        return QueueBuilder.durable(POST_INDEX_SYNC_QUEUE).build();
    }

    @Bean
    Queue postIndexSyncDeadLetterQueue() {
        return QueueBuilder.durable(POST_INDEX_SYNC_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding postIndexSyncDeadLetterBinding(
            Queue postIndexSyncDeadLetterQueue, TopicExchange socialEventsDeadLetterExchange) {
        return BindingBuilder.bind(postIndexSyncDeadLetterQueue)
                .to(socialEventsDeadLetterExchange)
                .with(POST_INDEX_DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    FanoutExchange commentLiveEventsExchange() {
        return ExchangeBuilder.fanoutExchange(COMMENT_LIVE_EVENTS_EXCHANGE).durable(true).build();
    }

    // Exchange-to-exchange: the topic bus routes every comment.* event into the live fanout so the
    // outbox publishes once and the broker fans out to both the notification queue and the live
    // tier.
    @Bean
    Binding commentLiveExchangeBinding(
            FanoutExchange commentLiveEventsExchange, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(commentLiveEventsExchange)
                .to(socialEventsExchange)
                .with("comment.#");
    }

    @Bean
    Queue commentNotificationQueue() {
        return QueueBuilder.durable(COMMENT_NOTIFICATION_QUEUE)
                .withArgument("x-dead-letter-exchange", SOCIAL_EVENTS_DEAD_LETTER_EXCHANGE)
                .withArgument(
                        "x-dead-letter-routing-key", COMMENT_NOTIFICATION_DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue commentNotificationDeadLetterQueue() {
        return QueueBuilder.durable(COMMENT_NOTIFICATION_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding commentNotificationDeadLetterBinding(
            Queue commentNotificationDeadLetterQueue,
            TopicExchange socialEventsDeadLetterExchange) {
        return BindingBuilder.bind(commentNotificationDeadLetterQueue)
                .to(socialEventsDeadLetterExchange)
                .with(COMMENT_NOTIFICATION_DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    Queue adminNotificationQueue() {
        return QueueBuilder.durable(ADMIN_NOTIFICATION_QUEUE)
                .withArgument("x-dead-letter-exchange", SOCIAL_EVENTS_DEAD_LETTER_EXCHANGE)
                .withArgument(
                        "x-dead-letter-routing-key", ADMIN_NOTIFICATION_DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue adminNotificationDeadLetterQueue() {
        return QueueBuilder.durable(ADMIN_NOTIFICATION_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding adminNotificationDeadLetterBinding(
            Queue adminNotificationDeadLetterQueue, TopicExchange socialEventsDeadLetterExchange) {
        return BindingBuilder.bind(adminNotificationDeadLetterQueue)
                .to(socialEventsDeadLetterExchange)
                .with(ADMIN_NOTIFICATION_DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    Queue storyNotificationQueue() {
        return QueueBuilder.durable(STORY_NOTIFICATION_QUEUE)
                .withArgument("x-dead-letter-exchange", SOCIAL_EVENTS_DEAD_LETTER_EXCHANGE)
                .withArgument(
                        "x-dead-letter-routing-key", STORY_NOTIFICATION_DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue storyNotificationDeadLetterQueue() {
        return QueueBuilder.durable(STORY_NOTIFICATION_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding storyNotificationDeadLetterBinding(
            Queue storyNotificationDeadLetterQueue, TopicExchange socialEventsDeadLetterExchange) {
        return BindingBuilder.bind(storyNotificationDeadLetterQueue)
                .to(socialEventsDeadLetterExchange)
                .with(STORY_NOTIFICATION_DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    Queue recommendationFeedbackQueue() {
        return QueueBuilder.durable(RECOMMENDATION_FEEDBACK_QUEUE)
                .withArgument("x-dead-letter-exchange", SOCIAL_EVENTS_DEAD_LETTER_EXCHANGE)
                .withArgument(
                        "x-dead-letter-routing-key",
                        RECOMMENDATION_FEEDBACK_DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue postNotificationQueue() {
        return QueueBuilder.durable(POST_NOTIFICATION_QUEUE)
                .withArgument("x-dead-letter-exchange", SOCIAL_EVENTS_DEAD_LETTER_EXCHANGE)
                .withArgument(
                        "x-dead-letter-routing-key", POST_NOTIFICATION_DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue recommendationFeedbackDeadLetterQueue() {
        return QueueBuilder.durable(RECOMMENDATION_FEEDBACK_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding recommendationFeedbackDeadLetterBinding(
            Queue recommendationFeedbackDeadLetterQueue,
            TopicExchange socialEventsDeadLetterExchange) {
        return BindingBuilder.bind(recommendationFeedbackDeadLetterQueue)
                .to(socialEventsDeadLetterExchange)
                .with(RECOMMENDATION_FEEDBACK_DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    Queue postNotificationDeadLetterQueue() {
        return QueueBuilder.durable(POST_NOTIFICATION_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding postNotificationDeadLetterBinding(
            Queue postNotificationDeadLetterQueue, TopicExchange socialEventsDeadLetterExchange) {
        return BindingBuilder.bind(postNotificationDeadLetterQueue)
                .to(socialEventsDeadLetterExchange)
                .with(POST_NOTIFICATION_DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    FanoutExchange messageLiveEventsExchange() {
        return ExchangeBuilder.fanoutExchange(MESSAGE_LIVE_EVENTS_EXCHANGE).durable(true).build();
    }

    // Exchange-to-exchange: the topic bus routes every message.* event into the live fanout so the
    // outbox publishes once and the broker fans out to both the notification queue and the live
    // tier.
    @Bean
    Binding messageLiveExchangeBinding(
            FanoutExchange messageLiveEventsExchange, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(messageLiveEventsExchange)
                .to(socialEventsExchange)
                .with("message.#");
    }

    @Bean
    FanoutExchange notificationLiveEventsExchange() {
        return ExchangeBuilder.fanoutExchange(NOTIFICATION_LIVE_EVENTS_EXCHANGE)
                .durable(true)
                .build();
    }

    // Exchange-to-exchange: the topic bus routes every notification.* event into the live fanout so
    // the outbox publishes once and the broker fans out to whichever instance holds the recipient's
    // session, mirroring the comment module's commentLiveExchangeBinding above.
    @Bean
    Binding notificationLiveExchangeBinding(
            FanoutExchange notificationLiveEventsExchange, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(notificationLiveEventsExchange)
                .to(socialEventsExchange)
                .with("notification.#");
    }

    @Bean
    FanoutExchange postLiveEventsExchange() {
        return ExchangeBuilder.fanoutExchange(POST_LIVE_EVENTS_EXCHANGE).durable(true).build();
    }

    // Exchange-to-exchange, mirroring the comment and notification live tiers above, but bound on
    // post.live.# rather than post.# on purpose. post.index.upsert.v1 and post.index.delete.v1
    // already flow through this same topic exchange to the Elasticsearch sync queue, and a post.#
    // binding would deliver every index-sync event into the live tier to be fanned out to
    // WebSocket subscribers as though it were user-facing. Keeping the live namespace disjoint
    // also lets a future post live event be added by publishing under post.live.* with no change
    // to this topology.
    @Bean
    Binding postLiveExchangeBinding(
            FanoutExchange postLiveEventsExchange, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(postLiveEventsExchange)
                .to(socialEventsExchange)
                .with("post.live.#");
    }
}
