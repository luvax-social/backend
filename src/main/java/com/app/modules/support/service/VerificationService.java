package com.app.modules.support.service;

import java.util.List;
import java.util.UUID;

import com.app.common.exception.AppException;
import com.app.modules.support.dto.request.CreateVerificationRequest;
import com.app.modules.support.dto.request.VerificationDecisionRequest;
import com.app.modules.support.dto.response.VerificationCategoryResponse;
import com.app.modules.support.dto.response.VerificationQueueItemResponse;
import com.app.modules.support.dto.response.VerificationStateResponse;
import com.app.modules.support.enums.SupportTicketStatus;
import com.app.modules.users.enums.UserStatus;

/**
 * Verification requests, decisions, and the badge lifecycle.
 *
 * <p>A verification request is a support ticket of category {@code VERIFICATION_REQUEST} with a
 * structured child row beside it. Everything about the queue - claiming, the conflict-of-interest
 * rule, escalation, the audit trail and the outbox mail - comes from the support framework
 * unchanged. This service owns only what is specific to verification: the evidence rule, the grant,
 * and the coupling between account status and the badge.
 */
public interface VerificationService {

    /**
     * The categories a requester may choose from.
     *
     * @return enabled categories in display order, each carrying the glyph key the client maps
     */
    List<VerificationCategoryResponse> listCategories();

    /**
     * Submits a verification request for the calling account.
     *
     * <p>Refuses with {@code VERIFICATION_INSUFFICIENT_EVIDENCE} when fewer than three of the seven
     * evidence fields carry text. That rule is the contract; a client enforcing it first is a
     * convenience.
     *
     * @param userId the requesting account
     * @param request the category, the claimed name and the evidence fields
     * @return the account's verification state after the submission
     * @throws AppException {@code VERIFICATION_INSUFFICIENT_EVIDENCE} below three evidence fields,
     *     {@code VERIFICATION_CATEGORY_NOT_FOUND} for an unknown or disabled category, {@code
     *     VERIFICATION_ALREADY_VERIFIED} when the account already holds a badge, and {@code
     *     SUPPORT_TICKET_ALREADY_OPEN} when a verification request is already outstanding
     */
    VerificationStateResponse submit(UUID userId, CreateVerificationRequest request);

    /**
     * The calling account's own verification state.
     *
     * @param userId the account
     * @return the active badge, the outstanding request, the last decision, or none of them
     */
    VerificationStateResponse myState(UUID userId);

    /**
     * The moderator review queue.
     *
     * @param actorId the staff member reading
     * @param status status to match, or null for every staff-visible status
     * @param limit page size, bounded by the service
     * @return requests newest first, each carrying the account's previous grants
     */
    List<VerificationQueueItemResponse> queue(UUID actorId, SupportTicketStatus status, int limit);

    /**
     * One verification request in full.
     *
     * @param actorId the staff member reading
     * @param ticketId the ticket carrying the request
     * @return the request with the requester's grant history
     */
    VerificationQueueItemResponse getForStaff(UUID actorId, UUID ticketId);

    /**
     * Approves a request, granting the badge.
     *
     * <p>The grant, the ticket transition and the audit row commit together. The requester is
     * mailed through the outbox by {@code AdminActionRecorder}, on the same path every other
     * moderation notice takes.
     *
     * @param actorId the deciding staff member, moderator or administrator
     * @param ticketId the ticket carrying the request
     * @param decision the reason the requester is told, and an optional staff-only note
     * @return the decided request
     */
    VerificationQueueItemResponse approve(
            UUID actorId, UUID ticketId, VerificationDecisionRequest decision);

    /**
     * Rejects a request without granting anything.
     *
     * @param actorId the deciding staff member, moderator or administrator
     * @param ticketId the ticket carrying the request
     * @param decision the reason the requester is told, and an optional staff-only note
     * @return the decided request
     */
    VerificationQueueItemResponse reject(
            UUID actorId, UUID ticketId, VerificationDecisionRequest decision);

    /**
     * Withdraws an account's badge by a staff decision.
     *
     * <p>Soft: the grant row stays with its revocation recorded, so the next reviewer can see what
     * was granted and why it was taken back.
     *
     * @param actorId the deciding staff member
     * @param targetUserId the account losing the badge
     * @param decision the reason, which is required and reaches the account
     * @throws AppException {@code VERIFICATION_NOT_ACTIVE} when the account holds no badge
     */
    void revoke(UUID actorId, UUID targetUserId, VerificationDecisionRequest decision);

    /**
     * Applies the badge consequence of an account status change.
     *
     * <p>Called by the status-change paths rather than being scheduled, so the badge and the status
     * move in one transaction. Suspension and a ban withdraw the badge; deactivation retains it,
     * because deactivation is a voluntary act by the account holder, the account is invisible while
     * it lasts, and revoking there punishes something that is not an offence.
     *
     * <p>This never creates a support ticket. It is a status-driven side effect, not a request, and
     * the audit row it writes carries a null actor so the log distinguishes it from a decision a
     * moderator made.
     *
     * @param targetUserId the account whose status changed
     * @param newStatus the status it moved to
     * @return true when a badge was withdrawn
     */
    boolean applyStatusChange(UUID targetUserId, UserStatus newStatus);
}
