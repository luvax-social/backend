package com.app.modules.admin.service.impl;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.app.common.analytics.ClickHouseUnavailableException;
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
import com.app.modules.recommendation.enums.UserEventType;
import com.app.modules.recommendation.repository.UserEventAnalyticsRepository;
import com.app.modules.recommendation.repository.UserEventRow;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class AdminUserEventServiceImpl implements AdminUserEventService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserEventServiceImpl.class);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final TypeReference<Map<String, Object>> METADATA_TYPE =
            new TypeReference<>() {};

    private final UserEventAnalyticsRepository userEventAnalyticsRepository;
    private final AdminAuthorizationService adminAuthorizationService;
    private final ObjectMapper objectMapper;

    public AdminUserEventServiceImpl(
            UserEventAnalyticsRepository userEventAnalyticsRepository,
            AdminAuthorizationService adminAuthorizationService,
            ObjectMapper objectMapper) {
        this.userEventAnalyticsRepository = userEventAnalyticsRepository;
        this.adminAuthorizationService = adminAuthorizationService;
        this.objectMapper = objectMapper;
    }

    // No transaction: the only PostgreSQL read is the administrator check, which owns its own, and
    // a read-only transaction here would hold a connection open across the ClickHouse call.
    @Override
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
        List<UserEventRow> rows;
        try {
            rows =
                    userEventAnalyticsRepository.findPage(
                            userId,
                            from,
                            to,
                            eventType,
                            decoded == null
                                    ? null
                                    : TimeCursors.fromMicros(decoded.sortValueMicros()),
                            decoded == null ? null : decoded.id(),
                            pageSize + 1);
        } catch (ClickHouseUnavailableException ex) {
            log.warn("Activity log unavailable: {}", ex.getMessage());
            throw new AppException(ApiErrorCode.ANALYTICS_UNAVAILABLE);
        }
        KeysetPage.Result<UserEventRow> page =
                KeysetPage.of(
                        rows,
                        pageSize,
                        row -> new Cursor(TimeCursors.toMicros(row.createdAt()), row.id()),
                        CursorScope.ADMIN_USER_EVENTS);
        return CursorPageResponse.of(
                page.content().stream().map(this::toResponse).toList(),
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

    private UserEventResponse toResponse(UserEventRow event) {
        return new UserEventResponse(
                event.id(),
                event.userId(),
                event.eventType(),
                event.entityType(),
                event.entityId(),
                parseMetadata(event),
                event.createdAt());
    }

    // The metadata is stored as JSON text, empty when the event carried none. A value that no
    // longer parses is shown as absent rather than failing the whole page over one row.
    private Map<String, Object> parseMetadata(UserEventRow event) {
        String text = event.metadata();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(text, METADATA_TYPE);
        } catch (RuntimeException ex) {
            log.warn("Event {} carries metadata that is not a JSON object", event.id());
            return null;
        }
    }
}
