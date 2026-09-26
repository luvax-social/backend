package com.app.modules.admin.service.impl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.response.CursorPageResponse;
import com.app.modules.admin.dto.request.AdminCreateHashtagRequest;
import com.app.modules.admin.dto.request.AdminDeleteHashtagRequest;
import com.app.modules.admin.dto.request.AdminUpdateHashtagRequest;
import com.app.modules.admin.dto.response.AdminActionResponse;
import com.app.modules.admin.enums.AdminActionType;
import com.app.modules.admin.service.AdminActionRecorder;
import com.app.modules.admin.service.AdminAuthorizationService;
import com.app.modules.admin.service.AdminHashtagService;
import com.app.modules.hashtag.dto.response.HashtagAdminResponse;
import com.app.modules.hashtag.enums.HashtagStatus;
import com.app.modules.hashtag.service.HashtagLifecycleResult;
import com.app.modules.hashtag.service.HashtagLifecycleService;

@Service
public class AdminHashtagServiceImpl implements AdminHashtagService {

    private static final String TARGET_ENTITY_TYPE = "hashtag";

    private final HashtagLifecycleService hashtagLifecycleService;
    private final AdminActionRecorder adminActionRecorder;
    private final AdminAuthorizationService adminAuthorizationService;

    public AdminHashtagServiceImpl(
            HashtagLifecycleService hashtagLifecycleService,
            AdminActionRecorder adminActionRecorder,
            AdminAuthorizationService adminAuthorizationService) {
        this.hashtagLifecycleService = hashtagLifecycleService;
        this.adminActionRecorder = adminActionRecorder;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<HashtagAdminResponse> listHashtags(
            UUID actorId, HashtagStatus status, String cursor, int limit) {
        adminAuthorizationService.assertActorIsAdministrator(actorId);
        return hashtagLifecycleService.list(status, cursor, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<HashtagAdminResponse> searchHashtags(
            UUID actorId, String query, HashtagStatus status, String cursor, int limit) {
        adminAuthorizationService.assertActorIsAdministrator(actorId);
        return hashtagLifecycleService.search(query, status, cursor, limit);
    }

    @Override
    @Transactional
    public AdminActionResponse createHashtag(UUID actorId, AdminCreateHashtagRequest request) {
        adminAuthorizationService.assertActorIsAdministrator(actorId);
        HashtagLifecycleResult result =
                hashtagLifecycleService.create(
                        actorId, request.name(), request.status(), request.note());
        return record(actorId, AdminActionType.CREATE_HASHTAG, result, request.note());
    }

    @Override
    @Transactional
    public AdminActionResponse updateHashtag(
            UUID actorId, UUID hashtagId, AdminUpdateHashtagRequest request) {
        adminAuthorizationService.assertActorIsAdministrator(actorId);
        HashtagLifecycleResult result =
                hashtagLifecycleService.changeStatus(
                        actorId, hashtagId, request.status(), request.note());
        return record(actorId, auditActionFor(result), result, request.note());
    }

    @Override
    @Transactional
    public AdminActionResponse deleteHashtag(
            UUID actorId, UUID hashtagId, AdminDeleteHashtagRequest request) {
        adminAuthorizationService.assertActorIsAdministrator(actorId);
        HashtagLifecycleResult result =
                hashtagLifecycleService.changeStatus(
                        actorId, hashtagId, HashtagStatus.DELETED, request.reason());
        return record(actorId, AdminActionType.DELETE_HASHTAG, result, request.reason());
    }

    // Mapped from the transition, not from the target alone. Reaching 'banned' is a ban and
    // reaching 'deleted' is a delete whichever state it came from, but reaching 'active' is an
    // unban only when it came from 'banned'. Coming back from 'deleted' has no audit value of its
    // own, and edit_hashtag is what that value is for: a status edit that is none of the three
    // named transitions.
    private static AdminActionType auditActionFor(HashtagLifecycleResult result) {
        return switch (result.status()) {
            case BANNED -> AdminActionType.BAN_HASHTAG;
            case DELETED -> AdminActionType.DELETE_HASHTAG;
            case ACTIVE ->
                    result.previousStatus() == HashtagStatus.BANNED
                            ? AdminActionType.UNBAN_HASHTAG
                            : AdminActionType.EDIT_HASHTAG;
        };
    }

    @Override
    @Transactional
    public AdminActionResponse pinHashtag(UUID actorId, UUID hashtagId, String note) {
        adminAuthorizationService.assertActorIsAdministrator(actorId);
        HashtagLifecycleResult result = hashtagLifecycleService.pin(actorId, hashtagId);
        return record(actorId, AdminActionType.PIN_HASHTAG, result, note);
    }

    @Override
    @Transactional
    public AdminActionResponse unpinHashtag(UUID actorId, UUID hashtagId, String note) {
        adminAuthorizationService.assertActorIsAdministrator(actorId);
        HashtagLifecycleResult result = hashtagLifecycleService.unpin(actorId, hashtagId);
        return record(actorId, AdminActionType.UNPIN_HASHTAG, result, note);
    }

    // Every value here is a fact this transaction established, which is the only thing
    // AdminActionRecorder accepts. No hashtag has a target user, so target_user_id stays null; the
    // hashtag itself is the target entity. No report can name a hashtag either - report_type covers
    // post, comment, user, story and message - so report_id stays null too.
    private AdminActionResponse record(
            UUID actorId,
            AdminActionType actionType,
            HashtagLifecycleResult result,
            String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", result.name());
        metadata.put("resultingStatus", result.status().toJson());
        if (result.previousStatus() != null) {
            metadata.put("previousStatus", result.previousStatus().toJson());
        }
        if (result.purgedTrendingRows() > 0) {
            metadata.put("purgedTrendingRows", result.purgedTrendingRows());
        }
        return adminActionRecorder.record(
                actorId,
                actionType,
                null,
                TARGET_ENTITY_TYPE,
                result.hashtagId(),
                null,
                reason,
                metadata);
    }
}
