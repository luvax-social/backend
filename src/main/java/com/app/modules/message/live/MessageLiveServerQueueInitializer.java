package com.app.modules.message.live;

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
 * Declares this instance's ephemeral fanout queue for live message events.
 *
 * <p>Each application instance binds its own auto-delete queue to the message live fanout exchange,
 * so every instance receives all message events and can push them to the sessions it locally holds.
 * The queue is durable (RabbitMQ 4.x rejects non-durable, non-exclusive queue declarations) but
 * still auto-deletes when the instance's consumer disconnects.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.message.live", name = "enabled", havingValue = "true")
public class MessageLiveServerQueueInitializer {

    private final String serverId = UUID.randomUUID().toString();
    private final String queueName = "message.live." + serverId;

    public String getServerId() {
        return serverId;
    }

    public String getQueueName() {
        return queueName;
    }

    @Bean
    Queue messageLiveServerQueue() {
        // autoDelete removes the queue once its consumer leaves, but never fires for a queue whose
        // instance died before its listener attached; the idle expiry removes that one too.
        return QueueBuilder.durable(queueName)
                .autoDelete()
                .expires(RabbitMqTopologyConfig.LIVE_SERVER_QUEUE_EXPIRES_MILLIS)
                .build();
    }

    @Bean
    Binding messageLiveServerQueueBinding(
            Queue messageLiveServerQueue, FanoutExchange messageLiveEventsExchange) {
        return BindingBuilder.bind(messageLiveServerQueue).to(messageLiveEventsExchange);
    }
}
