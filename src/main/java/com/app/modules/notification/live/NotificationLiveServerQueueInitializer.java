package com.app.modules.notification.live;

import java.util.UUID;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.app.common.config.rabbit.RabbitMqTopologyConfig;

/**
 * Declares this instance's ephemeral fanout queue for live notification events, mirroring {@code
 * CommentLiveServerQueueInitializer}.
 *
 * <p>Each application instance binds its own auto-delete queue to the notification live fanout
 * exchange, so every instance receives every notification event and can push to whichever sessions
 * it locally holds. The queue is durable (RabbitMQ 4.x rejects non-durable, non-exclusive queue
 * declarations) but still auto-deletes when the instance's consumer disconnects.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.notification.live", name = "enabled", havingValue = "true")
public class NotificationLiveServerQueueInitializer {

    private final String serverId = UUID.randomUUID().toString();
    private final String queueName = "notification.live." + serverId;

    public String getServerId() {
        return serverId;
    }

    public String getQueueName() {
        return queueName;
    }

    @Bean
    Queue notificationLiveServerQueue() {
        // autoDelete removes the queue once its consumer leaves, but never fires for a queue whose
        // instance died before its listener attached; the idle expiry removes that one too.
        return QueueBuilder.durable(queueName)
                .autoDelete()
                .expires(RabbitMqTopologyConfig.LIVE_SERVER_QUEUE_EXPIRES_MILLIS)
                .build();
    }

    @Bean
    Binding notificationLiveServerQueueBinding(
            Queue notificationLiveServerQueue, FanoutExchange notificationLiveEventsExchange) {
        return BindingBuilder.bind(notificationLiveServerQueue).to(notificationLiveEventsExchange);
    }
}
