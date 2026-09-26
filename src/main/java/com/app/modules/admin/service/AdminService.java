package com.app.modules.admin.service;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.app.common.exception.AppException;
import com.app.common.response.CursorPageResponse;
import com.app.modules.admin.dto.request.AdminActionRequest;
import com.app.modules.admin.dto.request.AdminEscalateReportRequest;
import com.app.modules.admin.dto.request.AdminSuspendUserRequest;
import com.app.modules.admin.dto.response.AdminActionResponse;
import com.app.modules.admin.dto.response.AdminActionSummaryResponse;
import com.app.modules.admin.dto.response.AdminPostRestoreResponse;
import com.app.modules.admin.dto.response.EscalatedReportCountResponse;
import com.app.modules.admin.enums.AdminActionType;

public interface AdminService {

    /** Bans a user and records the action atomically. */
    AdminActionResponse banUser(UUID actorId, UUID userId, AdminActionRequest request);

    /** Unbans a banned user and records the action atomically. */
    AdminActionResponse unbanUser(UUID actorId, UUID userId, AdminActionRequest request);

    /**
     * Suspends an active user and records the action atomically.
     *
     * <p>A request carrying {@code durationDays} stores the moment the suspension lapses, after
     * which the first authentication attempt or the reinstatement sweep returns the account to
     * active. A request without one is indefinite and stores no deadline, so nothing ever
     * reinstates it automatically.
     */
    AdminActionResponse suspendUser(UUID actorId, UUID userId, AdminSuspendUserRequest request);

    /**
     * Unsuspends a suspended user and records the action atomically.
     *
     * <p>Clears the suspension deadline in the same transaction, so the reinstatement sweep can
     * never fire on a row an administrator has already handled.
     */
    AdminActionResponse unsuspendUser(UUID actorId, UUID userId, AdminActionRequest request);

    /** Removes a post and records the action atomically. */
    AdminActionResponse removePost(UUID actorId, UUID postId, AdminActionRequest request);

    /**
     * Restores a removed post and records the action atomically.
     *
     * <p>Returns the names of any hashtags the caption still carries that were not re-associated
     * because an administrator has banned them. Restore is the one write path that strips rather
     * than refuses, so it is also the one that can silently give a post back with fewer tags than
     * its caption names.
     *
     * @param actorId the acting moderator or administrator
     * @param postId the post to restore
     * @param request the audit reason and any linked report
     * @return the audit row and the hashtag names the restore dropped
     */
    AdminPostRestoreResponse restorePost(UUID actorId, UUID postId, AdminActionRequest request);

    /** Removes a comment and records the action atomically. */
    AdminActionResponse removeComment(UUID actorId, UUID commentId, AdminActionRequest request);

    /** Restores a removed comment and records the action atomically. */
    AdminActionResponse restoreComment(UUID actorId, UUID commentId, AdminActionRequest request);

    /** Removes a story and records the action atomically. */
    AdminActionResponse removeStory(UUID actorId, UUID storyId, AdminActionRequest request);

    /**
     * Restores a removed story and records the action atomically.
     *
     * <p>Clears the removal only. Expiry continues to decide visibility, so a story that expired
     * while it was removed comes back to a live row that no feed will show. A story that the
     * cleanup job has already hard-deleted, which it does once a row is both removed and expired,
     * cannot be restored at all and answers not-found.
     *
     * @param actorId the acting moderator or administrator
     * @param storyId the story to restore
     * @param request the audit reason and any linked report
     * @return the audit row
     */
    AdminActionResponse restoreStory(UUID actorId, UUID storyId, AdminActionRequest request);

    /**
     * Removes a message and records the action atomically.
     *
     * <p>Sets the administrative tombstone, which withholds the message's text, media and shares
     * from both participants and leaves the "message deleted" placeholder the module already shows
     * for a sender's own deletion. The row keeps its payload so a restore can return it.
     *
     * @param actorId the acting moderator or administrator
     * @param messageId the message to remove
     * @param request the audit reason and any linked report
     * @return the audit row
     */
    AdminActionResponse removeMessage(UUID actorId, UUID messageId, AdminActionRequest request);

