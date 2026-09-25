package com.app.common.inbox.service.impl;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import com.app.common.inbox.enums.ProcessedMessageResult;
import com.app.common.inbox.observability.InboxMetrics;
import com.app.common.inbox.repository.ProcessedMessageRepository;
import com.app.common.inbox.service.ProcessedMessageService;

@Service
public class ProcessedMessageServiceImpl implements ProcessedMessageService {

    private final ProcessedMessageRepository processedMessageRepository;
    private final InboxMetrics inboxMetrics;

    public ProcessedMessageServiceImpl(
            ProcessedMessageRepository processedMessageRepository, InboxMetrics inboxMetrics) {
        this.processedMessageRepository = processedMessageRepository;
        this.inboxMetrics = inboxMetrics;
    }

    @Override
    @Transactional
    public ProcessedMessageResult processOnce(
            String consumerName, UUID eventId, String eventType, Runnable handler) {
        Assert.hasText(consumerName, "consumerName must not be blank");
        Assert.notNull(eventId, "eventId must not be null");
        Assert.hasText(eventType, "eventType must not be blank");
        Assert.notNull(handler, "handler must not be null");

        boolean inserted =
                processedMessageRepository
                        .insertIfAbsent(consumerName, eventId, eventType)
                        .isPresent();
        if (!inserted) {
            inboxMetrics.record(consumerName, InboxMetrics.Outcome.DUPLICATE);
            return ProcessedMessageResult.DUPLICATE;
        }

        handler.run();
        // Registered rather than counted here directly: a handler that runs but whose transaction
        // then rolls back must never be counted as processed.
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        inboxMetrics.record(consumerName, InboxMetrics.Outcome.PROCESSED);
                    }
                });
        return ProcessedMessageResult.PROCESSED;
    }
}
