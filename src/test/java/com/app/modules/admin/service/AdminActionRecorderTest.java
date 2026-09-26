package com.app.modules.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.outbox.service.OutboxService;
import com.app.modules.admin.dto.response.AdminActionResponse;
import com.app.modules.admin.enums.AdminActionType;
import com.app.modules.admin.mapper.AdminActionMapper;
import com.app.modules.admin.messaging.AdminEventTypes;
import com.app.modules.admin.repository.AdminActionRepository;

@ExtendWith(MockitoExtension.class)
class AdminActionRecorderTest {

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID TARGET_ID = UUID.randomUUID();
    private static final UUID ACTION_ID = UUID.randomUUID();

    @Mock private AdminActionRepository adminActionRepository;
    @Mock private AdminActionMapper adminActionMapper;
    @Mock private OutboxService outboxService;

    private AdminActionRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new AdminActionRecorder(adminActionRepository, adminActionMapper, outboxService);
        lenient().when(adminActionRepository.insert(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(adminActionMapper.toResponse(any())).thenReturn(response());
    }

    // The nine actions that mail their subject. Each is exercised through the same single insertion
    // point every calling path uses, which is what makes one assertion cover all nine paths.
    @ParameterizedTest
    @EnumSource(
            value = AdminActionType.class,
            names = {
                "BAN_USER",
                "UNBAN_USER",
                "SUSPEND_USER",
                "UNSUSPEND_USER",
                "WARN_USER",
                "REMOVE_POST",
                "REMOVE_COMMENT",
                "REMOVE_STORY",
                "REMOVE_MESSAGE"
            })
    void record_mailingAction_enqueuesTheNotice(AdminActionType actionType) {
        recorder.record(ACTOR_ID, actionType, TARGET_ID, "user", TARGET_ID, null, "internal", null);

        verify(outboxService)
                .enqueue(
                        eq(AdminEventTypes.MODERATION_NOTICE_REQUESTED_V1),
                        eq(AdminEventTypes.MODERATION_NOTICE_REQUESTED_V1),
                        eq("user"),
                        eq(TARGET_ID),
                        any(),
                        any());
        assertThat(capturedPayload())
                .containsEntry("actionType", actionType.name())
                .containsEntry("userId", TARGET_ID.toString())
                .containsEntry("adminActionId", ACTION_ID.toString());
    }

    // Restores, strikes, revocations, session actions, role changes and every hashtag action are
    // absent from the mapping on purpose, so none of them raises a notice.
    @ParameterizedTest
    @EnumSource(
            value = AdminActionType.class,
            names = {
                "RESTORE_POST",
                "RESTORE_COMMENT",
                "RESTORE_STORY",
                "RESTORE_MESSAGE",
                "ISSUE_STRIKE",
                "REVOKE_WARNING",
                "REVOKE_STRIKE",
                "FORCE_LOGOUT",
                "REVOKE_SESSION",
                "CHANGE_USER_ROLE",
                "ESCALATE_REPORT",
                "CREATE_HASHTAG",
                "BAN_HASHTAG",
                "UNBAN_HASHTAG",
                "DELETE_HASHTAG",
                "EDIT_HASHTAG",
                "RESOLVE_REPORT",
                "DISMISS_REPORT"
            })
    void record_nonMailingAction_enqueuesNothing(AdminActionType actionType) {
        recorder.record(ACTOR_ID, actionType, TARGET_ID, "user", TARGET_ID, null, "internal", null);

        verify(outboxService, never())
                .enqueue(anyString(), anyString(), anyString(), any(), any(), any());
    }

    // A message whose sender's account was hard-deleted has no target user, so there is nobody to
    // tell and no notice is raised.
    @Test
    void record_mailingActionWithNoTargetUser_enqueuesNothing() {
        recorder.record(
                ACTOR_ID,
                AdminActionType.REMOVE_MESSAGE,
                null,
                "message",
                TARGET_ID,
                null,
                "x",
                null);

        verify(outboxService, never())
                .enqueue(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void record_suspension_carriesTheEndDateIntoThePayload() {
        OffsetDateTime until = OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC);

        recorder.record(
                ACTOR_ID,
                AdminActionType.SUSPEND_USER,
                TARGET_ID,
                "user",
                TARGET_ID,
                null,
                "internal",
                Map.of(AdminActionRecorder.SUSPENDED_UNTIL_KEY, until.toString()));

        assertThat(capturedPayload())
                .containsEntry(AdminActionRecorder.SUSPENDED_UNTIL_KEY, until.toString());
    }

    // admin_actions.reason is written for colleagues. It must never reach a recipient, so it never
    // enters the payload the notice is rendered from.
    @Test
    void record_anyMailingAction_neverCarriesTheReason() {
        recorder.record(
                ACTOR_ID,
                AdminActionType.BAN_USER,
                TARGET_ID,
                "user",
                TARGET_ID,
                UUID.randomUUID(),
                "Repeated harassment, see ticket 4471",
                null);

        assertThat(capturedPayload())
                .doesNotContainKeys("reason", "reportId")
                .containsOnlyKeys("userId", "actionType", "adminActionId");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedPayload() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(outboxService)
                .enqueue(anyString(), anyString(), anyString(), any(), any(), captor.capture());
        return captor.getValue();
    }

    private static AdminActionResponse response() {
        return new AdminActionResponse(
                ACTION_ID,
                ACTOR_ID,
                AdminActionType.BAN_USER,
                TARGET_ID,
                "user",
                TARGET_ID,
                null,
                null,
                null,
                OffsetDateTime.now(ZoneOffset.UTC));
    }
}
