package com.app.modules.social.service.impl;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.common.pagination.Cursor;
import com.app.common.pagination.CursorCodec;
import com.app.common.pagination.CursorScope;
import com.app.common.pagination.TimeCursors;
import com.app.common.response.CursorPageResponse;
import com.app.common.response.UserSummaryResponse;
import com.app.modules.social.dto.response.MuteResponse;
import com.app.modules.social.entity.UserMute;
import com.app.modules.social.messaging.SocialEventTypes;
import com.app.modules.social.repository.BlockRepository;
import com.app.modules.social.repository.SocialUserRepository;
import com.app.modules.social.repository.UserMuteRepository;
import com.app.modules.social.service.MuteService;
import com.app.modules.users.service.UserSummaryService;

@Service
public class MuteServiceImpl implements MuteService {

    private static final String AGGREGATE_TYPE_USER = "user";
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final UserMuteRepository userMuteRepository;
    private final SocialUserRepository socialUserRepository;
    private final BlockRepository blockRepository;
    private final UserSummaryService userSummaryService;
    private final OutboxService outboxService;

    public MuteServiceImpl(
            UserMuteRepository userMuteRepository,
            SocialUserRepository socialUserRepository,
            BlockRepository blockRepository,
            UserSummaryService userSummaryService,
            OutboxService outboxService) {
        this.userMuteRepository = userMuteRepository;
        this.socialUserRepository = socialUserRepository;
        this.blockRepository = blockRepository;
        this.userSummaryService = userSummaryService;
        this.outboxService = outboxService;
    }

    @Override
    @Transactional
    public MuteResponse setMute(
            UUID userId, UUID targetUserId, boolean mutePosts, boolean muteStories) {
        if (userId.equals(targetUserId)) {
            throw new AppException(ApiErrorCode.BAD_REQUEST, "You cannot mute yourself");
        }
        // A missing target and a block in either direction share one answer, so the status and
        // body cannot be used to learn that a block exists.
        if (!socialUserRepository.existsByIdAndDeletedAtIsNull(targetUserId)
                || blockRepository.existsBetween(userId, targetUserId)) {
            throw new AppException(ApiErrorCode.NOT_FOUND, "Target user not found");
        }
        if (!mutePosts && !muteStories) {
            removeAndAnnounce(userId, targetUserId);
        } else if (userMuteRepository.upsert(userId, targetUserId, mutePosts, muteStories) > 0) {
            // The upsert counts a row only when it was created or its scopes changed, so
            // repeating the same request enqueues nothing.
            outboxService.enqueue(
                    SocialEventTypes.USER_MUTED_V1,
                    SocialEventTypes.USER_MUTED_V1,
                    AGGREGATE_TYPE_USER,
                    userId,
                    userId,
                    Map.of(
                            "targetUserId",
                            targetUserId.toString(),
                            "mutePosts",
                            mutePosts,
                            "muteStories",
                            muteStories));
        }
        UserSummaryResponse summary =
                userSummaryService.loadSummaries(List.of(targetUserId)).get(targetUserId);
        return new MuteResponse(targetUserId, mutePosts, muteStories, summary);
    }

    @Override
    @Transactional
    public void unmute(UUID userId, UUID targetUserId) {
        removeAndAnnounce(userId, targetUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<MuteResponse> listMutes(UUID userId, String cursor, int limit) {
        int size = normalizeLimit(limit);
        Cursor decoded = CursorCodec.decode(cursor, CursorScope.SOCIAL_MUTES);
        Pageable pageable = PageRequest.of(0, size + 1);

        List<UserMute> mutes =
                decoded == null
                        ? userMuteRepository.findFirstMutes(userId, pageable)
                        : userMuteRepository.findMutesBefore(
                                userId,
                                TimeCursors.fromMicros(decoded.sortValueMicros()),
                                decoded.id(),
                                pageable);

        boolean hasNextPage = mutes.size() > size;
        if (hasNextPage) {
            mutes = mutes.subList(0, size);
        }
        if (mutes.isEmpty()) {
            return CursorPageResponse.of(
                    Collections.emptyList(), false, null, null, cursor != null);
        }

        List<UUID> mutedIds = mutes.stream().map(m -> m.getId().getMutedUserId()).toList();
        // Placeholder rather than drop, so the page length matches the row count even when a muted
        // account has since been soft-deleted.
        Map<UUID, UserSummaryResponse> summaries = userSummaryService.loadSummaries(mutedIds);
        List<MuteResponse> content =
                mutes.stream()
                        .map(
                                mute ->
                                        new MuteResponse(
                                                mute.getId().getMutedUserId(),
                                                mute.isMutePosts(),
                                                mute.isMuteStories(),
                                                summaries.get(mute.getId().getMutedUserId())))
                        .toList();

        UserMute first = mutes.get(0);
        UserMute last = mutes.get(mutes.size() - 1);
        return CursorPageResponse.of(
                content, hasNextPage, encodeCursor(first), encodeCursor(last), cursor != null);
    }

    private void removeAndAnnounce(UUID userId, UUID targetUserId) {
        if (userMuteRepository.deleteByUserAndMuted(userId, targetUserId) == 0) {
            return;
        }
        outboxService.enqueue(
                SocialEventTypes.USER_UNMUTED_V1,
                SocialEventTypes.USER_UNMUTED_V1,
                AGGREGATE_TYPE_USER,
                userId,
                userId,
                Map.of("targetUserId", targetUserId.toString()));
    }

    private static String encodeCursor(UserMute mute) {
        OffsetDateTime updatedAt = mute.getUpdatedAt();
        if (updatedAt == null) {
            return null;
        }
        return CursorCodec.encode(
                new Cursor(TimeCursors.toMicros(updatedAt), mute.getId().getMutedUserId()),
                CursorScope.SOCIAL_MUTES);
    }

    private static int normalizeLimit(int limit) {
        return limit > MAX_PAGE_SIZE ? MAX_PAGE_SIZE : (limit < 1 ? DEFAULT_PAGE_SIZE : limit);
    }
}
