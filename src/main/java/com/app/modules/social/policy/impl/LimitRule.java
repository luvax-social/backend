package com.app.modules.social.policy.impl;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.app.modules.social.entity.InteractionLimit;
import com.app.modules.social.policy.InteractionReason;
import com.app.modules.social.policy.InteractionRequest;
import com.app.modules.social.policy.InteractionType;
import com.app.modules.social.repository.FollowRepository;
import com.app.modules.social.repository.InteractionLimitRepository;

/**
 * Hides interactions from the audiences the target limited, while the limit is in force.
 *
 * <p>A send into an accepted conversation is outside a limit: the limit governs who may reach the
 * target, not conversations the target already let in.
 */
@Component
class LimitRule implements InteractionRule {

    static final int RECENT_FOLLOWER_DAYS = 7;

    private static final Set<InteractionType> HANDLES =
            EnumSet.of(
                    InteractionType.COMMENT,
                    InteractionType.MENTION,
                    InteractionType.TAG,
                    InteractionType.MESSAGE_INITIATE,
                    InteractionType.MESSAGE_SEND);

    private final InteractionLimitRepository limitRepository;
    private final FollowRepository followRepository;

    LimitRule(InteractionLimitRepository limitRepository, FollowRepository followRepository) {
        this.limitRepository = limitRepository;
        this.followRepository = followRepository;
    }

    @Override
    public Set<InteractionType> handles() {
        return HANDLES;
    }

    @Override
    public Verdict apply(InteractionRequest request, EvaluationContext context) {
        if (request.type() == InteractionType.MESSAGE_SEND
                && !InteractionRule.isRequesterSendIntoPending(request)) {
            return Verdict.pass();
        }
        Optional<InteractionLimit> active =
                limitRepository.findActive(request.targetUserId(), context.now());
        if (active.isEmpty()) {
            return Verdict.pass();
        }
        InteractionLimit limit = active.get();
        // The follow is read only once a limit is in force, so an unlimited target costs one lookup
        Optional<OffsetDateTime> acceptedAt =
                followRepository.findAcceptedAt(request.actorId(), request.targetUserId());
        if (acceptedAt.isEmpty()) {
            return limit.isLimitNonFollowers()
                    ? Verdict.hide(InteractionReason.LIMITED_NON_FOLLOWER)
                    : Verdict.pass();
        }
        OffsetDateTime recentSince = context.now().minusDays(RECENT_FOLLOWER_DAYS);
        if (limit.isLimitRecentFollowers() && acceptedAt.get().isAfter(recentSince)) {
            return Verdict.hide(InteractionReason.LIMITED_RECENT_FOLLOWER);
        }
        return Verdict.pass();
    }
}
