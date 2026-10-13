package com.app.modules.social.policy.impl;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.app.modules.social.policy.ConversationRequestState;
import com.app.modules.social.policy.InteractionOutcome;
import com.app.modules.social.policy.InteractionReason;
import com.app.modules.social.policy.InteractionRequest;
import com.app.modules.social.policy.InteractionType;
import com.app.modules.social.repository.FollowRepository;
import com.app.modules.users.enums.MessagePermission;
import com.app.modules.users.service.UserPrivacySettingsService;

/**
 * Applies the recipient's message permission to a new conversation, and sets the requests base
 * outcome for a requester's send into a conversation the recipient has not accepted.
 */
@Component
class MessagePermissionRule implements InteractionRule {

    private static final Set<InteractionType> HANDLES =
            EnumSet.of(InteractionType.MESSAGE_INITIATE, InteractionType.MESSAGE_SEND);

    private final UserPrivacySettingsService privacySettings;
    private final FollowRepository followRepository;

    MessagePermissionRule(
            UserPrivacySettingsService privacySettings, FollowRepository followRepository) {
        this.privacySettings = privacySettings;
        this.followRepository = followRepository;
    }

    @Override
    public Set<InteractionType> handles() {
        return HANDLES;
    }

    @Override
    public Verdict apply(InteractionRequest request, EvaluationContext context) {
        if (request.type() == InteractionType.MESSAGE_SEND) {
            return sendIntoPending(request);
        }
        MessagePermission permission = privacySettings.messagePermissionOf(request.targetUserId());
        if (permission == MessagePermission.NOBODY) {
            return Verdict.deny(InteractionReason.PERMISSION_NOBODY);
        }
        boolean recipientFollowsActor =
                followRepository
                        .findAcceptedAt(request.targetUserId(), request.actorId())
                        .isPresent();
        if (recipientFollowsActor) {
            return Verdict.pass();
        }
        if (permission == MessagePermission.FOLLOWING) {
            return Verdict.deny(InteractionReason.PERMISSION_FOLLOWING_ONLY);
        }
        return Verdict.base(
                InteractionOutcome.ROUTE_TO_REQUESTS, InteractionReason.NOT_FOLLOWED_REQUEST);
    }

    private static Verdict sendIntoPending(InteractionRequest request) {
        if (!InteractionRule.isRequesterSendIntoPending(request)) {
            return Verdict.pass();
        }
        // A request already filed as hidden stays hidden: a later message never surfaces it
        InteractionOutcome base =
                request.conversation().state() == ConversationRequestState.PENDING_HIDDEN
                        ? InteractionOutcome.ROUTE_TO_HIDDEN_REQUESTS
                        : InteractionOutcome.ROUTE_TO_REQUESTS;
        return Verdict.base(base, InteractionReason.NONE);
    }
}
