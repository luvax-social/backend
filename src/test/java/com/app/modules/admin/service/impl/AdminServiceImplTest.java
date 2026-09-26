package com.app.modules.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.modules.admin.dto.request.AdminActionRequest;
import com.app.modules.admin.dto.request.AdminSuspendUserRequest;
import com.app.modules.admin.dto.response.AdminActionResponse;
import com.app.modules.admin.dto.response.AdminActionSummaryResponse;
import com.app.modules.admin.dto.response.AdminPostRestoreResponse;
import com.app.modules.admin.entity.AdminAction;
import com.app.modules.admin.enums.AdminActionType;
import com.app.modules.admin.mapper.AdminActionMapper;
import com.app.modules.admin.repository.AdminActionRepository;
import com.app.modules.admin.service.AdminActionRecorder;
import com.app.modules.comment.repository.CommentRepository;
import com.app.modules.message.repository.MessageRepository;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.service.NotificationDraft;
import com.app.modules.notification.service.NotificationService;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.service.PostModerationResult;
import com.app.modules.post.service.PostService;
import com.app.modules.report.entity.Report;
import com.app.modules.report.enums.ReportReason;
import com.app.modules.report.enums.ReportStatus;
import com.app.modules.report.enums.ReportType;
import com.app.modules.report.repository.ReportRepository;
import com.app.modules.story.repository.StoryRepository;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class AdminServiceImplTest {

    @Mock private AdminActionRepository adminActionRepository;
    @Mock private UserRepository userRepository;
    @Mock private OutboxService outboxService;
    @Mock private PostRepository postRepository;
    @Mock private PostService postService;
    @Mock private CommentRepository commentRepository;
    @Mock private StoryRepository storyRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private ReportRepository reportRepository;
    @Mock private AdminActionMapper adminActionMapper;
    @Mock private NotificationService notificationService;
    @Mock private com.app.modules.support.service.VerificationService verificationService;

    private AdminServiceImpl service;

    @BeforeEach
    void setUp() {
        service =
                new AdminServiceImpl(
                        adminActionRepository,
                        userRepository,
                        postRepository,
                        postService,
                        commentRepository,
                        storyRepository,
                        messageRepository,
                        reportRepository,
                        adminActionMapper,
                        new AdminActionRecorder(
                                adminActionRepository, adminActionMapper, outboxService),
                        new AdminAuthorizationServiceImpl(userRepository),
                        notificationService,
                        verificationService);
    }

    private void stubActor(UUID actorId, UserRole role) {
        when(userRepository.findByIdAndDeletedAtIsNull(actorId))
                .thenReturn(
                        Optional.of(
                                User.builder()
                                        .id(actorId)
                                        .role(role)
                                        .status(UserStatus.ACTIVE)
                                        .build()));
    }

    @Test
    void suspendUser_activeUser_updatesAndAudits() {
        UUID actorId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        User user = User.builder().id(userId).role(UserRole.USER).status(UserStatus.ACTIVE).build();
        AdminActionResponse expected = response(AdminActionType.SUSPEND_USER);
        stubActor(actorId, UserRole.ADMIN);
        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        stubAudit(expected);

        AdminActionResponse result =
                service.suspendUser(
                        actorId, userId, new AdminSuspendUserRequest("Policy breach", null, null));

        assertThat(result).isEqualTo(expected);
        assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        verify(userRepository).save(user);
        ArgumentCaptor<AdminAction> action = ArgumentCaptor.forClass(AdminAction.class);
        verify(adminActionRepository).insert(action.capture());
        assertThat(action.getValue().getAdminId()).isEqualTo(actorId);
        assertThat(action.getValue().getTargetUserId()).isEqualTo(userId);
    }

    @Test
    void suspendUser_durationOmitted_leavesTheEndTimeNull() {
        UUID actorId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        User user = User.builder().id(userId).role(UserRole.USER).status(UserStatus.ACTIVE).build();
        stubActor(actorId, UserRole.ADMIN);
        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        stubAudit(response(AdminActionType.SUSPEND_USER));

        service.suspendUser(
                actorId, userId, new AdminSuspendUserRequest("Policy breach", null, null));

        assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        // The reinstatement sweep matches on "suspended_until IS NOT NULL AND suspended_until <=
        // now()", so a null end time is structurally outside it and the account stays suspended
        // until an administrator lifts it.
        assertThat(user.getSuspendedUntil()).isNull();
    }

    @Test
    void suspendUser_durationSupplied_setsAnEndTimeInTheFuture() {
        UUID actorId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        User user = User.builder().id(userId).role(UserRole.USER).status(UserStatus.ACTIVE).build();
        stubActor(actorId, UserRole.ADMIN);
        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        stubAudit(response(AdminActionType.SUSPEND_USER));

        service.suspendUser(actorId, userId, new AdminSuspendUserRequest("Policy breach", null, 7));

        assertThat(user.getSuspendedUntil()).isNotNull();
        assertThat(user.getSuspendedUntil()).isAfter(OffsetDateTime.now());
    }

    @Test
    void unbanUser_activeUser_throwsInvalidTransition() {
        UUID actorId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        stubActor(actorId, UserRole.ADMIN);
        when(userRepository.findByIdAndDeletedAtIsNull(userId))
                .thenReturn(
                        Optional.of(
                                User.builder()
                                        .id(userId)
                                        .role(UserRole.USER)
                                        .status(UserStatus.ACTIVE)
                                        .build()));

        assertThatThrownBy(
                        () ->
                                service.unbanUser(
                                        actorId, userId, new AdminActionRequest("Review", null)))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.ADMIN_INVALID_TRANSITION);
        verify(adminActionRepository, never()).insert(any());
    }

    @Test
    void changeUserStatus_actorIsTarget_throwsSelfActionNotAllowed() {
        UUID actorId = UUID.randomUUID();
        stubActor(actorId, UserRole.ADMIN);

        assertThatThrownBy(
                        () ->
                                service.suspendUser(
                                        actorId,
                                        actorId,
                                        new AdminSuspendUserRequest("Self action", null, null)))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.ADMIN_SELF_ACTION_NOT_ALLOWED);
        verify(userRepository, never()).save(any());
        verify(adminActionRepository, never()).insert(any());
    }

    @Test
    void changeUserStatus_targetIsAdministrator_throwsTargetProtected() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        stubActor(actorId, UserRole.ADMIN);
        stubActor(targetId, UserRole.ADMIN);

        assertThatThrownBy(
                        () ->
                                service.banUser(
                                        actorId,
                                        targetId,
                                        new AdminActionRequest("Admin on admin", null)))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.ADMIN_TARGET_PROTECTED);
        verify(userRepository, never()).save(any());
        verify(adminActionRepository, never()).insert(any());
    }

    @Test
    void changeUserStatus_actorIsModerator_throwsForbidden() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        stubActor(actorId, UserRole.MODERATOR);
        stubActor(targetId, UserRole.USER);

        assertThatThrownBy(
                        () ->
                                service.banUser(
                                        actorId,
                                        targetId,
                                        new AdminActionRequest("Moderator escalation", null)))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.FORBIDDEN);
        verify(userRepository, never()).save(any());
        verify(adminActionRepository, never()).insert(any());
    }

    @Test
    void removePost_livePost_delegatesEverySideEffectToThePostModule() {
        UUID postId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        AdminActionResponse expected = response(AdminActionType.REMOVE_POST);
        when(postRepository.findStatusIncludingDeleted(postId))
                .thenReturn(Optional.of("published"));
        when(postService.applyModerationRemoval(postId))
                .thenReturn(new PostModerationResult(ownerId, PostStatus.REMOVED, List.of()));
        stubAudit(expected);

        AdminActionResponse result =
                service.removePost(
                        UUID.randomUUID(), postId, new AdminActionRequest("Violation", null));

        assertThat(result).isEqualTo(expected);
        verify(postService).applyModerationRemoval(postId);
        verify(notificationService)
                .create(
                        notice(
                                ownerId,
                                NotificationType.POST_REMOVED,
                                "post",
                                postId,
                                postId,
                                "Violation"));
    }

    @Test
    void removePost_linkedReport_notifiesReporterThatReportedPostWasRemoved() {
        UUID postId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        AdminActionResponse expected = response(AdminActionType.REMOVE_POST);
        when(postRepository.findStatusIncludingDeleted(postId))
                .thenReturn(Optional.of("published"));
        when(reportRepository.findById(reportId))
                .thenReturn(
                        Optional.of(
                                Report.builder()
                                        .id(reportId)
                                        .reporterId(reporterId)
                                        .reportType(ReportType.POST)
                                        .reportReason(ReportReason.SPAM)
                                        .entityId(postId)
                                        .status(ReportStatus.PENDING)
                                        .build()));
        when(postService.applyModerationRemoval(postId))
                .thenReturn(new PostModerationResult(ownerId, PostStatus.REMOVED, List.of()));
        stubAudit(expected);

        AdminActionResponse result =
                service.removePost(
                        UUID.randomUUID(), postId, new AdminActionRequest("Violation", reportId));

        assertThat(result).isEqualTo(expected);
        verify(notificationService)
                .create(
                        notice(
                                reporterId,
                                NotificationType.REPORT_POST_REMOVED,
                                "report",
                                reportId,
                                postId,
                                null));
    }

    @Test
    void removePost_linkedReportForAnotherPost_throwsInvalidTransitionBeforeMutating() {
        UUID postId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        when(postRepository.findStatusIncludingDeleted(postId))
                .thenReturn(Optional.of("published"));
        when(reportRepository.findById(reportId))
                .thenReturn(
                        Optional.of(
                                Report.builder()
                                        .id(reportId)
                                        .reporterId(UUID.randomUUID())
                                        .reportType(ReportType.POST)
                                        .reportReason(ReportReason.SPAM)
                                        .entityId(UUID.randomUUID())
                                        .status(ReportStatus.PENDING)
                                        .build()));

        assertThatThrownBy(
                        () ->
                                service.removePost(
                                        UUID.randomUUID(),
                                        postId,
                                        new AdminActionRequest("Violation", reportId)))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.REPORT_INVALID_TRANSITION);
        verify(postService, never()).applyModerationRemoval(any());
        verify(adminActionRepository, never()).insert(any());
    }

    @Test
    void removePost_linkedReportForAnotherType_throwsInvalidTransitionBeforeMutating() {
        UUID postId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        when(postRepository.findStatusIncludingDeleted(postId))
                .thenReturn(Optional.of("published"));
        when(reportRepository.findById(reportId))
                .thenReturn(
                        Optional.of(
                                Report.builder()
                                        .id(reportId)
                                        .reporterId(UUID.randomUUID())
                                        .reportType(ReportType.COMMENT)
                                        .reportReason(ReportReason.SPAM)
                                        .entityId(postId)
                                        .status(ReportStatus.PENDING)
                                        .build()));

        assertThatThrownBy(
                        () ->
                                service.removePost(
                                        UUID.randomUUID(),
                                        postId,
                                        new AdminActionRequest("Violation", reportId)))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.REPORT_INVALID_TRANSITION);
        verify(postService, never()).applyModerationRemoval(any());
        verify(adminActionRepository, never()).insert(any());
    }

    @Test
    void removePost_escalatedReportByModerator_throwsForbiddenBeforeMutating() {
        UUID actorId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        stubActor(actorId, UserRole.MODERATOR);
        when(postRepository.findStatusIncludingDeleted(postId))
                .thenReturn(Optional.of("published"));
        when(reportRepository.findById(reportId))
                .thenReturn(
                        Optional.of(
                                Report.builder()
                                        .id(reportId)
                                        .reporterId(UUID.randomUUID())
                                        .reportType(ReportType.POST)
                                        .reportReason(ReportReason.SPAM)
                                        .entityId(postId)
                                        .status(ReportStatus.ESCALATED)
                                        .build()));

        assertThatThrownBy(
                        () ->
                                service.removePost(
                                        actorId,
                                        postId,
                                        new AdminActionRequest("Violation", reportId)))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.FORBIDDEN);
        verify(postService, never()).applyModerationRemoval(any());
        verify(adminActionRepository, never()).insert(any());
    }

    @Test
    void restorePost_removedPost_recordsTheStatusThePostReturnedTo() {
        UUID postId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        AdminActionResponse expected = response(AdminActionType.RESTORE_POST);
        when(postRepository.findStatusIncludingDeleted(postId)).thenReturn(Optional.of("removed"));
        when(postService.applyModerationRestore(postId))
                .thenReturn(new PostModerationResult(ownerId, PostStatus.DRAFT, List.of()));
        stubAudit(expected);

        AdminPostRestoreResponse result =
                service.restorePost(
                        UUID.randomUUID(), postId, new AdminActionRequest("Appeal accepted", null));

        assertThat(result.action()).isEqualTo(expected);
        assertThat(result.remainingBannedHashtags()).isEmpty();
        ArgumentCaptor<AdminAction> captor = ArgumentCaptor.forClass(AdminAction.class);
        verify(adminActionRepository).insert(captor.capture());
        assertThat(captor.getValue().getMetadata()).containsEntry("resultingStatus", "draft");
        verify(notificationService)
                .create(
                        notice(
                                ownerId,
                                NotificationType.POST_RESTORED,
                                "post",
                                postId,
                                postId,
                                "Appeal accepted"));
    }

    @Test
    void restorePost_captionNamingABannedTag_namesTheDroppedTagInTheResponse() {
        // The audit row already recorded the stripped names, but a moderator reads a response.
        // Left only in metadata the names are unreachable from a generated client, because
        // metadata is a free-form map shared by every action type.
        UUID postId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(postRepository.findStatusIncludingDeleted(postId)).thenReturn(Optional.of("removed"));
        when(postService.applyModerationRestore(postId))
                .thenReturn(
                        new PostModerationResult(
                                ownerId, PostStatus.PUBLISHED, List.of("laterbanned")));
        stubAudit(response(AdminActionType.RESTORE_POST));

        AdminPostRestoreResponse result =
                service.restorePost(
                        UUID.randomUUID(), postId, new AdminActionRequest("Appeal accepted", null));

        assertThat(result.remainingBannedHashtags()).containsExactly("laterbanned");
    }

    @Test
    void restoreComment_removedComment_clearsAdminRemovedAtAndAudits() {
        UUID commentId = UUID.randomUUID();
        when(commentRepository.findOwnerIdIncludingDeleted(commentId))
                .thenReturn(Optional.of(UUID.randomUUID()));
        when(commentRepository.isAdminRemoved(commentId)).thenReturn(Optional.of(true));
        AdminActionResponse expected = response(AdminActionType.RESTORE_COMMENT);
        stubAudit(expected);

        AdminActionResponse result =
                service.restoreComment(
                        UUID.randomUUID(),
                        commentId,
                        new AdminActionRequest("Appeal accepted", null));

        assertThat(result).isEqualTo(expected);
        verify(commentRepository).applyAdminModeration(commentId, null);
    }

    // The transition guard reads admin_removed_at, so a comment its author deleted is not a
    // restorable moderation target. Before V95 both intents shared deleted_at and this restore
    // would have succeeded, undoing the author's own deletion.
    @Test
    void restoreComment_authorDeletedButNotAdminRemoved_throwsInvalidTransition() {
        UUID commentId = UUID.randomUUID();
        when(commentRepository.findOwnerIdIncludingDeleted(commentId))
                .thenReturn(Optional.of(UUID.randomUUID()));
        when(commentRepository.isAdminRemoved(commentId)).thenReturn(Optional.of(false));

        assertThatThrownBy(
                        () ->
                                service.restoreComment(
                                        UUID.randomUUID(),
                                        commentId,
                                        new AdminActionRequest("Appeal accepted", null)))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.ADMIN_INVALID_TRANSITION);

        verify(commentRepository, never()).applyAdminModeration(any(), any());
    }

    @Test
    void restoreStory_ownerDeletedButNotAdminRemoved_throwsInvalidTransition() {
        UUID storyId = UUID.randomUUID();
        when(storyRepository.findOwnerIdIncludingDeleted(storyId))
                .thenReturn(Optional.of(UUID.randomUUID()));
        when(storyRepository.isAdminRemoved(storyId)).thenReturn(Optional.of(false));

        assertThatThrownBy(
                        () ->
                                service.restoreStory(
                                        UUID.randomUUID(),
                                        storyId,
                                        new AdminActionRequest("Appeal accepted", null)))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.ADMIN_INVALID_TRANSITION);

        verify(storyRepository, never()).applyAdminModeration(any(), any());
    }

    @Test
    void resolveReport_pendingReport_recordsReviewerAndAudit() {
        UUID actorId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        Report report =
                Report.builder()
                        .id(reportId)
                        .reportType(ReportType.POST)
                        .reportReason(ReportReason.SPAM)
                        .entityId(UUID.randomUUID())
                        .status(ReportStatus.PENDING)
                        .build();
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        AdminActionResponse expected = response(AdminActionType.RESOLVE_REPORT);
        stubAudit(expected);

        AdminActionResponse result =
                service.resolveReport(actorId, reportId, new AdminActionRequest("Confirmed", null));

        assertThat(result).isEqualTo(expected);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED);
        assertThat(report.getReviewedBy()).isEqualTo(actorId);
        assertThat(report.getResolutionNote()).isEqualTo("Confirmed");
        verify(reportRepository).save(report);
    }

    @Test
    void dismissReport_pendingReport_notifiesReporter() {
        UUID actorId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        Report report =
                Report.builder()
                        .id(reportId)
                        .reporterId(reporterId)
                        .reportType(ReportType.POST)
                        .reportReason(ReportReason.SPAM)
                        .entityId(UUID.randomUUID())
                        .status(ReportStatus.PENDING)
                        .build();
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        AdminActionResponse expected = response(AdminActionType.DISMISS_REPORT);
        stubAudit(expected);

        AdminActionResponse result =
                service.dismissReport(
                        actorId, reportId, new AdminActionRequest("No violation found", null));

        assertThat(result).isEqualTo(expected);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.DISMISSED);
        verify(notificationService)
                .create(
                        notice(
                                reporterId,
                                NotificationType.REPORT_DISMISSED,
                                "report",
                                reportId,
                                null,
                                "No violation found"));
    }

    @Test
    void resolveReport_terminalReport_throwsInvalidTransition() {
        UUID reportId = UUID.randomUUID();
        Report report = Report.builder().status(ReportStatus.DISMISSED).build();
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(
                        () ->
                                service.resolveReport(
                                        UUID.randomUUID(),
                                        reportId,
                                        new AdminActionRequest("Reopen not allowed", null)))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.REPORT_INVALID_TRANSITION);
    }

    @Test
    void getActions_firstPage_mapsContentAndProbeRow() {
        UUID actorId = UUID.randomUUID();
        stubActor(actorId, UserRole.ADMIN);
        AdminAction first = action(AdminActionType.BAN_USER);
        AdminAction probe = action(AdminActionType.REMOVE_POST);
        AdminActionSummaryResponse mapped = summary(AdminActionType.BAN_USER);
        when(adminActionRepository.findActions(null, null, null, null, null, null, null, 2))
                .thenReturn(List.of(first, probe));
        when(adminActionMapper.toSummaryResponseList(List.of(first))).thenReturn(List.of(mapped));

        var result = service.getActions(actorId, null, null, null, null, null, null, 1);

        assertThat(result.getContent()).containsExactly(mapped);
        assertThat(result.getPageInfo().isHasNextPage()).isTrue();
        assertThat(result.getPageInfo().getEndCursor()).isNotBlank();
    }

    // The predicate is asserted on the argument reaching the repository, not on the page that comes
    // back, so the test fails if the filter is ever moved to the web layer or dropped.
    @Test
    void getActions_moderatorActor_forcesTheActorFilterToItselfEvenWhenAnotherIsRequested() {
        UUID actorId = UUID.randomUUID();
        UUID otherAdminId = UUID.randomUUID();
        stubActor(actorId, UserRole.MODERATOR);
        when(adminActionRepository.findActions(actorId, null, null, null, null, null, null, 2))
                .thenReturn(List.of());

        service.getActions(actorId, otherAdminId, null, null, null, null, null, 1);

        verify(adminActionRepository).findActions(actorId, null, null, null, null, null, null, 2);
        verify(adminActionRepository, never())
                .findActions(eq(otherAdminId), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void getActions_administratorActor_passesTheRequestedActorFilterThrough() {
        UUID actorId = UUID.randomUUID();
        UUID otherAdminId = UUID.randomUUID();
        stubActor(actorId, UserRole.ADMIN);
        when(adminActionRepository.findActions(otherAdminId, null, null, null, null, null, null, 2))
                .thenReturn(List.of());

        service.getActions(actorId, otherAdminId, null, null, null, null, null, 1);

        verify(adminActionRepository)
                .findActions(otherAdminId, null, null, null, null, null, null, 2);
    }

    @Test
    void getActions_administratorActorWithNoFilter_readsEveryActorsRows() {
        UUID actorId = UUID.randomUUID();
        stubActor(actorId, UserRole.ADMIN);
        when(adminActionRepository.findActions(null, null, null, null, null, null, null, 2))
                .thenReturn(List.of());

        service.getActions(actorId, null, null, null, null, null, null, 1);

        verify(adminActionRepository).findActions(null, null, null, null, null, null, null, 2);
    }

    @Test
    void getActionsForUser_moderatorActor_narrowsToItsOwnRows() {
        UUID actorId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();
        stubActor(actorId, UserRole.MODERATOR);
        when(adminActionRepository.findActions(
                        actorId, targetUserId, null, null, null, null, null, 2))
                .thenReturn(List.of());

        service.getActionsForUser(actorId, targetUserId, null, 1);

        verify(adminActionRepository)
                .findActions(actorId, targetUserId, null, null, null, null, null, 2);
    }

    @Test
    void getActionsForUser_administratorActor_readsEveryActorsRows() {
        UUID actorId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();
        stubActor(actorId, UserRole.ADMIN);
        when(adminActionRepository.findActions(null, targetUserId, null, null, null, null, null, 2))
                .thenReturn(List.of());

        service.getActionsForUser(actorId, targetUserId, null, 1);

        verify(adminActionRepository)
                .findActions(null, targetUserId, null, null, null, null, null, 2);
    }

    @Test
    void getActionById_moderatorActorReadingItsOwnRow_returnsIt() {
        UUID actorId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        AdminAction own = action(AdminActionType.REMOVE_POST);
        own.setAdminId(actorId);
        AdminActionResponse expected = response(AdminActionType.REMOVE_POST);
        stubActor(actorId, UserRole.MODERATOR);
        when(adminActionRepository.findById(actionId)).thenReturn(Optional.of(own));
        when(adminActionMapper.toResponse(own)).thenReturn(expected);

        assertThat(service.getActionById(actorId, actionId)).isEqualTo(expected);
    }

    @Test
    void getActionById_moderatorActorReadingAnotherActorsRow_throwsNotFound() {
        UUID actorId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        AdminAction foreign = action(AdminActionType.BAN_USER);
        foreign.setAdminId(UUID.randomUUID());
        stubActor(actorId, UserRole.MODERATOR);
        when(adminActionRepository.findById(actionId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.getActionById(actorId, actionId))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.ADMIN_ACTION_NOT_FOUND);
    }

    @Test
    void getActionById_missingAction_throwsNotFound() {
        UUID actionId = UUID.randomUUID();
        when(adminActionRepository.findById(actionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getActionById(UUID.randomUUID(), actionId))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.ADMIN_ACTION_NOT_FOUND);
    }

    @Test
    void removeStory_liveStory_setsDeletedAtAndAudits() {
        UUID storyId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(storyRepository.findOwnerIdIncludingDeleted(storyId)).thenReturn(Optional.of(ownerId));
        when(storyRepository.isAdminRemoved(storyId)).thenReturn(Optional.of(false));
        AdminActionResponse expected = response(AdminActionType.REMOVE_STORY);
        stubAudit(expected);

        AdminActionResponse result =
                service.removeStory(
                        UUID.randomUUID(), storyId, new AdminActionRequest("Nudity", null));

        assertThat(result).isEqualTo(expected);
        verify(storyRepository).applyAdminModeration(eq(storyId), any(OffsetDateTime.class));
    }

    @Test
    void restoreStory_removedStory_clearsDeletedAtAndLeavesExpiryAlone() {
        UUID storyId = UUID.randomUUID();
        when(storyRepository.findOwnerIdIncludingDeleted(storyId))
                .thenReturn(Optional.of(UUID.randomUUID()));
        when(storyRepository.isAdminRemoved(storyId)).thenReturn(Optional.of(true));
        AdminActionResponse expected = response(AdminActionType.RESTORE_STORY);
        stubAudit(expected);

        AdminActionResponse result =
                service.restoreStory(
                        UUID.randomUUID(),
                        storyId,
                        new AdminActionRequest("Appeal accepted", null));

        assertThat(result).isEqualTo(expected);
        // Only deleted_at is written. Nothing here touches expires_at, which is what stops a
        // restore from resurrecting a story that expired while it was removed.
        verify(storyRepository).applyAdminModeration(storyId, null);
    }

    @Test
    void removeStory_alreadyRemovedStory_throwsInvalidTransition() {
        UUID storyId = UUID.randomUUID();
        when(storyRepository.findOwnerIdIncludingDeleted(storyId))
                .thenReturn(Optional.of(UUID.randomUUID()));
        when(storyRepository.isAdminRemoved(storyId)).thenReturn(Optional.of(true));

        assertThatThrownBy(
                        () ->
                                service.removeStory(
                                        UUID.randomUUID(),
                                        storyId,
                                        new AdminActionRequest("Nudity", null)))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.ADMIN_INVALID_TRANSITION);
    }

    @Test
    void removeStory_missingStory_throwsNotFound() {
        UUID storyId = UUID.randomUUID();
        when(storyRepository.findOwnerIdIncludingDeleted(storyId)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.removeStory(
                                        UUID.randomUUID(),
                                        storyId,
                                        new AdminActionRequest("Nudity", null)))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.STORY_NOT_FOUND);
    }

    @Test
    void removeMessage_liveMessage_setsTheAdminTombstoneAndAudits() {
        UUID messageId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        when(messageRepository.isAdminRemoved(messageId)).thenReturn(Optional.of(false));
        when(messageRepository.findSenderIdForModeration(messageId))
                .thenReturn(Optional.of(senderId));
        AdminActionResponse expected = response(AdminActionType.REMOVE_MESSAGE);
        stubAudit(expected);

        AdminActionResponse result =
                service.removeMessage(
                        UUID.randomUUID(), messageId, new AdminActionRequest("Harassment", null));

        assertThat(result).isEqualTo(expected);
        verify(messageRepository).applyAdminModeration(eq(messageId), any(OffsetDateTime.class));
    }

    @Test
    void restoreMessage_removedMessage_clearsOnlyTheAdminTombstone() {
        UUID messageId = UUID.randomUUID();
        when(messageRepository.isAdminRemoved(messageId)).thenReturn(Optional.of(true));
        when(messageRepository.findSenderIdForModeration(messageId))
                .thenReturn(Optional.of(UUID.randomUUID()));
        AdminActionResponse expected = response(AdminActionType.RESTORE_MESSAGE);
        stubAudit(expected);

        AdminActionResponse result =
                service.restoreMessage(
                        UUID.randomUUID(),
                        messageId,
                        new AdminActionRequest("Appeal accepted", null));

        assertThat(result).isEqualTo(expected);
        verify(messageRepository).applyAdminModeration(messageId, null);
    }

    @Test
    void restoreMessage_messageTheSenderDeleted_throwsInvalidTransition() {
        UUID messageId = UUID.randomUUID();
        // The sender's own deletion sets is_deleted, never admin_removed_at. A restore must not
        // treat it as a moderation state, or it would put back a message the sender destroyed.
        when(messageRepository.isAdminRemoved(messageId)).thenReturn(Optional.of(false));

        assertThatThrownBy(
                        () ->
                                service.restoreMessage(
                                        UUID.randomUUID(),
                                        messageId,
                                        new AdminActionRequest("Appeal accepted", null)))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.ADMIN_INVALID_TRANSITION);
    }

    @Test
    void removeMessage_senderAccountDeleted_recordsTheActionWithNoTargetUser() {
        UUID messageId = UUID.randomUUID();
        when(messageRepository.isAdminRemoved(messageId)).thenReturn(Optional.of(false));
        // sender_id is nullable (V32); the scalar read comes back empty for such a row.
        when(messageRepository.findSenderIdForModeration(messageId)).thenReturn(Optional.empty());
        stubAudit(response(AdminActionType.REMOVE_MESSAGE));

        service.removeMessage(
                UUID.randomUUID(), messageId, new AdminActionRequest("Harassment", null));

        ArgumentCaptor<AdminAction> captor = ArgumentCaptor.forClass(AdminAction.class);
        verify(adminActionRepository).insert(captor.capture());
        assertThat(captor.getValue().getTargetUserId()).isNull();
        assertThat(captor.getValue().getTargetEntityType()).isEqualTo("message");
    }

    @Test
    void removeMessage_missingMessage_throwsNotFound() {
        UUID messageId = UUID.randomUUID();
        when(messageRepository.isAdminRemoved(messageId)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.removeMessage(
                                        UUID.randomUUID(),
                                        messageId,
                                        new AdminActionRequest("Harassment", null)))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.MESSAGE_NOT_FOUND);
    }

    @Test
    void removeComment_notifiesTheOwnerAndDeepLinksToTheAuditRow() {
        UUID commentId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(commentRepository.findOwnerIdIncludingDeleted(commentId))
                .thenReturn(Optional.of(ownerId));
        when(commentRepository.isAdminRemoved(commentId)).thenReturn(Optional.of(false));
        AdminActionResponse expected = response(AdminActionType.REMOVE_COMMENT);
        stubAudit(expected);

        service.removeComment(UUID.randomUUID(), commentId, new AdminActionRequest("Abuse", null));

        // entity_type is the audit row and not the comment: the in-product appeal endpoint opens
        // an appeal against an admin_actions id, so this is what makes the notification actionable.
        verify(notificationService)
                .create(
                        notice(
                                ownerId,
                                NotificationType.COMMENT_REMOVED,
                                "admin_action",
                                expected.id(),
                                null,
                                "Abuse"));
    }

    @Test
    void removeStory_notifiesTheOwnerAndDeepLinksToTheAuditRow() {
        UUID storyId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(storyRepository.findOwnerIdIncludingDeleted(storyId)).thenReturn(Optional.of(ownerId));
        when(storyRepository.isAdminRemoved(storyId)).thenReturn(Optional.of(false));
        AdminActionResponse expected = response(AdminActionType.REMOVE_STORY);
        stubAudit(expected);

        service.removeStory(UUID.randomUUID(), storyId, new AdminActionRequest("Nudity", null));

        verify(notificationService)
                .create(
                        notice(
                                ownerId,
                                NotificationType.STORY_REMOVED,
                                "admin_action",
                                expected.id(),
                                null,
                                "Nudity"));
    }

    @Test
    void removeMessage_notifiesTheSenderAndDeepLinksToTheAuditRow() {
        UUID messageId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        when(messageRepository.isAdminRemoved(messageId)).thenReturn(Optional.of(false));
        when(messageRepository.findSenderIdForModeration(messageId))
                .thenReturn(Optional.of(senderId));
        AdminActionResponse expected = response(AdminActionType.REMOVE_MESSAGE);
        stubAudit(expected);

        service.removeMessage(
                UUID.randomUUID(), messageId, new AdminActionRequest("Harassment", null));

        verify(notificationService)
                .create(
                        notice(
                                senderId,
                                NotificationType.MESSAGE_REMOVED,
                                "admin_action",
                                expected.id(),
                                null,
                                "Harassment"));
    }

    @Test
    void removeMessage_senderAccountDeleted_writesNoNotification() {
        UUID messageId = UUID.randomUUID();
        when(messageRepository.isAdminRemoved(messageId)).thenReturn(Optional.of(false));
        // sender_id is nullable (V32). There is nobody left to notify, and a null recipient would
        // violate the not-null constraint on notifications.recipient_id.
        when(messageRepository.findSenderIdForModeration(messageId)).thenReturn(Optional.empty());
        stubAudit(response(AdminActionType.REMOVE_MESSAGE));

        service.removeMessage(
                UUID.randomUUID(), messageId, new AdminActionRequest("Harassment", null));

        verify(notificationService, never()).create(any(NotificationDraft.class));
    }

    @Test
    void restoreComment_writesNoRemovalNotification() {
        UUID commentId = UUID.randomUUID();
        when(commentRepository.findOwnerIdIncludingDeleted(commentId))
                .thenReturn(Optional.of(UUID.randomUUID()));
        when(commentRepository.isAdminRemoved(commentId)).thenReturn(Optional.of(true));
        stubAudit(response(AdminActionType.RESTORE_COMMENT));

        service.restoreComment(
                UUID.randomUUID(), commentId, new AdminActionRequest("Appeal accepted", null));

        verify(notificationService, never()).create(any(NotificationDraft.class));
    }

    private static NotificationDraft notice(
            UUID recipientId,
            NotificationType type,
            String entityType,
            UUID entityId,
            UUID postId,
            String message) {
        return argThat(
                draft ->
                        draft.actorId() == null
                                && recipientId.equals(draft.recipientId())
                                && draft.type() == type
                                && Objects.equals(entityType, draft.entityType())
                                && Objects.equals(entityId, draft.entityId())
                                && Objects.equals(postId, draft.postId())
                                && Objects.equals(message, draft.message())
                                && draft.adminActionId() != null);
    }

    private void stubAudit(AdminActionResponse response) {
        when(adminActionRepository.insert(any(AdminAction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(adminActionMapper.toResponse(any(AdminAction.class))).thenReturn(response);
    }

    private static AdminAction action(AdminActionType actionType) {
        return AdminAction.builder()
                .id(UUID.randomUUID())
                .actionType(actionType)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    private static AdminActionResponse response(AdminActionType actionType) {
        return new AdminActionResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                actionType,
                UUID.randomUUID(),
                "user",
                UUID.randomUUID(),
                null,
                "Reason",
                Map.of(),
                OffsetDateTime.now());
    }

    private static AdminActionSummaryResponse summary(AdminActionType actionType) {
        return new AdminActionSummaryResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                actionType,
                UUID.randomUUID(),
                "user",
                UUID.randomUUID(),
                null,
                "Reason",
                OffsetDateTime.now());
    }
}
