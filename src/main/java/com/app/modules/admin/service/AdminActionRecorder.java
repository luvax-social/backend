package com.app.modules.admin.service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.app.common.outbox.service.OutboxService;
import com.app.modules.admin.dto.response.AdminActionResponse;
import com.app.modules.admin.entity.AdminAction;
import com.app.modules.admin.enums.AdminActionType;
import com.app.modules.admin.mapper.AdminActionMapper;
import com.app.modules.admin.messaging.AdminEventTypes;
import com.app.modules.admin.messaging.ModerationMailTemplates;
import com.app.modules.admin.repository.AdminActionRepository;

/**
 * Single writer for the {@code admin_actions} audit log.
 *
 * <p>Deliberately carries no transaction annotation so every call joins the caller's transaction.
 * An audit row that could commit independently of the state change it describes would let the log
 * and the data disagree, which defeats the point of an audit log.
 *
 * <p>{@code metadata} is server-side fact only. No caller may forward a client-supplied map here:
 * an audit log a client can write into records what the client claims happened, not what happened.
 *
 * <p>This is also where the moderation notice event is raised. Enqueuing it here rather than at
 * each of the nine calling paths means one insertion point instead of nine, inside the same
 * transaction as both the audit row and the state change, so a notice can never be sent for an
 * action that rolled back and an action can never commit without its notice enqueued. Which actions
 * mail is decided in one place by {@link ModerationMailTemplates}.
 */
@Component
public class AdminActionRecorder {

    /** Metadata key carrying the end of a fixed-term suspension, read into the notice payload. */
    public static final String SUSPENDED_UNTIL_KEY = "suspendedUntil";

    /**
     * Metadata key carrying the staff response text a support ticket notice renders.
     *
     * <p>The support ticket's internal note is deliberately never placed under this or any other
     * key. The note never enters the metadata map, so no template can render it even by accident.
     */
    public static final String SUPPORT_RESPONSE_KEY = "supportResponse";

    private final AdminActionRepository adminActionRepository;
    private final AdminActionMapper adminActionMapper;
    private final OutboxService outboxService;

    public AdminActionRecorder(
            AdminActionRepository adminActionRepository,
            AdminActionMapper adminActionMapper,
            OutboxService outboxService) {
        this.adminActionRepository = adminActionRepository;
        this.adminActionMapper = adminActionMapper;
        this.outboxService = outboxService;
    }

    /**
     * Appends one immutable audit row and returns it as a response.
     *
     * @param actorId the acting account, or null for an action taken by a scheduled job
     * @param actionType the moderation action performed
     * @param targetUserId the affected account, or null when the action has no user target
     * @param targetEntityType polymorphic target discriminator, for example {@code "user"}
     * @param targetEntityId polymorphic target identifier
     * @param reportId the report that prompted the action, or null
     * @param reason human-readable justification; trimmed before persisting
     * @param metadata server-derived structured context, or null
     * @return the persisted audit row, with {@code createdAt} already populated
     */
    public AdminActionResponse record(
            UUID actorId,
            AdminActionType actionType,
            UUID targetUserId,
            String targetEntityType,
            UUID targetEntityId,
            UUID reportId,
            String reason,
            Map<String, Object> metadata) {
        AdminAction action =
                AdminAction.builder()
                        .adminId(actorId)
                        .actionType(actionType)
                        .targetUserId(targetUserId)
                        .targetEntityType(targetEntityType)
                        .targetEntityId(targetEntityId)
                        .reportId(reportId)
                        .reason(reason == null ? null : reason.trim())
                        .metadata(metadata)
                        .build();
        AdminActionResponse response =
                adminActionMapper.toResponse(adminActionRepository.insert(action));
        enqueueModerationNotice(response, actionType, targetUserId, metadata);
        return response;
    }

    // The payload carries identifiers and the one date a template needs, and nothing else. The
    // reason is deliberately absent: it is written for colleagues and never reaches a recipient,
    // and
    // the outbox rejects a data map carrying credential-shaped keys in any case.
    private void enqueueModerationNotice(
            AdminActionResponse response,
            AdminActionType actionType,
            UUID targetUserId,
            Map<String, Object> metadata) {
        if (targetUserId == null || !ModerationMailTemplates.mails(actionType)) {
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("userId", targetUserId.toString());
        data.put("actionType", actionType.name());
        data.put("adminActionId", response.id().toString());
        Object suspendedUntil = metadata == null ? null : metadata.get(SUSPENDED_UNTIL_KEY);
        if (suspendedUntil != null) {
            data.put(SUSPENDED_UNTIL_KEY, suspendedUntil.toString());
        }
        Object supportResponse = metadata == null ? null : metadata.get(SUPPORT_RESPONSE_KEY);
        if (supportResponse != null) {
            data.put(SUPPORT_RESPONSE_KEY, supportResponse.toString());
        }
        outboxService.enqueue(
                AdminEventTypes.MODERATION_NOTICE_REQUESTED_V1,
                AdminEventTypes.MODERATION_NOTICE_REQUESTED_V1,
                "user",
                targetUserId,
                response.adminId(),
                data);
    }
}
