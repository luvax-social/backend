package com.app.modules.admin.service.impl;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.response.CursorPageResponse;
import com.app.modules.admin.dto.request.AdminUpdatePlaceRequest;
import com.app.modules.admin.dto.response.AdminActionResponse;
import com.app.modules.admin.enums.AdminActionType;
import com.app.modules.admin.service.AdminActionRecorder;
import com.app.modules.admin.service.AdminAuthorizationService;
import com.app.modules.admin.service.AdminPlaceService;
import com.app.modules.post.place.dto.response.PlaceAdminResponse;
import com.app.modules.post.place.enums.PlaceStatus;
import com.app.modules.post.place.service.PlaceLifecycleResult;
import com.app.modules.post.place.service.PlaceLifecycleService;
import com.app.modules.report.entity.Report;
import com.app.modules.report.enums.ReportStatus;
import com.app.modules.report.enums.ReportType;
import com.app.modules.report.repository.ReportRepository;

@Service
public class AdminPlaceServiceImpl implements AdminPlaceService {

    private static final String TARGET_ENTITY_TYPE = "place";

    private final PlaceLifecycleService placeLifecycleService;
    private final AdminActionRecorder adminActionRecorder;
    private final AdminAuthorizationService adminAuthorizationService;
    private final ReportRepository reportRepository;

    public AdminPlaceServiceImpl(
            PlaceLifecycleService placeLifecycleService,
            AdminActionRecorder adminActionRecorder,
            AdminAuthorizationService adminAuthorizationService,
            ReportRepository reportRepository) {
        this.placeLifecycleService = placeLifecycleService;
        this.adminActionRecorder = adminActionRecorder;
        this.adminAuthorizationService = adminAuthorizationService;
        this.reportRepository = reportRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<PlaceAdminResponse> listPlaces(
            UUID actorId, PlaceStatus status, String cursor, int limit) {
        adminAuthorizationService.assertActorIsAdministrator(actorId);
        return placeLifecycleService.list(status, cursor, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<PlaceAdminResponse> searchPlaces(
            UUID actorId, String query, PlaceStatus status, String cursor, int limit) {
        adminAuthorizationService.assertActorIsAdministrator(actorId);
        return placeLifecycleService.search(query, status, cursor, limit);
    }

    @Override
    @Transactional
    public AdminActionResponse updatePlace(
            UUID actorId, UUID placeId, AdminUpdatePlaceRequest request) {
        adminAuthorizationService.assertActorIsAdministrator(actorId);
        boolean removes = request.status() != PlaceStatus.ACTIVE;
        String note = request.note() == null ? "" : request.note().strip();
        // Banning and deleting hide a name from every surface, so each demands a reason, which the
        // audit row then carries; bringing a place back does not.
        if (removes && note.isEmpty()) {
            throw new AppException(ApiErrorCode.BAD_REQUEST, "A reason is required");
        }
        Report linkedReport = findLinkedReport(request.reportId(), placeId, removes);
        PlaceLifecycleResult result =
                placeLifecycleService.changeStatus(actorId, placeId, request.status(), note);
        if (removes) {
            resolveLinkedReport(linkedReport, actorId, note);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", result.name());
        metadata.put("resultingStatus", result.status().toJson());
        metadata.put("previousStatus", result.previousStatus().toJson());
        metadata.put("affectedPosts", result.affectedPosts());
        return adminActionRecorder.record(
                actorId,
                auditActionFor(result),
                null,
                TARGET_ENTITY_TYPE,
                result.placeId(),
                request.reportId(),
                note.isEmpty() ? null : note,
                metadata);
    }

    // Mapped from the target: reaching banned is a ban and reaching deleted is a delete whichever
    // state it came from, and reaching active is the unban, including from deleted, so the registry
    // needs no separate restore action.
    private static AdminActionType auditActionFor(PlaceLifecycleResult result) {
        return switch (result.status()) {
            case BANNED -> AdminActionType.BAN_PLACE;
            case DELETED -> AdminActionType.DELETE_PLACE;
            case ACTIVE -> AdminActionType.UNBAN_PLACE;
        };
    }

    private Report findLinkedReport(UUID reportId, UUID placeId, boolean closesReport) {
        if (reportId == null) {
            return null;
        }
        Report report =
                reportRepository
                        .findById(reportId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.REPORT_NOT_FOUND));
        if (report.getReportType() != ReportType.PLACE || !placeId.equals(report.getEntityId())) {
            throw new AppException(ApiErrorCode.REPORT_INVALID_TRANSITION);
        }
        if (closesReport
                && (report.getStatus() == ReportStatus.RESOLVED
                        || report.getStatus() == ReportStatus.DISMISSED)) {
            throw new AppException(ApiErrorCode.REPORT_INVALID_TRANSITION);
        }
        return report;
    }

    private void resolveLinkedReport(Report report, UUID actorId, String reason) {
        if (report == null
                || report.getStatus() == ReportStatus.RESOLVED
                || report.getStatus() == ReportStatus.DISMISSED) {
            return;
        }
        report.setStatus(ReportStatus.RESOLVED);
        report.setReviewedBy(actorId);
        report.setReviewedAt(OffsetDateTime.now());
        report.setResolutionNote(reason);
        reportRepository.save(report);
    }
}
