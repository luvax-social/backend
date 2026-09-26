package com.app.modules.support.service;

import java.util.UUID;

import com.app.common.exception.AppException;
import com.app.modules.support.entity.SupportTicket;
import com.app.modules.users.enums.UserRole;

/**
 * Every actor-and-ticket rule for the support centre, in one place.
 *
 * <p>Shaped on {@code AdminAuthorizationService}: a pure evaluator returning an outcome, and assert
 * methods that map an outcome to an {@code ApiErrorCode}. The controller annotations are the first
 * gate and this is the second; a service method reachable from a consumer or a scheduled job must
 * not depend on a caller having passed through a controller.
 *
 * <p>Two rules carry most of the weight here.
 *
 * <p>The first is that a moderator may read an appeal and may escalate it, but may not answer or
 * close one. Unban, unsuspend, revoke-warning and revoke-strike are all administrator-only actions,
 * so a moderator who could close an appeal would be recording a verdict they have no capability to
 * execute. The refusal is a distinct code rather than a plain 403 so a client can say why.
 *
 * <p>The second is conflict of interest. A staff member may not claim, respond to, close or
 * escalate a ticket appealing an audit row they wrote. The acting staff member is resolved from
 * {@code admin_actions.admin_id}, which is nullable - it is null for the automatic strike the
 * discipline ladder writes and for the automatic unsuspend the expiry sweep writes - so a null
 * actor blocks nobody.
 */
public interface SupportAuthorizationService {

    /** Outcome of evaluating one requested staff action against one ticket, without throwing. */
    enum Outcome {
        ALLOWED,
        ACTOR_NOT_STAFF,
        APPEAL_REQUIRES_ADMIN,
        CONFLICT_OF_INTEREST,
        NOT_CLAIMED_BY_ACTOR
    }

    /** What a staff member intends to do with a ticket. */
    enum StaffAction {
        READ,
        CLAIM,
        RESPOND,
        REJECT,
        ESCALATE
    }

    /**
     * Classifies a requested staff action without throwing.
     *
     * @param actorId the staff member acting
     * @param actorRole the actor's role as read from the source of truth, never from a token claim
     * @param ticket the ticket being acted on
     * @param action what the actor intends to do
     * @param decisionAuthorId the author of the audit row the ticket appeals, or null when the
     *     ticket appeals nothing or the audit row records an automatic action
     * @return the outcome; {@link Outcome#ALLOWED} when the action is permitted
     */
    Outcome evaluate(
            UUID actorId,
            UserRole actorRole,
            SupportTicket ticket,
            StaffAction action,
            UUID decisionAuthorId);

    /**
     * Asserts that a staff member may perform one action on one ticket.
     *
     * @param actorId the staff member acting
     * @param actorRole the actor's role as read from the source of truth
     * @param ticket the ticket being acted on
     * @param action what the actor intends to do
     * @param decisionAuthorId the author of the appealed audit row, or null
     * @throws AppException {@code FORBIDDEN} when the actor is not staff, {@code
     *     SUPPORT_APPEAL_REQUIRES_ADMIN} when a moderator attempts a decision on an appeal, {@code
     *     SUPPORT_CONFLICT_OF_INTEREST} when the actor wrote the decision being appealed, and
     *     {@code SUPPORT_TICKET_NOT_CLAIMED} when the actor holds no claim on the ticket
     */
    void assertMayAct(
            UUID actorId,
            UserRole actorRole,
            SupportTicket ticket,
            StaffAction action,
            UUID decisionAuthorId);

    /**
     * Asserts that the caller may read one ticket as its owner.
     *
     * @param viewerId the requesting account
     * @param ticket the ticket
     * @throws AppException {@code SUPPORT_TICKET_NOT_FOUND} when the ticket belongs to somebody
     *     else; not-found rather than forbidden, so the endpoint does not confirm that a ticket
     *     exists for an account the caller does not own
     */
    void assertIsOwner(UUID viewerId, SupportTicket ticket);
}
