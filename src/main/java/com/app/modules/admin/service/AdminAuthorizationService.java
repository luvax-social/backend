package com.app.modules.admin.service;

import java.util.List;
import java.util.UUID;

import com.app.common.exception.AppException;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserRole;

/**
 * Every actor-and-target rule for an administrative action against an account, in one place.
 *
 * <p>The endpoint annotations and path matchers are the first gate; this is the second. A service
 * method reachable from a consumer, a scheduled job, or another service must not depend on a caller
 * having passed through the controller.
 *
 * <p>Status changes and role changes share the same first three rules: only an administrator may
 * act, nobody may act on their own account, and an administrator is never a valid target. They were
 * written separately and drifting apart was a matter of time, since a rule added to one place would
 * not have been visible from the other. They are evaluated once here and each caller maps the
 * outcome to the error code its own contract publishes.
 *
 * <p>An administrator being an invalid target makes removing a rogue administrator deliberately a
 * database-level operation. An in-application route to lock out the whole administrator tier has no
 * recovery path, because a banned account cannot authenticate to undo it; a database escalation
 * does have one.
 */
public interface AdminAuthorizationService {

    /** Outcome of evaluating one requested action, without throwing. */
    enum Outcome {
        ALLOWED,
        ACTOR_NOT_ADMIN,
        SELF_TARGET,
        TARGET_IS_ADMIN,
        SKIP_LEVEL,
        NO_OP
    }

    /**
     * The operations one actor may perform against one target, without performing any of them.
     *
     * <p>Derived from the same evaluation the assertions use, never restated. Two copies of an
     * authorization rule is the debt this interface exists to remove, and a copy in a mapper is the
     * same debt written somewhere harder to find.
     *
     * @param canChangeStatus whether ban, unban, suspend and unsuspend are permitted; they share
     *     one rule, so one flag answers all four
     * @param canChangeRole whether any role transition at all is permitted
     * @param assignableRoles the roles this actor may move this target to, empty when none is
     */
    record Capabilities(
            boolean canChangeStatus, boolean canChangeRole, List<UserRole> assignableRoles) {}

    /**
     * Classifies an action that requires the administrator role and names no target account.
     *
     * <p>The reports, hashtag registry, statistics and activity-log surfaces act on the platform
     * rather than on one account, so the shared actor-and-target rules do not apply to them. Only
     * the first of those rules does: the actor must be an administrator.
     *
     * @param actorRole the actor's role as read from the source of truth, never from a token claim
     * @return {@link Outcome#ALLOWED} for an administrator, {@link Outcome#ACTOR_NOT_ADMIN}
     *     otherwise
     */
    Outcome evaluateAdministratorAction(UserRole actorRole);

    /**
     * Asserts that the actor holds the administrator role, resolving that role from the source of
     * truth.
     *
     * <p>This is the second gate behind the {@code @PreAuthorize} annotations on the administrator
     * surface. Those annotations are the only gate the eleven administrator-only endpoints had, and
     * the {@code SecurityConfig} matcher covering their paths is the broad {@code /api/v1/admin/**}
     * rule, which admits a moderator. Deleting or mistyping one annotation therefore opened the
     * endpoint with nothing else to catch it.
     *
     * <p>The role lookup lives here rather than at each call site because the same four-line read
     * was already repeated across this module, and a rule that is copied is a rule that drifts.
     *
     * @param actorId the account performing the action
     * @throws AppException {@code FORBIDDEN} when the actor is not an administrator, or when no
     *     live account exists for {@code actorId}
     */
    void assertActorIsAdministrator(UUID actorId);

    /**
     * Classifies a requested status change without throwing.
     *
     * @param actorId the account performing the action
     * @param actorRole the actor's role as read from the source of truth, never from a token claim
     * @param targetId the account whose status would change
     * @param targetRole the target's current role
     * @return the outcome; {@link Outcome#ALLOWED} when the actor may change that target's status
     */
    Outcome evaluateStatusChange(
            UUID actorId, UserRole actorRole, UUID targetId, UserRole targetRole);

    /**
     * Reports what this actor may do to this target, so a client can decide whether to render a
     * control rather than discover the answer from a rejection after the user has clicked it.
     *
     * @param actorId the account that would perform the action
     * @param actorRole the actor's role as read from the source of truth
     * @param targetId the account that would be acted on
     * @param targetRole the target's current role
     * @return the permitted operations, agreeing exactly with what the assertions allow
     */
    Capabilities capabilitiesFor(
            UUID actorId, UserRole actorRole, UUID targetId, UserRole targetRole);