    /**
     * Restores a removed message and records the action atomically.
     *
     * <p>Clears the administrative tombstone only. A message the sender had also deleted stays
     * deleted, because a restore corrects a moderation decision and not the sender's.
     *
     * @param actorId the acting moderator or administrator
     * @param messageId the message to restore
     * @param request the audit reason and any linked report
     * @return the audit row
     */
    AdminActionResponse restoreMessage(UUID actorId, UUID messageId, AdminActionRequest request);

    /**
     * Resolves an open report and records the action atomically.
     *
     * <p>An escalated report may be resolved only by an administrator. A moderator escalated it
     * precisely because it did not want to decide, so letting any moderator close it again would
     * make the escalation an empty gesture.
     */
    AdminActionResponse resolveReport(UUID actorId, UUID reportId, AdminActionRequest request);

    /**
     * Dismisses an open report and records the action atomically.
     *
     * <p>Subject to the same administrator-only rule as resolution for an escalated report.
     */
    AdminActionResponse dismissReport(UUID actorId, UUID reportId, AdminActionRequest request);

    /**
     * Hands a report up to an administrator and records the action atomically.
     *
     * <p>Moves the report out of the moderator queue while leaving it readable by the moderator
     * that escalated it. There is no transition back: a report that could fall into the queue it
     * just left would defeat the point of escalating it.
     *
     * @param actorId moderator or administrator escalating the report
     * @param reportId report to escalate
     * @param request why the decision is being handed up
     * @return the audit row written
     * @throws AppException with {@code REPORT_NOT_FOUND} when no row holds that id, or {@code
     *     REPORT_INVALID_TRANSITION} when the report is already closed or already escalated
     */
    AdminActionResponse escalateReport(
            UUID actorId, UUID reportId, AdminEscalateReportRequest request);

    /**
     * Counts the reports waiting on an administrator.
     *
     * <p>Escalation pushes no notification by design, so this count is the only signal that one is
     * waiting.
     *
     * @return the count
     */
    EscalatedReportCountResponse countEscalatedReports(UUID actorId);

    /**
     * Lists audit-event summaries with optional actor and action-type filters.
     *
     * <p>A moderator sees only rows it authored, whatever {@code adminId} filter it supplies. An
     * administrator sees every row. The restriction is applied here rather than at the web layer so
     * it holds for any caller of this method.
     *
     * @param actorId the requesting account, resolved from the security context
     * @param adminId actor filter requested by the caller; ignored for a moderator
     * @param actionType action-type filter, or null for every type
     * @param targetUserId account the action was taken against, or null for every target
     * @param from inclusive lower bound on when the action was recorded, or null for unbounded
     * @param to exclusive upper bound on when the action was recorded, or null for unbounded
     * @param cursor opaque keyset cursor, or null for the first page
     * @param size requested page size
     * @return one cursor page of audit summaries visible to this actor
     * @throws com.app.common.exception.AppException {@code BAD_REQUEST} when both bounds are given
     *     and {@code to} is not after {@code from}
     */
    CursorPageResponse<AdminActionSummaryResponse> getActions(
            UUID actorId,
            UUID adminId,
            AdminActionType actionType,
            UUID targetUserId,
            OffsetDateTime from,
            OffsetDateTime to,
            String cursor,
            int size);

    /**
     * Returns one immutable audit event.
     *
     * <p>A moderator may read only a row it authored. A row authored by someone else is reported as
     * absent rather than forbidden, so the endpoint does not confirm that an audit row it may not
     * read exists.
     *
     * @param actorId the requesting account, resolved from the security context
     * @param actionId the audit event to read
     * @return the audit event
     * @throws AppException when the audit event does not exist or is not visible to this actor
     */
    AdminActionResponse getActionById(UUID actorId, UUID actionId);

    /**
     * Lists audit-event summaries for one affected user.
     *
     * <p>A moderator sees only rows it authored against that user; an administrator sees every row.
     *
     * @param actorId the requesting account, resolved from the security context
     * @param userId the affected account
     * @param cursor opaque keyset cursor, or null for the first page
     * @param size requested page size
     * @return one cursor page of audit summaries visible to this actor
     */
    CursorPageResponse<AdminActionSummaryResponse> getActionsForUser(
            UUID actorId, UUID userId, String cursor, int size);
}
