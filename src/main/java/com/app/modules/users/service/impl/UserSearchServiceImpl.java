package com.app.modules.users.service.impl;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.pagination.OffsetCursorCodec;
import com.app.common.response.CursorPageResponse;
import com.app.common.response.UserListItemResponse;
import com.app.common.response.UserSummaryResponse;
import com.app.common.response.ViewerRelationshipResponse;
import com.app.modules.recommendation.service.UserEventRecorder;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.repository.UserRepository;
import com.app.modules.users.repository.UserSearchProjection;
import com.app.modules.users.service.UserSearchService;

@Service
public class UserSearchServiceImpl implements UserSearchService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * Shortest accepted query.
     *
     * <p>A single character matches a large fraction of the table, which both degrades to a
     * sequential scan and makes the endpoint a cheap enumeration sweep. Two is the floor at which a
     * trigram index gives useful selectivity.
     */
    private static final int MIN_QUERY_LENGTH = 2;

    private final UserRepository userRepository;
    private final SocialService socialService;
    private final UserEventRecorder userEventRecorder;

    public UserSearchServiceImpl(
            UserRepository userRepository,
            SocialService socialService,
            UserEventRecorder userEventRecorder) {
        this.userRepository = userRepository;
        this.socialService = socialService;
        this.userEventRecorder = userEventRecorder;
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<UserListItemResponse> searchUsers(
            UUID viewerId, String query, String cursor, int limit) {
        String normalized = normalizeQuery(query);
        // After normalization, so a query rejected as too short records nothing. Before the read,
        // because the event records that somebody searched, not that the search returned anything.
        userEventRecorder.recordSearch(viewerId, "users", normalized);
        int offset = OffsetCursorCodec.decode(cursor);
        int size = normalizeLimit(limit);

        List<UserSearchProjection> hits =
                userRepository.searchByUsername(normalized, viewerId, size + 1, offset);

        boolean hasNextPage = hits.size() > size;
        if (hasNextPage) {
            hits = hits.subList(0, size);
        }

        if (hits.isEmpty()) {
            return CursorPageResponse.of(List.of(), false, null, null, offset > 0);
        }

        List<UUID> ids = hits.stream().map(UserSearchProjection::getId).toList();
        Map<UUID, ViewerRelationshipResponse> relationships =
                socialService.loadRelationships(viewerId, ids);

        List<UserListItemResponse> content =
                hits.stream()
                        .map(
                                hit ->
                                        new UserListItemResponse(
                                                new UserSummaryResponse(
                                                        hit.getId(),
                                                        hit.getUsername(),
                                                        hit.getDisplayName(),
                                                        hit.getAvatarUrl(),
                                                        hit.getIsVerified(),
                                                        hit.getVerifiedCategory()),
                                                relationships.getOrDefault(
                                                        hit.getId(),
                                                        ViewerRelationshipResponse.NONE)))
                        .toList();

        return CursorPageResponse.of(
                content,
                hasNextPage,
                OffsetCursorCodec.encode(offset),
                OffsetCursorCodec.encode(offset + content.size()),
                offset > 0);
    }

    private static String normalizeQuery(String raw) {
        String trimmed = raw == null ? "" : raw.strip();
        if (trimmed.length() < MIN_QUERY_LENGTH) {
            throw new AppException(
                    ApiErrorCode.BAD_REQUEST,
                    "Search query must be at least " + MIN_QUERY_LENGTH + " characters");
        }
        return trimmed;
    }

    private static int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(limit, MAX_PAGE_SIZE);
    }
}