    /**
     * Asserts that the actor may change the target account's status.
     *
     * @param actorId the account performing the action
     * @param actorRole the role resolved for {@code actorId} from the source of truth, never the
     *     role claim carried on a token
     * @param target the target account, already loaded inside the caller's transaction
     * @throws AppException {@code FORBIDDEN} when the actor is not an administrator, {@code
     *     ADMIN_SELF_ACTION_NOT_ALLOWED} when actor and target are the same account, and {@code
     *     ADMIN_TARGET_PROTECTED} when the target is an administrator
     */
    void assertMayChangeUserStatus(UUID actorId, UserRole actorRole, User target);

    /**
     * Classifies a verification decision without throwing.
     *
     * <p>The same actor-and-target skeleton the status rules use, with one difference: a moderator
     * is admitted alongside an administrator. Verification is a discretionary grant rather than an
     * enforcement action, so the narrowing that keeps unban and unsuspend administrator-only does
     * not apply to it; a moderator granting a badge is not recording a verdict they cannot execute.
     *
     * <p>The other two guards do apply and are the reason this routes here rather than checking a
     * role inline. Granting yourself a badge and revoking an administrator's are exactly the abuses
     * {@code SELF_TARGET} and {@code TARGET_IS_ADMIN} exist to stop.
     *
     * @param actorId the staff member deciding
     * @param actorRole the actor's role as read from the source of truth, never from a token claim
     * @param targetId the account whose badge would change
     * @param targetRole the target's current role
     * @return the outcome; {@link Outcome#ALLOWED} when the actor may decide
     */
    Outcome evaluateVerificationDecision(
            UUID actorId, UserRole actorRole, UUID targetId, UserRole targetRole);

    /**
     * Asserts that the actor may grant, reject or revoke verification for the target account.
     *
     * @param actorId the staff member deciding
     * @param actorRole the role resolved for {@code actorId} from the source of truth
     * @param target the target account, already loaded inside the caller's transaction
     * @throws AppException {@code FORBIDDEN} when the actor is neither moderator nor administrator,
     *     {@code ADMIN_SELF_ACTION_NOT_ALLOWED} when actor and target are the same account, and
     *     {@code ADMIN_TARGET_PROTECTED} when the target is an administrator
     */
    void assertMayDecideVerification(UUID actorId, UserRole actorRole, User target);

    /**
     * Classifies a requested role transition without throwing.
     *
     * <p>Permitted, and nothing else:
     *
     * <ul>
     *   <li>{@code user -> moderator}
     *   <li>{@code moderator -> user}
     *   <li>{@code moderator -> admin}
     * </ul>
     *
     * <p>Checks are ordered so the most specific applicable reason is the one reported: actor
     * authority first, then self-targeting, then the protected target, then the transition itself.
     * A skip-level {@code user -> admin} promotion is refused so granting the highest privilege
     * always passes through the moderator tier. A request naming the role the account already holds
     * is refused rather than silently succeeding, so a successful response always means something
     * changed.
     *
     * @param actorId the account performing the change
     * @param actorRole the actor's role as read from the source of truth, never from a token claim
     * @param targetId the account whose role would change
     * @param targetRole the target's current role
     * @param requestedRole the role the caller asked for
     * @return the outcome; {@link Outcome#ALLOWED} only for a transition in the permitted set
     */
    Outcome evaluateRoleTransition(
            UUID actorId,
            UserRole actorRole,
            UUID targetId,
            UserRole targetRole,
            UserRole requestedRole);

    /**
     * Asserts that a requested role transition is permitted.
     *
     * @param actorId the account performing the change
     * @param actorRole the actor's role as read from the source of truth
     * @param targetId the account whose role would change
     * @param targetRole the target's current role
     * @param requestedRole the role the caller asked for
     * @throws AppException {@code FORBIDDEN} when the actor is not an administrator, {@code
     *     ADMIN_SELF_ACTION_NOT_ALLOWED} when the actor targets itself, {@code
     *     ADMIN_TARGET_PROTECTED} when the target is an administrator, and {@code
     *     ADMIN_ROLE_TRANSITION_NOT_ALLOWED} for a skip-level promotion or a no-op
     */
    void assertMayChangeUserRole(
            UUID actorId,
            UserRole actorRole,
            UUID targetId,
            UserRole targetRole,
            UserRole requestedRole);
}
