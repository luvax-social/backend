package com.app.common.config.rabbit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.app.modules.admin.messaging.AdminAnalyticsRabbitBindingConfig;
import com.app.modules.admin.messaging.AdminRabbitBindingConfig;
import com.app.modules.auth.messaging.AuthEventTypes;
import com.app.modules.auth.messaging.AuthMailRabbitBindingConfig;
import com.app.modules.comment.messaging.CommentRabbitBindingConfig;
import com.app.modules.hashtag.messaging.HashtagRabbitBindingConfig;
import com.app.modules.notification.messaging.NotificationRabbitBindingConfig;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.messaging.PostRabbitBindingConfig;
import com.app.modules.recommendation.messaging.DomainSignalMappings;
import com.app.modules.recommendation.messaging.RecommendationRabbitBindingConfig;
import com.app.modules.social.messaging.SocialEventTypes;
import com.app.modules.story.messaging.StoryRabbitBindingConfig;

class RabbitMqTopologyConfigTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(RabbitMqTopologyConfig.class);
    private final ApplicationContextRunner authMailBindingContextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            RabbitMqTopologyConfig.class, AuthMailRabbitBindingConfig.class);

    // Signals that are recorded for analytics and also announced to the person they involve, so
    // they reach the post notification queue as well as the import queue.
    private static final Set<String> NOTIFYING_SIGNALS =
            Set.of(
                    PostEventTypes.POST_SCHEDULE_FAILED_V1,
                    PostEventTypes.POST_TAG_CREATED_V1,
                    PostEventTypes.POST_COLLABORATION_INVITED_V1,
                    PostEventTypes.POST_COLLABORATION_ACCEPTED_V1,
                    PostEventTypes.POST_MENTION_CREATED_V1);

    @Test
    void topology_contextLoads_declaresAllDeclaredQueues() {
        contextRunner.run(
                context -> {
                    Set<String> queueNames =
                            context.getBeansOfType(Queue.class).values().stream()
                                    .map(Queue::getName)
                                    .collect(Collectors.toSet());

                    assertThat(queueNames)
                            .containsExactlyInAnyOrder(
                                    RabbitMqTopologyConfig.MAIL_QUEUE,
                                    RabbitMqTopologyConfig.MAIL_DEAD_LETTER_QUEUE,
                                    RabbitMqTopologyConfig.MODERATION_MAIL_QUEUE,
                                    RabbitMqTopologyConfig.MODERATION_MAIL_DEAD_LETTER_QUEUE,
                                    RabbitMqTopologyConfig.NOTIFICATION_QUEUE,
                                    RabbitMqTopologyConfig.NOTIFICATION_DEAD_LETTER_QUEUE,
                                    RabbitMqTopologyConfig.HASHTAG_INDEX_SYNC_QUEUE,
                                    RabbitMqTopologyConfig.HASHTAG_INDEX_SYNC_DEAD_LETTER_QUEUE,
                                    RabbitMqTopologyConfig.POST_INDEX_SYNC_QUEUE,
                                    RabbitMqTopologyConfig.POST_INDEX_SYNC_DEAD_LETTER_QUEUE,
                                    RabbitMqTopologyConfig.PLACE_INDEX_SYNC_QUEUE,
                                    RabbitMqTopologyConfig.PLACE_INDEX_SYNC_DEAD_LETTER_QUEUE,
                                    RabbitMqTopologyConfig.COMMENT_NOTIFICATION_QUEUE,
                                    RabbitMqTopologyConfig.COMMENT_NOTIFICATION_DEAD_LETTER_QUEUE,
                                    RabbitMqTopologyConfig.STORY_NOTIFICATION_QUEUE,
                                    RabbitMqTopologyConfig.STORY_NOTIFICATION_DEAD_LETTER_QUEUE,
                                    RabbitMqTopologyConfig.RECOMMENDATION_FEEDBACK_QUEUE,
                                    RabbitMqTopologyConfig
                                            .RECOMMENDATION_FEEDBACK_DEAD_LETTER_QUEUE,
                                    RabbitMqTopologyConfig.POST_NOTIFICATION_QUEUE,
                                    RabbitMqTopologyConfig.POST_NOTIFICATION_DEAD_LETTER_QUEUE,
                                    RabbitMqTopologyConfig.ADMIN_NOTIFICATION_QUEUE,
                                    RabbitMqTopologyConfig.ADMIN_NOTIFICATION_DEAD_LETTER_QUEUE,
                                    RabbitMqTopologyConfig.ADMIN_ACTION_REPLICATION_QUEUE,
                                    RabbitMqTopologyConfig
                                            .ADMIN_ACTION_REPLICATION_DEAD_LETTER_QUEUE,
                                    RabbitMqTopologyConfig.PLATFORM_STATS_QUEUE,
                                    RabbitMqTopologyConfig.PLATFORM_STATS_DEAD_LETTER_QUEUE,
                                    RabbitMqTopologyConfig.USER_EVENT_IMPORT_QUEUE,
                                    RabbitMqTopologyConfig.USER_EVENT_IMPORT_DEAD_LETTER_QUEUE);
                    assertThat(queueNames)
                            .doesNotContain(
                                    RabbitMqTopologyConfig.AUDIT_LOG_QUEUE,
                                    RabbitMqTopologyConfig.MODERATION_QUEUE,
                                    RabbitMqTopologyConfig.SEARCH_INDEX_QUEUE);
                    assertThat(context.getBeansOfType(Queue.class).values())
                            .allSatisfy(queue -> assertThat(queue.isDurable()).isTrue());
                });
    }

    @Test
    void declaresDomainEventAndDeadLetterExchanges() {
        contextRunner.run(
                context -> {
                    List<String> exchangeNames =
                            context.getBeansOfType(TopicExchange.class).values().stream()
                                    .map(TopicExchange::getName)
                                    .toList();

                    assertThat(exchangeNames)
                            .containsExactlyInAnyOrder(
                                    RabbitMqTopologyConfig.SOCIAL_EVENTS_EXCHANGE,
                                    RabbitMqTopologyConfig.SOCIAL_EVENTS_DEAD_LETTER_EXCHANGE);
                    assertThat(context.getBeansOfType(TopicExchange.class).values())
                            .allSatisfy(exchange -> assertThat(exchange.isDurable()).isTrue());
                });
    }

    @Test
    void bindsMailQueueToMailEventRoutingKeysOnly() {
        authMailBindingContextRunner.run(
                context -> {
                    List<String> mailRoutingKeys =
                            context.getBeansOfType(Binding.class).values().stream()
                                    .filter(
                                            binding ->
                                                    RabbitMqTopologyConfig.MAIL_QUEUE.equals(
                                                            binding.getDestination()))
                                    .filter(
                                            binding ->
                                                    RabbitMqTopologyConfig.SOCIAL_EVENTS_EXCHANGE
                                                            .equals(binding.getExchange()))
                                    .map(Binding::getRoutingKey)
                                    .toList();

                    assertThat(mailRoutingKeys)
                            .containsExactlyInAnyOrderElementsOf(
                                    AuthEventTypes.MAIL_EVENT_ROUTING_KEYS);
                    assertThat(context.getBeansOfType(Binding.class).values())
                            .filteredOn(
                                    binding ->
                                            RabbitMqTopologyConfig.MAIL_QUEUE.equals(
                                                    binding.getDestination()))
                            .allSatisfy(
                                    binding ->
                                            assertThat(binding.getDestinationType())
                                                    .isEqualTo(Binding.DestinationType.QUEUE));
                });
    }

    @Test
    void topology_contextLoads_doesNotBindFutureQueues() {
        contextRunner.run(
                context -> {
                    Set<String> futureQueues =
                            Set.of(
                                    RabbitMqTopologyConfig.AUDIT_LOG_QUEUE,
                                    RabbitMqTopologyConfig.MODERATION_QUEUE,
                                    RabbitMqTopologyConfig.SEARCH_INDEX_QUEUE);

                    assertThat(context.getBeansOfType(Binding.class).values())
                            .noneMatch(binding -> futureQueues.contains(binding.getDestination()));
                    // Active bindings: mail, moderation.mail, notification, hashtag.index,
                    // post.index, place.index, comment.notification, story.notification,
                    // recommendation.feedback, post.notification, admin.notification and
                    // admin.action.replication, admin.platform-stats and
                    // recommendation.user-event.import dead-letter bindings plus
                    // the comment live, message
                    // live, notification live,
                    // and post live exchange-to-exchange bindings.
                    assertThat(context.getBeansOfType(Binding.class)).hasSize(18);
                });
    }

    @Test
    void bindsMailDeadLetterQueueToDeadLetterExchange() {
        contextRunner.run(
                context -> {
                    List<String> deadLetterRoutingKeys =
                            context.getBeansOfType(Binding.class).values().stream()
                                    .filter(
                                            binding ->
                                                    RabbitMqTopologyConfig.MAIL_DEAD_LETTER_QUEUE
                                                            .equals(binding.getDestination()))
                                    .filter(
                                            binding ->
                                                    RabbitMqTopologyConfig
                                                            .SOCIAL_EVENTS_DEAD_LETTER_EXCHANGE
                                                            .equals(binding.getExchange()))
                                    .map(Binding::getRoutingKey)
                                    .toList();

                    assertThat(deadLetterRoutingKeys)
                            .containsExactly(RabbitMqTopologyConfig.MAIL_DEAD_LETTER_ROUTING_KEY);
                });
    }

    @Test
    void interactionControlEvents_areNeverBoundToANotificationQueue() {
        // A mute, a show-fewer request and a not-interested mark are private to the viewer who made
        // them, so none of their routing keys may reach a queue that turns events into
        // notifications. The check matches every binding pattern against each key, so a wildcard
        // added later is caught as well as an exact key. The post creation events that tell
        // another person something, a tag, an invitation to co-author, a mention and a failed
        // schedule, are the deliberate exceptions and are listed in NOTIFYING_SIGNALS.
        new ApplicationContextRunner()
                .withUserConfiguration(
                        RabbitMqTopologyConfig.class,
                        AuthMailRabbitBindingConfig.class,
                        NotificationRabbitBindingConfig.class,
                        PostRabbitBindingConfig.class,
                        CommentRabbitBindingConfig.class,
                        StoryRabbitBindingConfig.class,
                        HashtagRabbitBindingConfig.class,
                        RecommendationRabbitBindingConfig.class,
                        AdminRabbitBindingConfig.class,
                        AdminAnalyticsRabbitBindingConfig.class)
                .run(
                        context -> {
                            Set<String> notificationQueues =
                                    Set.of(
                                            RabbitMqTopologyConfig.NOTIFICATION_QUEUE,
                                            RabbitMqTopologyConfig.POST_NOTIFICATION_QUEUE,
                                            RabbitMqTopologyConfig.COMMENT_NOTIFICATION_QUEUE,
                                            RabbitMqTopologyConfig.STORY_NOTIFICATION_QUEUE,
                                            RabbitMqTopologyConfig.ADMIN_NOTIFICATION_QUEUE);
                            // Every key the import table maps is an analytics signal that no
                            // notification may be built from, except the ones that are also
                            // announced to the person involved, so a mapping added later is
                            // covered without editing this list.
                            List<String> eventKeys =
                                    Stream.concat(
                                                    Stream.of(
                                                            SocialEventTypes.USER_MUTED_V1,
                                                            SocialEventTypes.USER_UNMUTED_V1,
                                                            SocialEventTypes.USER_SHOW_FEWER_V1,
                                                            SocialEventTypes
                                                                    .USER_SHOW_FEWER_UNDONE_V1,
                                                            PostEventTypes.POST_NOT_INTERESTED_V1,
                                                            PostEventTypes
                                                                    .POST_NOT_INTERESTED_UNDONE_V1),
                                                    DomainSignalMappings.BY_EVENT_TYPE
                                                            .keySet()
                                                            .stream())
                                            .filter(key -> !NOTIFYING_SIGNALS.contains(key))
                                            .distinct()
                                            .toList();

                            List<Binding> notificationBindings =
                                    context.getBeansOfType(Binding.class).values().stream()
                                            .filter(
                                                    binding ->
                                                            RabbitMqTopologyConfig
                                                                    .SOCIAL_EVENTS_EXCHANGE
                                                                    .equals(binding.getExchange()))
                                            .filter(
                                                    binding ->
                                                            notificationQueues.contains(
                                                                    binding.getDestination()))
                                            .toList();

                            assertThat(notificationBindings).isNotEmpty();
                            for (String eventKey : eventKeys) {
                                assertThat(notificationBindings)
                                        .as("bindings that would deliver %s", eventKey)
                                        .noneMatch(
                                                binding ->
                                                        topicPatternMatches(
                                                                binding.getRoutingKey(), eventKey));
                            }
                        });
    }

    @Test
    void domainSignalEvents_areBoundToTheUserEventImportQueueAndToNoOtherQueue() {
        // Each mapped event must reach the queue the import consumer reads; the broker would
        // otherwise count it as unroutable. Matching every binding pattern against each key also
        // proves no other queue receives it, which the import queue's sole ownership relies on.
        new ApplicationContextRunner()
                .withUserConfiguration(
                        RabbitMqTopologyConfig.class,
                        AuthMailRabbitBindingConfig.class,
                        NotificationRabbitBindingConfig.class,
                        PostRabbitBindingConfig.class,
                        CommentRabbitBindingConfig.class,
                        StoryRabbitBindingConfig.class,
                        HashtagRabbitBindingConfig.class,
                        RecommendationRabbitBindingConfig.class,
                        AdminRabbitBindingConfig.class,
                        AdminAnalyticsRabbitBindingConfig.class)
                .run(
                        context -> {
                            List<Binding> queueBindings =
                                    context.getBeansOfType(Binding.class).values().stream()
                                            .filter(
                                                    binding ->
                                                            RabbitMqTopologyConfig
                                                                    .SOCIAL_EVENTS_EXCHANGE
                                                                    .equals(binding.getExchange()))
                                            .filter(
                                                    binding ->
                                                            binding.getDestinationType()
                                                                    == Binding.DestinationType
                                                                            .QUEUE)
                                            .toList();

                            assertThat(DomainSignalMappings.BY_EVENT_TYPE).isNotEmpty();
                            for (String eventKey : DomainSignalMappings.BY_EVENT_TYPE.keySet()) {
                                Set<String> receivers =
                                        queueBindings.stream()
                                                .filter(
                                                        binding ->
                                                                topicPatternMatches(
                                                                        binding.getRoutingKey(),
                                                                        eventKey))
                                                .map(Binding::getDestination)
                                                .collect(Collectors.toSet());
                                if (NOTIFYING_SIGNALS.contains(eventKey)) {
                                    assertThat(receivers)
                                            .as("queues that receive %s", eventKey)
                                            .containsExactlyInAnyOrder(
                                                    RabbitMqTopologyConfig.USER_EVENT_IMPORT_QUEUE,
                                                    RabbitMqTopologyConfig.POST_NOTIFICATION_QUEUE);
                                } else {
                                    assertThat(receivers)
                                            .as("queues that receive %s", eventKey)
                                            .containsExactly(
                                                    RabbitMqTopologyConfig.USER_EVENT_IMPORT_QUEUE);
                                }
                            }
                        });
    }

    @Test
    void notInterestedEvents_reachTheFeedbackQueueExactlyOnceAndNoOtherQueue() {
        // These two are not in DomainSignalMappings because RecommendationFeedbackConsumer writes
        // them. An unbound key would be unroutable and a key bound twice, or also bound to another
        // queue, would apply the preference more than once, so the destinations are counted rather
        // than collected into a set.
        new ApplicationContextRunner()
                .withUserConfiguration(
                        RabbitMqTopologyConfig.class,
                        AuthMailRabbitBindingConfig.class,
                        NotificationRabbitBindingConfig.class,
                        PostRabbitBindingConfig.class,
                        CommentRabbitBindingConfig.class,
                        StoryRabbitBindingConfig.class,
                        HashtagRabbitBindingConfig.class,
                        RecommendationRabbitBindingConfig.class,
                        AdminRabbitBindingConfig.class,
                        AdminAnalyticsRabbitBindingConfig.class)
                .run(
                        context -> {
                            List<Binding> queueBindings =
                                    context.getBeansOfType(Binding.class).values().stream()
                                            .filter(
                                                    binding ->
                                                            RabbitMqTopologyConfig
                                                                    .SOCIAL_EVENTS_EXCHANGE
                                                                    .equals(binding.getExchange()))
                                            .filter(
                                                    binding ->
                                                            binding.getDestinationType()
                                                                    == Binding.DestinationType
                                                                            .QUEUE)
                                            .toList();

                            for (String eventKey :
                                    List.of(
                                            PostEventTypes.POST_NOT_INTERESTED_V1,
                                            PostEventTypes.POST_NOT_INTERESTED_UNDONE_V1)) {
                                List<String> receivers =
                                        queueBindings.stream()
                                                .filter(
                                                        binding ->
                                                                topicPatternMatches(
                                                                        binding.getRoutingKey(),
                                                                        eventKey))
                                                .map(Binding::getDestination)
                                                .toList();
                                assertThat(receivers)
                                        .as("bindings that deliver %s", eventKey)
                                        .containsExactly(
                                                RabbitMqTopologyConfig
                                                        .RECOMMENDATION_FEEDBACK_QUEUE);
                            }
                        });
    }

    // AMQP topic semantics: words are dot separated, "*" stands for exactly one word and "#" for
    // zero or more.
    private static boolean topicPatternMatches(String pattern, String routingKey) {
        return matchWords(pattern.split("[.]"), 0, routingKey.split("[.]"), 0);
    }

    private static boolean matchWords(String[] pattern, int p, String[] key, int k) {
        if (p == pattern.length) {
            return k == key.length;
        }
        if ("#".equals(pattern[p])) {
            for (int skip = k; skip <= key.length; skip++) {
                if (matchWords(pattern, p + 1, key, skip)) {
                    return true;
                }
            }
            return false;
        }
        if (k == key.length) {
            return false;
        }
        boolean wordMatches = "*".equals(pattern[p]) || pattern[p].equals(key[k]);
        return wordMatches && matchWords(pattern, p + 1, key, k + 1);
    }
}
