package com.app.common.config.rabbit;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Deletes queues this application no longer declares, once, at startup.
 *
 * <p>Removing a queue's {@code @Bean} stops the application declaring it but does not remove it
 * from a broker that already has it. A durable queue that is still bound keeps receiving a copy of
 * every matching message with no consumer to take it, and grows until the broker runs out of disk.
 * Deleting it here makes a retirement self-cleaning in every environment, with no manual broker
 * step to forget.
 *
 * <p>Idempotent: a queue that is already gone is skipped, and deleting a queue removes its bindings
 * with it. Messages still waiting in a retired queue are dropped deliberately; the feature that
 * would have consumed them no longer exists. A broker that cannot be reached is logged and left
 * alone, so this never stops the application from starting; the next start retries.
 *
 * <p>Absent a RabbitMQ connection (tests that exclude the auto-configuration), it does nothing.
 */
@Component
public class RetiredQueueCleaner implements ApplicationRunner {

    /**
     * Queues retired from the topology.
     *
     * <p>{@code message.notification.queue} and its dead-letter queue carried {@code
     * message.sent.v1} to the consumer that wrote a notification per direct message; direct
     * messages left the activity feed with the notification overhaul. Keep a name here for at least
     * one release after its retirement, so every environment has started once with it.
     */
    static final List<String> RETIRED_QUEUES =
            List.of("message.notification.queue", "message.notification.dlq");

    private static final Logger log = LoggerFactory.getLogger(RetiredQueueCleaner.class);

    private final ObjectProvider<AmqpAdmin> amqpAdmin;

    public RetiredQueueCleaner(ObjectProvider<AmqpAdmin> amqpAdmin) {
        this.amqpAdmin = amqpAdmin;
    }

    @Override
    public void run(ApplicationArguments args) {
        AmqpAdmin admin = amqpAdmin.getIfAvailable();
        if (admin == null) {
            return;
        }
        for (String queue : RETIRED_QUEUES) {
            try {
                if (admin.getQueueProperties(queue) == null) {
                    continue;
                }
                admin.deleteQueue(queue);
                log.info("Deleted retired RabbitMQ queue {}", queue);
            } catch (AmqpException ex) {
                log.warn("Could not delete retired RabbitMQ queue {}: {}", queue, ex.getMessage());
            }
        }
    }
}
