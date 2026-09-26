package com.app.modules.support.service.impl;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.modules.support.entity.SupportTicket;
import com.app.modules.support.service.SupportAuthorizationService;
import com.app.modules.users.enums.UserRole;

@Service
public class SupportAuthorizationServiceImpl implements SupportAuthorizationService {

    @Override
    public Outcome evaluate(
            UUID actorId,
            UserRole actorRole,
            SupportTicket ticket,
            StaffAction action,
            UUID decisionAuthorId) {
        if (actorRole != UserRole.MODERATOR && actorRole != UserRole.ADMIN) {
            return Outcome.ACTOR_NOT_STAFF;
        }
        // Reading is the widest capability and is checked first, so a moderator browsing the queue
        // is never refused for a reason that only applies to deciding.
        if (action == StaffAction.READ) {
            return Outcome.ALLOWED;
        }
        // Ordered so the most specific applicable reason wins. Conflict of interest outranks the
        // appeal rule: an administrator who wrote the decision is refused for the conflict, which
        // is
        // the accurate reason, rather than being allowed through because their role is sufficient.
        if (decisionAuthorId != null && decisionAuthorId.equals(actorId)) {
            return Outcome.CONFLICT_OF_INTEREST;
        }
        // A moderator may escalate an appeal - that is how one reaches an administrator - but may
        // not decide it.
        boolean decidesAppeal =
                ticket.getCategory().isAppeal()
                        && (action == StaffAction.RESPOND || action == StaffAction.REJECT);
        if (decidesAppeal && actorRole != UserRole.ADMIN) {
            return Outcome.APPEAL_REQUIRES_ADMIN;
        }
        // Claiming is what establishes ownership, so it cannot itself require ownership.
        if (action == StaffAction.CLAIM) {
            return Outcome.ALLOWED;
        }
        // Deciding or escalating requires holding the claim. Without this an unclaimed ticket could
        // be answered by anyone who saw it, which is the ownership gap escalated reports have
        // today.
        if (ticket.getAssignedTo() == null || !ticket.getAssignedTo().equals(actorId)) {
            return Outcome.NOT_CLAIMED_BY_ACTOR;
        }
        return Outcome.ALLOWED;
    }

    @Override
    public void assertMayAct(
            UUID actorId,
            UserRole actorRole,
            SupportTicket ticket,
            StaffAction action,
            UUID decisionAuthorId) {
        switch (evaluate(actorId, actorRole, ticket, action, decisionAuthorId)) {
            case ALLOWED -> {}
            case ACTOR_NOT_STAFF -> throw new AppException(ApiErrorCode.FORBIDDEN);
            case APPEAL_REQUIRES_ADMIN ->
                    throw new AppException(ApiErrorCode.SUPPORT_APPEAL_REQUIRES_ADMIN);
            case CONFLICT_OF_INTEREST ->
                    throw new AppException(ApiErrorCode.SUPPORT_CONFLICT_OF_INTEREST);
            case NOT_CLAIMED_BY_ACTOR ->
                    throw new AppException(ApiErrorCode.SUPPORT_TICKET_NOT_CLAIMED);
        }
    }

    @Override
    public void assertIsOwner(UUID viewerId, SupportTicket ticket) {
        // Not-found rather than forbidden on purpose: answering 403 here would confirm that a
        // ticket
        // with that identifier exists, which is a fact about somebody else's account.
        if (ticket.getUserId() == null || !ticket.getUserId().equals(viewerId)) {
            throw new AppException(ApiErrorCode.SUPPORT_TICKET_NOT_FOUND);
        }
    }
}
