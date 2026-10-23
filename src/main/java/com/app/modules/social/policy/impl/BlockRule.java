package com.app.modules.social.policy.impl;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.app.modules.social.policy.InteractionReason;
import com.app.modules.social.policy.InteractionRequest;
import com.app.modules.social.policy.InteractionType;
import com.app.modules.social.service.SocialService;

/** Denies any interaction between accounts in a block relationship, in either direction. */
@Component
class BlockRule implements InteractionRule {

    private static final Set<InteractionType> HANDLES =
            EnumSet.of(
                    InteractionType.COMMENT,
                    InteractionType.MENTION,
                    InteractionType.TAG,
                    InteractionType.COLLABORATION_INVITE,
                    InteractionType.MESSAGE_INITIATE,
                    InteractionType.MESSAGE_SEND);

    private final SocialService socialService;

    BlockRule(SocialService socialService) {
        this.socialService = socialService;
    }

    @Override
    public Set<InteractionType> handles() {
        return HANDLES;
    }

    @Override
    public Verdict apply(InteractionRequest request, EvaluationContext context) {
        Set<UUID> counterparts = new LinkedHashSet<>();
        counterparts.add(request.targetUserId());
        counterparts.addAll(request.secondaryTargetIds());
        counterparts.remove(request.actorId());
        for (UUID counterpart : counterparts) {
            if (socialService.isBlockedBetween(request.actorId(), counterpart)) {
                return Verdict.deny(InteractionReason.BLOCKED);
            }
        }
        return Verdict.pass();
    }
}
