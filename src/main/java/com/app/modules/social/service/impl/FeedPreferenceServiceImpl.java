package com.app.modules.social.service.impl;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.modules.social.messaging.SocialEventTypes;
import com.app.modules.social.repository.AccountShowFewerRepository;
import com.app.modules.social.repository.BlockRepository;
import com.app.modules.social.repository.SocialUserRepository;
import com.app.modules.social.service.FeedPreferenceService;

@Service
public class FeedPreferenceServiceImpl implements FeedPreferenceService {

    private static final String AGGREGATE_TYPE_USER = "user";

    private final AccountShowFewerRepository showFewerRepository;
    private final SocialUserRepository socialUserRepository;
    private final BlockRepository blockRepository;
    private final OutboxService outboxService;

    public FeedPreferenceServiceImpl(
            AccountShowFewerRepository showFewerRepository,
            SocialUserRepository socialUserRepository,
            BlockRepository blockRepository,
            OutboxService outboxService) {
        this.showFewerRepository = showFewerRepository;
        this.socialUserRepository = socialUserRepository;
        this.blockRepository = blockRepository;
        this.outboxService = outboxService;
    }

    @Override
    @Transactional
    public void showFewer(UUID userId, UUID authorId) {
        // Self, a missing author and a block in either direction share one answer, so the status
        // and body cannot be used to learn that a block exists.
        if (userId.equals(authorId)
                || !socialUserRepository.existsByIdAndDeletedAtIsNull(authorId)
                || blockRepository.existsBetween(userId, authorId)) {
            throw new AppException(ApiErrorCode.NOT_FOUND, "Target user not found");
        }
        // The insert reports whether it created the row, so a repeat or a concurrent duplicate
        // enqueues nothing.
        if (showFewerRepository.insertIfAbsent(userId, authorId) == 0) {
            return;
        }
        enqueue(SocialEventTypes.USER_SHOW_FEWER_V1, userId, authorId);
    }

    @Override
    @Transactional
    public void undoShowFewer(UUID userId, UUID authorId) {
        if (showFewerRepository.deleteByUserAndAuthor(userId, authorId) == 0) {
            return;
        }
        enqueue(SocialEventTypes.USER_SHOW_FEWER_UNDONE_V1, userId, authorId);
    }

    private void enqueue(String eventType, UUID userId, UUID authorId) {
        outboxService.enqueue(
                eventType,
                eventType,
                AGGREGATE_TYPE_USER,
                userId,
                userId,
                Map.of("authorId", authorId.toString()));
    }
}
