package com.app.common.config.rabbit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.app.modules.auth.messaging.AuthEventTypes;
import com.app.modules.auth.messaging.AuthMailRabbitBindingConfig;

class RabbitMqTopologyConfigTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(RabbitMqTopologyConfig.class);
    private final ApplicationContextRunner authMailBindingContextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            RabbitMqTopologyConfig.class, AuthMailRabbitBindingConfig.class);

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
                                    RabbitMqTopologyConfig.ADMIN_NOTIFICATION_DEAD_LETTER_QUEUE);
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
                    // post.index, comment.notification, story.notification,
                    // recommendation.feedback, post.notification, and admin.notification
                    // dead-letter bindings plus the comment live, message live, notification live,
                    // and post live exchange-to-exchange bindings.
                    assertThat(context.getBeansOfType(Binding.class)).hasSize(14);
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
}
