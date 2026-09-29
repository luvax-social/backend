package com.app.modules.admin.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the events that feed the ClickHouse analytics tier to their own queues.
 *
 * <p>Both audit events, the one the recorder raises and the one the {@code admin_actions} update
 * trigger writes, land on the same queue because the consumer treats them identically: it reads the
 * current row and writes it with its current version.
 */
@Configuration
public class AdminAnalyticsRabbitBindingConfig {

    @Bean
    Binding adminActionRecordedBinding(
            Queue adminActionReplicationQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(adminActionReplicationQueue)
                .to(socialEventsExchange)
                .with(AdminEventTypes.ACTION_RECORDED_V1);
    }

    @Bean
    Binding adminActionChangedBinding(
            Queue adminActionReplicationQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(adminActionReplicationQueue)
                .to(socialEventsExchange)
                .with(AdminEventTypes.ACTION_CHANGED_V1);
    }
}
