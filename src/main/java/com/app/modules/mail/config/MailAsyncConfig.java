package com.app.modules.mail.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Transport-neutral wiring for asynchronous mail dispatch.
 *
 * <p>Provides the {@link Executor} backing {@code @Async} mail dispatch. The executor isolates
 * blocking transport I/O from the request-serving virtual thread carrier pool, and is required
 * regardless of which transport is selected.
 */
@Configuration
@EnableAsync
public class MailAsyncConfig {

    @Bean(name = {"mailTaskExecutor", "taskExecutor"})
    public Executor mailTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("mail-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.initialize();
        return executor;
    }
}
