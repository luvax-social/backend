package com.app.modules.support.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.modules.support.entity.SupportTicket;
import com.app.modules.support.enums.SupportCategory;
import com.app.modules.support.service.SupportAuthorizationService.Outcome;
import com.app.modules.support.service.SupportAuthorizationService.StaffAction;
import com.app.modules.users.enums.UserRole;

class SupportAuthorizationServiceImplTest {

    private static final UUID MODERATOR = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID OTHER_STAFF = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    private final SupportAuthorizationServiceImpl policy = new SupportAuthorizationServiceImpl();

    // A moderator may read every appeal. Only the decision paths are narrowed.
    @ParameterizedTest
    @EnumSource(
            value = SupportCategory.class,
            names = {
                "APPEAL_BAN",
                "APPEAL_SUSPENSION",
                "APPEAL_WARNING_STRIKE",
                "APPEAL_CONTENT_REMOVAL"
            })
    void evaluate_moderatorReadingAnAppeal_isAllowed(SupportCategory category) {
        SupportTicket ticket = ticket(category, null);

        assertThat(policy.evaluate(MODERATOR, UserRole.MODERATOR, ticket, StaffAction.READ, null))
                .isEqualTo(Outcome.ALLOWED);
    }

    // The rule that matters. Unban, unsuspend, revoke_warning and revoke_strike are all
    // administrator-only, so a moderator closing an appeal would be recording a verdict they cannot
    // execute.
    @ParameterizedTest
    @EnumSource(
            value = SupportCategory.class,
            names = {
                "APPEAL_BAN",
                "APPEAL_SUSPENSION",
                "APPEAL_WARNING_STRIKE",
                "APPEAL_CONTENT_REMOVAL"
            })
    void evaluate_moderatorRespondingToAnAppeal_requiresAdmin(SupportCategory category) {
        SupportTicket ticket = ticket(category, MODERATOR);

        assertThat(
                        policy.evaluate(
                                MODERATOR, UserRole.MODERATOR, ticket, StaffAction.RESPOND, null))
                .isEqualTo(Outcome.APPEAL_REQUIRES_ADMIN);
    }

    @ParameterizedTest
    @EnumSource(
            value = SupportCategory.class,
            names = {
                "APPEAL_BAN",
                "APPEAL_SUSPENSION",
                "APPEAL_WARNING_STRIKE",
                "APPEAL_CONTENT_REMOVAL"
            })
    void evaluate_moderatorRejectingAnAppeal_requiresAdmin(SupportCategory category) {
        SupportTicket ticket = ticket(category, MODERATOR);

        assertThat(policy.evaluate(MODERATOR, UserRole.MODERATOR, ticket, StaffAction.REJECT, null))
                .isEqualTo(Outcome.APPEAL_REQUIRES_ADMIN);
    }

    // Escalating is how an appeal reaches an administrator, so a moderator must be able to do it.
    @Test
    void evaluate_moderatorEscalatingAnAppeal_isAllowed() {
        SupportTicket ticket = ticket(SupportCategory.APPEAL_BAN, MODERATOR);

        assertThat(
                        policy.evaluate(
                                MODERATOR, UserRole.MODERATOR, ticket, StaffAction.ESCALATE, null))
                .isEqualTo(Outcome.ALLOWED);
    }

    @Test
    void evaluate_moderatorRespondingToANonAppeal_isAllowed() {
        SupportTicket ticket = ticket(SupportCategory.BUG_REPORT, MODERATOR);

        assertThat(
                        policy.evaluate(
                                MODERATOR, UserRole.MODERATOR, ticket, StaffAction.RESPOND, null))
                .isEqualTo(Outcome.ALLOWED);
    }

    @Test
    void evaluate_adminRespondingToAnAppeal_isAllowed() {
        SupportTicket ticket = ticket(SupportCategory.APPEAL_BAN, ADMIN);

        assertThat(policy.evaluate(ADMIN, UserRole.ADMIN, ticket, StaffAction.RESPOND, null))
                .isEqualTo(Outcome.ALLOWED);
    }

    @Test
    void evaluate_ordinaryUser_isNotStaff() {
        SupportTicket ticket = ticket(SupportCategory.BUG_REPORT, null);

        assertThat(policy.evaluate(OWNER, UserRole.USER, ticket, StaffAction.READ, null))
                .isEqualTo(Outcome.ACTOR_NOT_STAFF);
    }

    // A staff member may not act on a ticket appealing a decision they wrote, whatever their role.
    @ParameterizedTest
    @EnumSource(
            value = StaffAction.class,
            names = {"CLAIM", "RESPOND", "REJECT", "ESCALATE"})
    void evaluate_actorWroteTheDecisionBeingAppealed_isConflictOfInterest(StaffAction action) {
        SupportTicket ticket = ticket(SupportCategory.APPEAL_BAN, ADMIN);

        assertThat(policy.evaluate(ADMIN, UserRole.ADMIN, ticket, action, ADMIN))
                .isEqualTo(Outcome.CONFLICT_OF_INTEREST);
    }

