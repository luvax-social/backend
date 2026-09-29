package com.app.modules.admin.service.impl;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.analytics.ClickHouseUnavailableException;
import com.app.common.analytics.observability.AnalyticsMetrics;
import com.app.modules.admin.dto.response.AdminActionSummaryResponse;
import com.app.modules.admin.enums.AdminActionType;
import com.app.modules.admin.mapper.AdminActionMapper;
import com.app.modules.admin.repository.AdminActionAnalyticsRepository;
import com.app.modules.admin.repository.AdminActionRepository;
import com.app.modules.admin.service.AdminActionListingService;

@Service
public class AdminActionListingServiceImpl implements AdminActionListingService {

    private static final Logger log = LoggerFactory.getLogger(AdminActionListingServiceImpl.class);

    private final AdminActionAnalyticsRepository analyticsRepository;
    private final AdminActionRepository adminActionRepository;
    private final AdminActionMapper adminActionMapper;
    private final AnalyticsMetrics analyticsMetrics;

    public AdminActionListingServiceImpl(
            AdminActionAnalyticsRepository analyticsRepository,
            AdminActionRepository adminActionRepository,
            AdminActionMapper adminActionMapper,
            AnalyticsMetrics analyticsMetrics) {
        this.analyticsRepository = analyticsRepository;
        this.adminActionRepository = adminActionRepository;
        this.adminActionMapper = adminActionMapper;
        this.analyticsMetrics = analyticsMetrics;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminActionSummaryResponse> findActions(
            UUID adminId,
            UUID targetUserId,
            AdminActionType actionType,
            OffsetDateTime from,
            OffsetDateTime to,
            OffsetDateTime cursorCreatedAt,
            UUID cursorId,
            int limit) {
        try {
            return analyticsRepository.findActions(
                    adminId, targetUserId, actionType, from, to, cursorCreatedAt, cursorId, limit);
        } catch (ClickHouseUnavailableException e) {
            // Only unavailability falls back. A rejected request is a bug in this query, and
            // hiding it behind PostgreSQL would let it ship unnoticed.
            analyticsMetrics.recordAuditLogFallback();
            log.warn(
                    "Audit log served from PostgreSQL because ClickHouse is unavailable: {}",
                    e.getMessage());
            return adminActionMapper.toSummaryResponseList(
                    adminActionRepository.findActions(
                            adminId,
                            targetUserId,
                            actionType,
                            from,
                            to,
                            cursorCreatedAt,
                            cursorId,
                            limit));
        }
    }
}
