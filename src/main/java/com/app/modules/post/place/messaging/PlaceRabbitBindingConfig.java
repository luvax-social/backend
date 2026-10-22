package com.app.modules.post.place.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds place index-sync events to the place index sync queue. */
@Configuration
public class PlaceRabbitBindingConfig {

    @Bean
    Binding placeIndexSyncBinding(Queue placeIndexSyncQueue, TopicExchange socialEventsExchange) {
        return BindingBuilder.bind(placeIndexSyncQueue)
                .to(socialEventsExchange)
                .with("place.index.#");
    }
}
