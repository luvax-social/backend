package com.app.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class DeadLetterPublisherTest {

    @Mock private ObjectProvider<RabbitTemplate> rabbitTemplateProvider;
    @Mock private RabbitTemplate rabbitTemplate;

    private SimpleMeterRegistry meterRegistry;
    private DeadLetterPublisher publisher;

    @BeforeEach
    void setUp() {
        when(rabbitTemplateProvider.getIfAvailable(any())).thenReturn(rabbitTemplate);
        meterRegistry = new SimpleMeterRegistry();
        publisher = new DeadLetterPublisher(rabbitTemplateProvider, meterRegistry);
    }

    @Test
    void publish_interruptedOnConfirm_throwsIllegalStateAndRestoresInterrupt() {
        // Do not complete the future — leave it pending so get() checks the interrupted flag
        doAnswer(inv -> null)
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        Thread.currentThread().interrupt();
        Message msg = MessageBuilder.withBody("body".getBytes()).build();
        try {
            assertThatThrownBy(() -> publisher.publish(msg, "rk", "reason"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Interrupted");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void publish_executionExceptionOnConfirm_throwsIllegalState() {
        doAnswer(
                        inv -> {
                            CorrelationData cd = inv.getArgument(3);
                            cd.getFuture()
                                    .completeExceptionally(
                                            new ExecutionException(
                                                    "broker error", new RuntimeException("root")));
                            return null;
                        })
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        Message msg = MessageBuilder.withBody("body".getBytes()).build();
        assertThatThrownBy(() -> publisher.publish(msg, "rk", "reason"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to publish dead-letter");
    }

    @Test
    void publish_timeout_throwsIllegalState() {
        // No call to send → Future never completes → TimeoutException after 5s
        // Simulate by completing with nack which triggers nack path (not timeout)
        // Instead complete with success but returned != null to test returned branch
        doAnswer(
                        inv -> {
                            CorrelationData cd = inv.getArgument(3);
                            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
                            cd.setReturned(
                                    new ReturnedMessage(
                                            inv.getArgument(2),
                                            312,
                                            "NO_ROUTE",
                                            "social.events.dlx",
                                            "rk"));
                            return null;
                        })
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        Message msg = MessageBuilder.withBody("body".getBytes()).build();
        assertThatThrownBy(() -> publisher.publish(msg, "rk", "reason"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("returned");
    }

    @Test
    void publish_nackFromBroker_throwsIllegalState() {
        doAnswer(
                        inv -> {
                            CorrelationData cd = inv.getArgument(3);
                            cd.getFuture().complete(new CorrelationData.Confirm(false, "nack"));
                            return null;
                        })
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        Message msg = MessageBuilder.withBody("body".getBytes()).build();
        assertThatThrownBy(() -> publisher.publish(msg, "rk", "reason"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rejected");
    }

    @Test
    void sanitize_nullReason_usesDefaultMessage() {
        doAnswer(
                        inv -> {
                            CorrelationData cd = inv.getArgument(3);
                            Message sentMsg = inv.getArgument(2);
                            String header =
                                    (String)
                                            sentMsg.getMessageProperties()
                                                    .getHeader("x-dead-letter-reason");
                            assertThat(header).isEqualTo("Unknown message failure");
                            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
                            return null;
                        })
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        Message msg = MessageBuilder.withBody("body".getBytes()).build();
        publisher.publish(msg, "rk", null);
    }

    @Test
    void sanitize_reasonExceedsMaxLength_truncatesTo500Chars() {
        String longReason = "x".repeat(600);
        doAnswer(
                        inv -> {
                            CorrelationData cd = inv.getArgument(3);
                            Message sentMsg = inv.getArgument(2);
                            String header =
                                    (String)
                                            sentMsg.getMessageProperties()
                                                    .getHeader("x-dead-letter-reason");
                            assertThat(header).hasSize(500);
                            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
                            return null;
                        })
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        Message msg = MessageBuilder.withBody("body".getBytes()).build();
        publisher.publish(msg, "rk", longReason);
    }

    @Test
    void publish_confirmedPublish_incrementsDeadLetteredCounter() {
        doAnswer(
                        inv -> {
                            CorrelationData cd = inv.getArgument(3);
                            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
                            return null;
                        })
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        Message msg = MessageBuilder.withBody("body".getBytes()).build();
        publisher.publish(msg, "rk", "reason");

        assertThat(
                        meterRegistry
                                .get("luvax.messaging.dead.lettered")
                                .tag("queue", "unknown")
                                .tag("dead_letter_routing_key", "rk")
                                .counter()
                                .count())
                .isEqualTo(1.0);
    }

    @Test
    void publish_nack_doesNotIncrementDeadLetteredCounter() {
        doAnswer(
                        inv -> {
                            CorrelationData cd = inv.getArgument(3);
                            cd.getFuture().complete(new CorrelationData.Confirm(false, "nack"));
                            return null;
                        })
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        Message msg = MessageBuilder.withBody("body".getBytes()).build();
        assertThatThrownBy(() -> publisher.publish(msg, "rk", "reason"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(meterRegistry.find("luvax.messaging.dead.lettered").counter()).isNull();
    }
}
