package com.app.modules.admin.service.impl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.response.CursorPageResponse;
import com.app.modules.admin.dto.request.AdminDeleteHiddenWordRequest;
import com.app.modules.admin.dto.request.AdminHiddenWordRequest;
import com.app.modules.admin.enums.AdminActionType;
import com.app.modules.admin.service.AdminActionRecorder;
import com.app.modules.admin.service.AdminAuthorizationService;
import com.app.modules.admin.service.AdminHiddenWordService;
import com.app.modules.social.dto.response.AdminHiddenWordResponse;
import com.app.modules.social.service.HiddenWordLifecycleResult;
import com.app.modules.social.service.HiddenWordLifecycleService;

@Service
public class AdminHiddenWordServiceImpl implements AdminHiddenWordService {

    private static final String TARGET_ENTITY_TYPE = "hidden_word";

    private final HiddenWordLifecycleService hiddenWordLifecycleService;
    private final AdminActionRecorder adminActionRecorder;
    private final AdminAuthorizationService adminAuthorizationService;

    public AdminHiddenWordServiceImpl(
            HiddenWordLifecycleService hiddenWordLifecycleService,
            AdminActionRecorder adminActionRecorder,
            AdminAuthorizationService adminAuthorizationService) {
        this.hiddenWordLifecycleService = hiddenWordLifecycleService;
        this.adminActionRecorder = adminActionRecorder;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<AdminHiddenWordResponse> list(
            UUID actorId, String query, String cursor, int limit) {
        adminAuthorizationService.assertActorIsAdministrator(actorId);
        return hiddenWordLifecycleService.list(query, cursor, limit);
    }

    @Override
    @Transactional
    public AdminHiddenWordResponse create(UUID actorId, AdminHiddenWordRequest request) {
        adminAuthorizationService.assertActorIsAdministrator(actorId);
        HiddenWordLifecycleResult result =
                hiddenWordLifecycleService.create(actorId, request.phrase());
        record(actorId, AdminActionType.CREATE_HIDDEN_WORD, result, request.reason());
        return result.word();
    }

    @Override
    @Transactional
    public AdminHiddenWordResponse update(
            UUID actorId, UUID hiddenWordId, AdminHiddenWordRequest request) {
        adminAuthorizationService.assertActorIsAdministrator(actorId);
        HiddenWordLifecycleResult result =
                hiddenWordLifecycleService.update(hiddenWordId, request.phrase());
        record(actorId, AdminActionType.EDIT_HIDDEN_WORD, result, request.reason());
        return result.word();
    }

    @Override
    @Transactional
    public void delete(UUID actorId, UUID hiddenWordId, AdminDeleteHiddenWordRequest request) {
        adminAuthorizationService.assertActorIsAdministrator(actorId);
        HiddenWordLifecycleResult result = hiddenWordLifecycleService.delete(hiddenWordId);
        record(actorId, AdminActionType.DELETE_HIDDEN_WORD, result, request.reason());
    }

    // Every value here is a fact this transaction established, which is the only thing
    // AdminActionRecorder accepts. A hidden word has no target user and no report behind it, so
    // those stay null; the entry itself is the target entity. Deleting removes the row, so the
    // metadata is where the phrase survives.
    private void record(
            UUID actorId,
            AdminActionType actionType,
            HiddenWordLifecycleResult result,
            String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("phrase", result.phrase());
        metadata.put("normalizedPhrase", result.normalizedPhrase());
        if (result.previousPhrase() != null) {
            metadata.put("previousPhrase", result.previousPhrase());
            metadata.put("previousNormalizedPhrase", result.previousNormalizedPhrase());
        }
        adminActionRecorder.record(
                actorId, actionType, null, TARGET_ENTITY_TYPE, result.id(), null, reason, metadata);
    }
}
