package com.app.common.config.rabbit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;

class RetiredQueueCleanerTest {

    @Test
    void run_retiredQueuesStillOnTheBroker_deletesEach() {
        AmqpAdmin admin = mock(AmqpAdmin.class);
        when(admin.getQueueProperties(any())).thenReturn(new Properties());

        cleaner(admin).run(new DefaultApplicationArguments());

        verify(admin).deleteQueue("message.notification.queue");
        verify(admin).deleteQueue("message.notification.dlq");
    }

    @Test
    void run_queuesAlreadyGone_deletesNothing() {
        AmqpAdmin admin = mock(AmqpAdmin.class);
        when(admin.getQueueProperties(any())).thenReturn(null);

        cleaner(admin).run(new DefaultApplicationArguments());

        verify(admin, never()).deleteQueue(any());
    }

    @Test
    void run_brokerUnreachable_doesNotStopStartup() {
        AmqpAdmin admin = mock(AmqpAdmin.class);
        when(admin.getQueueProperties(any()))
                .thenThrow(new AmqpConnectException(new RuntimeException("down")));

        assertThatCode(() -> cleaner(admin).run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    @Test
    void run_noRabbitConnection_isANoOp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AmqpAdmin> absent = mock(ObjectProvider.class);
        when(absent.getIfAvailable()).thenReturn(null);

        assertThatCode(() -> new RetiredQueueCleaner(absent).run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    private static RetiredQueueCleaner cleaner(AmqpAdmin admin) {
        @SuppressWarnings("unchecked")
        ObjectProvider<AmqpAdmin> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(admin);
        return new RetiredQueueCleaner(provider);
    }
}
