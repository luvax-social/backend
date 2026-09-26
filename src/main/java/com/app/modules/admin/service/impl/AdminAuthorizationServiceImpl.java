package com.app.modules.admin.service.impl;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.modules.admin.service.AdminAuthorizationService;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.repository.UserRepository;

@Service
public class AdminAuthorizationServiceImpl implements AdminAuthorizationService {

    private final UserRepository userRepository;

    public AdminAuthorizationServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public Outcome evaluateAdministratorAction(UserRole actorRole) {
        return actorRole == UserRole.ADMIN ? Outcome.ALLOWED : Outcome.ACTOR_NOT_ADMIN;
    }

    @Override
    public void assertActorIsAdministrator(UUID actorId) {
        // A soft-deleted account resolves to no role at all and is refused with the same code as a
        // moderator, so the endpoint cannot distinguish a demoted account from a deleted one.
        UserRole actorRole =
                userRepository
                        .findByIdAndDeletedAtIsNull(actorId)
                        .map(User::getRole)
                        .orElseThrow(() -> new AppException(ApiErrorCode.FORBIDDEN));
        if (evaluateAdministratorAction(actorRole) != Outcome.ALLOWED) {
            throw new AppException(ApiErrorCode.FORBIDDEN);
        }
    }

    @Override
    public Outcome evaluateStatusChange(
            UUID actorId, UserRole actorRole, UUID targetId, UserRole targetRole) {
        return evaluateActorAndTarget(actorId, actorRole, targetId, targetRole);
    }

    @Override
    public Capabilities capabilitiesFor(
            UUID actorId, UserRole actorRole, UUID targetId, UserRole targetRole) {
        boolean canChangeStatus =
                evaluateStatusChange(actorId, actorRole, targetId, targetRole) == Outcome.ALLOWED;
        // Derived by asking the same evaluation about every role rather than by restating which
        // transitions are permitted. A rule added to evaluateRoleTransition is reflected here
        // without anybody remembering to come back and edit this method.
        List<UserRole> assignableRoles =
                Arrays.stream(UserRole.values())
                        .filter(
                                candidate ->
                                        evaluateRoleTransition(
                                                        actorId,
                                                        actorRole,
                                                        targetId,
                                                        targetRole,
                                                        candidate)
                                                == Outcome.ALLOWED)
                        .toList();
        return new Capabilities(canChangeStatus, !assignableRoles.isEmpty(), assignableRoles);
    }

    @Override
    public void assertMayChangeUserStatus(UUID actorId, UserRole actorRole, User target) {
        switch (evaluateActorAndTarget(actorId, actorRole, target.getId(), target.getRole())) {
            case ALLOWED -> {}
            case ACTOR_NOT_ADMIN -> throw new AppException(ApiErrorCode.FORBIDDEN);
            case SELF_TARGET -> throw new AppException(ApiErrorCode.ADMIN_SELF_ACTION_NOT_ALLOWED);
            default -> throw new AppException(ApiErrorCode.ADMIN_TARGET_PROTECTED);
        }
    }

    @Override
    public Outcome evaluateVerificationDecision(
            UUID actorId, UserRole actorRole, UUID targetId, UserRole targetRole) {
        if (actorRole != UserRole.MODERATOR && actorRole != UserRole.ADMIN) {
            return Outcome.ACTOR_NOT_ADMIN;
        }
        if (actorId.equals(targetId)) {
            return Outcome.SELF_TARGET;
        }
        if (targetRole == UserRole.ADMIN) {
            return Outcome.TARGET_IS_ADMIN;
        }
        return Outcome.ALLOWED;
    }

    @Override
    public void assertMayDecideVerification(UUID actorId, UserRole actorRole, User target) {
        switch (evaluateVerificationDecision(
                actorId, actorRole, target.getId(), target.getRole())) {
            case ALLOWED -> {}
            case ACTOR_NOT_ADMIN -> throw new AppException(ApiErrorCode.FORBIDDEN);
            case SELF_TARGET -> throw new AppException(ApiErrorCode.ADMIN_SELF_ACTION_NOT_ALLOWED);
            default -> throw new AppException(ApiErrorCode.ADMIN_TARGET_PROTECTED);
        }
    }

    @Override
    public Outcome evaluateRoleTransition(
            UUID actorId,
            UserRole actorRole,
            UUID targetId,
            UserRole targetRole,
            UserRole requestedRole) {
        Outcome shared = evaluateActorAndTarget(actorId, actorRole, targetId, targetRole);
        if (shared != Outcome.ALLOWED) {
            return shared;
        }
        if (targetRole == requestedRole) {
            return Outcome.NO_OP;
        }
        if (targetRole == UserRole.USER && requestedRole == UserRole.ADMIN) {
            return Outcome.SKIP_LEVEL;
        }
        return Outcome.ALLOWED;
    }

    @Override
    public void assertMayChangeUserRole(
            UUID actorId,
            UserRole actorRole,
            UUID targetId,
            UserRole targetRole,
            UserRole requestedRole) {
        switch (evaluateRoleTransition(actorId, actorRole, targetId, targetRole, requestedRole)) {
            case ALLOWED -> {}
            case ACTOR_NOT_ADMIN -> throw new AppException(ApiErrorCode.FORBIDDEN);
            case SELF_TARGET -> throw new AppException(ApiErrorCode.ADMIN_SELF_ACTION_NOT_ALLOWED);
            // The same condition on both paths, so the same code and the same status. A protected
            // target is a property of the target, not a conflict with the state the request asks
            // to leave, which is what separates it from the two transition conflicts below.
            case TARGET_IS_ADMIN -> throw new AppException(ApiErrorCode.ADMIN_TARGET_PROTECTED);
            case SKIP_LEVEL, NO_OP ->
                    throw new AppException(ApiErrorCode.ADMIN_ROLE_TRANSITION_NOT_ALLOWED);
        }
    }

    // The three rules every administrative action against an account shares, ordered so the most
    // specific applicable reason wins: an administrator acting on itself is answered with the
    // self-action reason rather than the generic protected-target one.
    private static Outcome evaluateActorAndTarget(
            UUID actorId, UserRole actorRole, UUID targetId, UserRole targetRole) {
        if (actorRole != UserRole.ADMIN) {
            return Outcome.ACTOR_NOT_ADMIN;
        }
        if (actorId.equals(targetId)) {
            return Outcome.SELF_TARGET;
        }
        if (targetRole == UserRole.ADMIN) {
            return Outcome.TARGET_IS_ADMIN;
        }
        return Outcome.ALLOWED;
    }
}
