package com.app.modules.admin.service.impl;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.pagination.Cursor;
import com.app.common.pagination.CursorCodec;
import com.app.common.pagination.CursorScope;
import com.app.common.pagination.KeysetPage;
import com.app.common.pagination.TimeCursors;
import com.app.common.response.CursorPageResponse;
import com.app.modules.admin.service.AdminAuthorizationService;
import com.app.modules.admin.service.AdminUserEventService;
import com.app.modules.recommendation.dto.response.UserEventResponse;
import com.app.modules.recommendation.entity.UserEvent;
import com.app.modules.recommendation.enums.UserEventType;
import com.app.modules.recommendation.repository.UserEventRepository;

@Service
public class AdminUserEventServiceImpl implements AdminUserEventService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final UserEventRepository userEventRepository;
    private final AdminAuthorizationService adminAuthorizationService;

    public AdminUserEventServiceImpl(
            UserEventRepository userEventRepository,
            AdminAuthorizationService adminAuthorizationService) {
        this.userEventRepository = userEventRepository;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<UserEventResponse> listUserEvents(
            UUID actorId,
            UUID userId,
            OffsetDateTime from,
            OffsetDateTime to,
            UserEventType eventType,
            String cursor,
            int limit) {
        adminAuthorizationService.assertActorIsAdministrator(actorId);
        validateWindow(from, to);
        int pageSize = normalizeLimit(limit);
        Cursor decoded = CursorCodec.decode(cursor, CursorScope.ADMIN_USER_EVENTS);
        List<UserEvent> rows =
                userEventRepository.findPage(
                        userId,
                        from,
                        to,
                        eventType,
                        decoded == null ? null : TimeCursors.fromMicros(decoded.sortValueMicros()),
                        decoded == null ? null : decoded.id(),
                        pageSize + 1);
        KeysetPage.Result<UserEvent> page =
                KeysetPage.of(
                        rows,
                        pageSize,
                        row -> new Cursor(TimeCursors.toMicros(row.getCreatedAt()), row.getId()),
                        CursorScope.ADMIN_USER_EVENTS);
        return CursorPageResponse.of(
                page.content().stream().map(AdminUserEventServiceImpl::toResponse).toList(),
                page.hasNextPage(),
                page.startCursor(),
                page.endCursor(),
                cursor != null);
    }

    private static void validateWindow(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null) {
            throw new AppException(
                    ApiErrorCode.BAD_REQUEST,
                    "Both 'from' and 'to' are required; the activity log has no unbounded read");
        }
        if (!to.isAfter(from)) {
            throw new AppException(ApiErrorCode.BAD_REQUEST, "'to' must be later than 'from'");
        }
        if (Duration.between(from, to).compareTo(Duration.ofDays(MAX_WINDOW_DAYS)) > 0) {
            throw new AppException(
                    ApiErrorCode.BAD_REQUEST,
                    "The window may span at most " + MAX_WINDOW_DAYS + " days");
        }
    }

    private static int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    private static UserEventResponse toResponse(UserEvent event) {
        return new UserEventResponse(
                event.getId(),
                event.getUserId(),
                event.getEventType(),
                event.getEntityType(),
                event.getEntityId(),
                event.getMetadata(),
                event.getCreatedAt());
    }
}
