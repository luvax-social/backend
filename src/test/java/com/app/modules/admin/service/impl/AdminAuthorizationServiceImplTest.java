package com.app.modules.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.modules.admin.service.AdminAuthorizationService;
import com.app.modules.admin.service.AdminAuthorizationService.Outcome;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class AdminAuthorizationServiceImplTest {

    @Mock private UserRepository userRepository;

    private AdminAuthorizationServiceImpl policy;

    @BeforeEach
    void setUp() {
        policy = new AdminAuthorizationServiceImpl(userRepository);
    }

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();

    @Test
    void evaluateRoleTransition_userToModerator_isAllowed() {
        assertThat(
                        policy.evaluateRoleTransition(
                                ACTOR, UserRole.ADMIN, TARGET, UserRole.USER, UserRole.MODERATOR))
                .isEqualTo(Outcome.ALLOWED);
    }

    @Test
    void evaluateRoleTransition_moderatorToUser_isAllowed() {
        assertThat(
                        policy.evaluateRoleTransition(
                                ACTOR, UserRole.ADMIN, TARGET, UserRole.MODERATOR, UserRole.USER))
                .isEqualTo(Outcome.ALLOWED);
    }

    @Test
    void evaluateRoleTransition_moderatorToAdmin_isAllowed() {
        assertThat(
                        policy.evaluateRoleTransition(
                                ACTOR, UserRole.ADMIN, TARGET, UserRole.MODERATOR, UserRole.ADMIN))
                .isEqualTo(Outcome.ALLOWED);
    }

    @Test
    void evaluateRoleTransition_userToAdmin_isSkipLevel() {
        assertThat(
                        policy.evaluateRoleTransition(
                                ACTOR, UserRole.ADMIN, TARGET, UserRole.USER, UserRole.ADMIN))
                .isEqualTo(Outcome.SKIP_LEVEL);
    }

    @Test
    void evaluateRoleTransition_adminTargetToModerator_isTargetIsAdmin() {
        assertThat(
                        policy.evaluateRoleTransition(
                                ACTOR, UserRole.ADMIN, TARGET, UserRole.ADMIN, UserRole.MODERATOR))
                .isEqualTo(Outcome.TARGET_IS_ADMIN);
    }

    @Test
    void evaluateRoleTransition_adminTargetToUser_isTargetIsAdmin() {
        assertThat(
                        policy.evaluateRoleTransition(
                                ACTOR, UserRole.ADMIN, TARGET, UserRole.ADMIN, UserRole.USER))
                .isEqualTo(Outcome.TARGET_IS_ADMIN);
    }

    @Test
    void evaluateRoleTransition_userToUser_isNoOp() {
        assertThat(
                        policy.evaluateRoleTransition(
                                ACTOR, UserRole.ADMIN, TARGET, UserRole.USER, UserRole.USER))
                .isEqualTo(Outcome.NO_OP);
    }

    @Test
    void evaluateRoleTransition_moderatorToModerator_isNoOp() {
        assertThat(
                        policy.evaluateRoleTransition(
                                ACTOR,
                                UserRole.ADMIN,
                                TARGET,
                                UserRole.MODERATOR,
                                UserRole.MODERATOR))
                .isEqualTo(Outcome.NO_OP);
    }

    // The protected-target rule is checked before the no-op rule, so an administrator asked to stay
    // an administrator reports the more specific reason rather than "nothing to do".
    @Test
    void evaluateRoleTransition_adminToAdmin_reportsProtectedTargetRatherThanNoOp() {
        assertThat(
                        policy.evaluateRoleTransition(
                                ACTOR, UserRole.ADMIN, TARGET, UserRole.ADMIN, UserRole.ADMIN))
                .isEqualTo(Outcome.TARGET_IS_ADMIN);
    }

    @Test
    void evaluateRoleTransition_actorTargetsItself_isSelfTarget() {
        assertThat(
                        policy.evaluateRoleTransition(
                                ACTOR, UserRole.ADMIN, ACTOR, UserRole.ADMIN, UserRole.MODERATOR))
                .isEqualTo(Outcome.SELF_TARGET);
    }

    @Test
    void evaluateRoleTransition_moderatorActor_isActorNotAdmin() {
        assertThat(
                        policy.evaluateRoleTransition(
                                ACTOR,
                                UserRole.MODERATOR,
                                TARGET,
                                UserRole.USER,
                                UserRole.MODERATOR))
                .isEqualTo(Outcome.ACTOR_NOT_ADMIN);
    }

    @Test
    void evaluateRoleTransition_userActor_isActorNotAdmin() {
        assertThat(
                        policy.evaluateRoleTransition(
                                ACTOR, UserRole.USER, TARGET, UserRole.USER, UserRole.MODERATOR))
                .isEqualTo(Outcome.ACTOR_NOT_ADMIN);
    }

    // Actor authority is checked before self-targeting, so a non-administrator acting on itself is
    // refused for lacking authority rather than for the target being itself.
    @Test
    void evaluateRoleTransition_nonAdminActorTargetingItself_reportsActorNotAdmin() {
        assertThat(
                        policy.evaluateRoleTransition(
                                ACTOR, UserRole.USER, ACTOR, UserRole.USER, UserRole.MODERATOR))
                .isEqualTo(Outcome.ACTOR_NOT_ADMIN);
    }

    @Test
    void assertMayChangeUserRole_permittedTransition_doesNotThrow() {
        assertThatCode(
                        () ->
                                policy.assertMayChangeUserRole(
                                        ACTOR,
                                        UserRole.ADMIN,
                                        TARGET,
                                        UserRole.USER,
                                        UserRole.MODERATOR))
                .doesNotThrowAnyException();
    }

    @Test
    void assertMayChangeUserRole_nonAdminActor_throwsForbidden() {
        assertThatThrownBy(
                        () ->
                                policy.assertMayChangeUserRole(
                                        ACTOR,
                                        UserRole.MODERATOR,
                                        TARGET,
                                        UserRole.USER,
                                        UserRole.MODERATOR))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.FORBIDDEN);
    }

    @Test
    void assertMayChangeUserRole_selfTarget_throwsSelfActionNotAllowed() {
        assertThatThrownBy(
                        () ->
                                policy.assertMayChangeUserRole(
                                        ACTOR,
                                        UserRole.ADMIN,
                                        ACTOR,
                                        UserRole.ADMIN,
                                        UserRole.MODERATOR))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.ADMIN_SELF_ACTION_NOT_ALLOWED);
    }

    @Test
    void assertMayChangeUserRole_adminTarget_throwsTargetProtected() {
        // The same code and status the status-change path answers for the same cause. A protected
        // target is a property of the target; the two remaining role outcomes are conflicts with
        // the state the request asks to leave, and those keep their own code.
        assertThatThrownBy(
                        () ->
                                policy.assertMayChangeUserRole(
                                        ACTOR,
                                        UserRole.ADMIN,
                                        TARGET,
                                        UserRole.ADMIN,
                                        UserRole.MODERATOR))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.ADMIN_TARGET_PROTECTED);
    }

    @Test
    void protectedTarget_answersTheSameCodeOnBothTheStatusAndRolePaths() {
        User target = targetUser(TARGET, UserRole.ADMIN);

        ApiErrorCode fromStatusPath =
                catchThrowableOfType(
                                AppException.class,
                                () ->
                                        policy.assertMayChangeUserStatus(
                                                ACTOR, UserRole.ADMIN, target))
                        .getErrorCode();
        ApiErrorCode fromRolePath =
                catchThrowableOfType(
                                AppException.class,
                                () ->
                                        policy.assertMayChangeUserRole(
                                                ACTOR,
                                                UserRole.ADMIN,
                                                TARGET,
                                                UserRole.ADMIN,
                                                UserRole.MODERATOR))
                        .getErrorCode();

        assertThat(fromStatusPath).isEqualTo(fromRolePath);
        assertThat(fromStatusPath.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void assertMayChangeUserRole_skipLevel_throwsRoleTransitionForbidden() {
        assertThatThrownBy(
                        () ->
                                policy.assertMayChangeUserRole(
                                        ACTOR,
                                        UserRole.ADMIN,
                                        TARGET,
                                        UserRole.USER,
                                        UserRole.ADMIN))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.ADMIN_ROLE_TRANSITION_NOT_ALLOWED);
    }

    @Test
    void assertMayChangeUserRole_noOp_throwsRoleTransitionForbidden() {
        assertThatThrownBy(
                        () ->
                                policy.assertMayChangeUserRole(
                                        ACTOR,
                                        UserRole.ADMIN,
                                        TARGET,
                                        UserRole.USER,
                                        UserRole.USER))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.ADMIN_ROLE_TRANSITION_NOT_ALLOWED);
    }

    @Test
    void assertMayChangeUserStatus_ordinaryTarget_doesNotThrow() {
        assertThatCode(
                        () ->
                                policy.assertMayChangeUserStatus(
                                        ACTOR, UserRole.ADMIN, targetUser(TARGET, UserRole.USER)))
                .doesNotThrowAnyException();
    }

    @Test
    void assertMayChangeUserStatus_moderatorActor_throwsForbidden() {
        assertStatusRefusedWith(UserRole.MODERATOR, TARGET, UserRole.USER, ApiErrorCode.FORBIDDEN);
    }

    @Test
    void assertMayChangeUserStatus_selfTarget_throwsSelfActionNotAllowed() {
        assertStatusRefusedWith(
                UserRole.ADMIN, ACTOR, UserRole.ADMIN, ApiErrorCode.ADMIN_SELF_ACTION_NOT_ALLOWED);
    }

    @Test
    void assertMayChangeUserStatus_adminTarget_throwsTargetProtected() {
        assertStatusRefusedWith(
                UserRole.ADMIN, TARGET, UserRole.ADMIN, ApiErrorCode.ADMIN_TARGET_PROTECTED);
    }

    // Actor authority is checked before self-targeting here too, so a moderator acting on itself is
    // refused for lacking authority rather than for the target being itself.
    @Test
    void assertMayChangeUserStatus_nonAdminActorTargetingItself_throwsForbidden() {
        assertStatusRefusedWith(
                UserRole.MODERATOR, ACTOR, UserRole.MODERATOR, ApiErrorCode.FORBIDDEN);
    }

    private void assertStatusRefusedWith(
            UserRole actorRole, UUID targetId, UserRole targetRole, ApiErrorCode expected) {
        assertThatThrownBy(
                        () ->
                                policy.assertMayChangeUserStatus(
                                        ACTOR, actorRole, targetUser(targetId, targetRole)))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(expected);
    }

    @Test
    void capabilitiesFor_agreesWithTheAssertionsForEveryActorAndTargetCombination() {
        // Asserted by agreement rather than by restating the rules. A test that listed the
        // expected flags would be the third copy of the same rules, and the second place they
        // could drift apart from the enforcement.
        for (UserRole actorRole : UserRole.values()) {
            for (UserRole targetRole : UserRole.values()) {
                for (boolean self : new boolean[] {false, true}) {
                    UUID targetId = self ? ACTOR : TARGET;
                    AdminAuthorizationService.Capabilities capabilities =
                            policy.capabilitiesFor(ACTOR, actorRole, targetId, targetRole);
                    String context =
                            "actor=" + actorRole + " target=" + targetRole + " self=" + self;

                    assertThat(capabilities.canChangeStatus())
                            .as("canChangeStatus for " + context)
                            .isEqualTo(
                                    permits(
                                            () ->
                                                    policy.assertMayChangeUserStatus(
                                                            ACTOR,
                                                            actorRole,
                                                            targetUser(targetId, targetRole))));

                    for (UserRole requested : UserRole.values()) {
                        assertThat(capabilities.assignableRoles().contains(requested))
                                .as("assignableRoles contains " + requested + " for " + context)
                                .isEqualTo(
                                        permits(
                                                () ->
                                                        policy.assertMayChangeUserRole(
                                                                ACTOR,
                                                                actorRole,
                                                                targetId,
                                                                targetRole,
                                                                requested)));
                    }

                    assertThat(capabilities.canChangeRole())
                            .as("canChangeRole for " + context)
                            .isEqualTo(!capabilities.assignableRoles().isEmpty());
                }
            }
        }
    }

    private static boolean permits(Runnable assertion) {
        try {
            assertion.run();
            return true;
        } catch (AppException ignored) {
            return false;
        }
    }

    private static User targetUser(UUID id, UserRole role) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        return user;
    }

    @Test
    void evaluateAdministratorAction_admin_isAllowed() {
        assertThat(policy.evaluateAdministratorAction(UserRole.ADMIN)).isEqualTo(Outcome.ALLOWED);
    }

    @Test
    void evaluateAdministratorAction_moderator_isActorNotAdmin() {
        assertThat(policy.evaluateAdministratorAction(UserRole.MODERATOR))
                .isEqualTo(Outcome.ACTOR_NOT_ADMIN);
    }

    @Test
    void evaluateAdministratorAction_user_isActorNotAdmin() {
        assertThat(policy.evaluateAdministratorAction(UserRole.USER))
                .isEqualTo(Outcome.ACTOR_NOT_ADMIN);
    }

    @Test
    void assertActorIsAdministrator_admin_passes() {
        when(userRepository.findByIdAndDeletedAtIsNull(ACTOR))
                .thenReturn(Optional.of(userWithRole(UserRole.ADMIN)));

        assertThatCode(() -> policy.assertActorIsAdministrator(ACTOR)).doesNotThrowAnyException();
    }

    @Test
    void assertActorIsAdministrator_moderator_throwsForbidden() {
        when(userRepository.findByIdAndDeletedAtIsNull(ACTOR))
                .thenReturn(Optional.of(userWithRole(UserRole.MODERATOR)));

        AppException thrown =
                catchThrowableOfType(
                        () -> policy.assertActorIsAdministrator(ACTOR), AppException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ApiErrorCode.FORBIDDEN);
    }

    @Test
    void assertActorIsAdministrator_plainUser_throwsForbidden() {
        when(userRepository.findByIdAndDeletedAtIsNull(ACTOR))
                .thenReturn(Optional.of(userWithRole(UserRole.USER)));

        AppException thrown =
                catchThrowableOfType(
                        () -> policy.assertActorIsAdministrator(ACTOR), AppException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ApiErrorCode.FORBIDDEN);
    }

    // A soft-deleted account and a demoted one are answered identically on purpose, so the
    // endpoint cannot be used to tell one from the other.
    @Test
    void assertActorIsAdministrator_deletedAccount_throwsForbidden() {
        when(userRepository.findByIdAndDeletedAtIsNull(ACTOR)).thenReturn(Optional.empty());

        AppException thrown =
                catchThrowableOfType(
                        () -> policy.assertActorIsAdministrator(ACTOR), AppException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ApiErrorCode.FORBIDDEN);
    }

    private static User userWithRole(UserRole role) {
        User user = new User();
        user.setId(ACTOR);
        user.setRole(role);
        return user;
    }
}