    // Reading is not a decision, so the conflict does not hide the ticket from its author.
    @Test
    void evaluate_actorWroteTheDecision_mayStillRead() {
        SupportTicket ticket = ticket(SupportCategory.APPEAL_BAN, null);

        assertThat(policy.evaluate(ADMIN, UserRole.ADMIN, ticket, StaffAction.READ, ADMIN))
                .isEqualTo(Outcome.ALLOWED);
    }

    @Test
    void evaluate_anotherStaffMemberWroteTheDecision_isAllowed() {
        SupportTicket ticket = ticket(SupportCategory.APPEAL_BAN, ADMIN);

        assertThat(policy.evaluate(ADMIN, UserRole.ADMIN, ticket, StaffAction.RESPOND, OTHER_STAFF))
                .isEqualTo(Outcome.ALLOWED);
    }

    // admin_actions.admin_id is null for the automatic strike the discipline ladder writes and for
    // the automatic unsuspend the expiry sweep writes. A null actor blocks nobody.
    @Test
    void evaluate_automaticDecisionWithNoAuthor_blocksNobody() {
        SupportTicket ticket = ticket(SupportCategory.APPEAL_WARNING_STRIKE, ADMIN);

        assertThat(policy.evaluate(ADMIN, UserRole.ADMIN, ticket, StaffAction.RESPOND, null))
                .isEqualTo(Outcome.ALLOWED);
    }

    @Test
    void evaluate_respondingWithoutHoldingTheClaim_isRefused() {
        SupportTicket ticket = ticket(SupportCategory.BUG_REPORT, OTHER_STAFF);

        assertThat(
                        policy.evaluate(
                                MODERATOR, UserRole.MODERATOR, ticket, StaffAction.RESPOND, null))
                .isEqualTo(Outcome.NOT_CLAIMED_BY_ACTOR);
    }

    @Test
    void evaluate_respondingToAnUnclaimedTicket_isRefused() {
        SupportTicket ticket = ticket(SupportCategory.BUG_REPORT, null);

        assertThat(
                        policy.evaluate(
                                MODERATOR, UserRole.MODERATOR, ticket, StaffAction.RESPOND, null))
                .isEqualTo(Outcome.NOT_CLAIMED_BY_ACTOR);
    }

    // Claiming establishes ownership, so it cannot itself require it.
    @Test
    void evaluate_claimingAnUnclaimedTicket_isAllowed() {
        SupportTicket ticket = ticket(SupportCategory.BUG_REPORT, null);

        assertThat(policy.evaluate(MODERATOR, UserRole.MODERATOR, ticket, StaffAction.CLAIM, null))
                .isEqualTo(Outcome.ALLOWED);
    }

    @Test
    void assertMayAct_moderatorOnAnAppeal_throwsAppealRequiresAdmin() {
        SupportTicket ticket = ticket(SupportCategory.APPEAL_BAN, MODERATOR);

        AppException thrown =
                catchThrowableOfType(
                        () ->
                                policy.assertMayAct(
                                        MODERATOR,
                                        UserRole.MODERATOR,
                                        ticket,
                                        StaffAction.RESPOND,
                                        null),
                        AppException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ApiErrorCode.SUPPORT_APPEAL_REQUIRES_ADMIN);
    }

    @Test
    void assertMayAct_conflictOfInterest_throwsDistinctCode() {
        SupportTicket ticket = ticket(SupportCategory.APPEAL_BAN, ADMIN);

        AppException thrown =
                catchThrowableOfType(
                        () ->
                                policy.assertMayAct(
                                        ADMIN, UserRole.ADMIN, ticket, StaffAction.RESPOND, ADMIN),
                        AppException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ApiErrorCode.SUPPORT_CONFLICT_OF_INTEREST);
    }

    @Test
    void assertIsOwner_theOwner_passes() {
        SupportTicket ticket = ticket(SupportCategory.BUG_REPORT, null);
        ticket.setUserId(OWNER);

        assertThatCode(() -> policy.assertIsOwner(OWNER, ticket)).doesNotThrowAnyException();
    }

    // Not-found rather than forbidden: answering 403 would confirm that a ticket with that
    // identifier exists, which is a fact about somebody else's account.
    @Test
    void assertIsOwner_someoneElse_throwsNotFound() {
        SupportTicket ticket = ticket(SupportCategory.BUG_REPORT, null);
        ticket.setUserId(OWNER);

        AppException thrown =
                catchThrowableOfType(
                        () -> policy.assertIsOwner(UUID.randomUUID(), ticket), AppException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ApiErrorCode.SUPPORT_TICKET_NOT_FOUND);
    }

    @Test
    void assertIsOwner_publicTicketWithNoOwner_throwsNotFound() {
        SupportTicket ticket = ticket(SupportCategory.BUG_REPORT, null);

        AppException thrown =
                catchThrowableOfType(() -> policy.assertIsOwner(OWNER, ticket), AppException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ApiErrorCode.SUPPORT_TICKET_NOT_FOUND);
    }

    private static SupportTicket ticket(SupportCategory category, UUID assignedTo) {
        SupportTicket ticket = new SupportTicket();
        ticket.setCategory(category);
        ticket.setAssignedTo(assignedTo);
        return ticket;
    }
}
