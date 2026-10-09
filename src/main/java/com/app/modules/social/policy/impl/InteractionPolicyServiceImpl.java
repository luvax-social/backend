package com.app.modules.social.policy.impl;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.modules.social.policy.InteractionDecision;
import com.app.modules.social.policy.InteractionOutcome;
import com.app.modules.social.policy.InteractionPolicyService;
import com.app.modules.social.policy.InteractionReason;
import com.app.modules.social.policy.InteractionRequest;
import com.app.modules.social.policy.InteractionType;

@Service
public class InteractionPolicyServiceImpl implements InteractionPolicyService {

    // Only content interactions turn ALLOW into ALLOW_HIDDEN; an allowed message is never hidden
    private static final Set<InteractionType> HIDDEN_WHEN_ALLOWED =
            EnumSet.of(InteractionType.COMMENT, InteractionType.MENTION);

    private final BlockRule blockRule;
    private final List<InteractionRule> chain;
    private final Clock clock;

    @Autowired
    InteractionPolicyServiceImpl(
            BlockRule blockRule,
            LimitRule limitRule,
            MessagePermissionRule messagePermissionRule,
            PendingRequestRule pendingRequestRule,
            HiddenWordsRule hiddenWordsRule) {
        this(
                blockRule,
                limitRule,
                messagePermissionRule,
                pendingRequestRule,
                hiddenWordsRule,
                Clock.systemUTC());
    }

    InteractionPolicyServiceImpl(
            BlockRule blockRule,
            LimitRule limitRule,
            MessagePermissionRule messagePermissionRule,
            PendingRequestRule pendingRequestRule,
            HiddenWordsRule hiddenWordsRule,
            Clock clock) {
        this.blockRule = blockRule;
        // The order is the policy, so it is fixed here rather than left to bean ordering
        this.chain =
                List.of(
                        blockRule,
                        limitRule,
                        messagePermissionRule,
                        pendingRequestRule,
                        hiddenWordsRule);
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public InteractionDecision evaluate(InteractionRequest request) {
        InteractionType type = request.type();
        if (chain.stream().noneMatch(rule -> rule.handles().contains(type))) {
            throw new IllegalArgumentException("interaction not enabled: " + type);
        }
        if (type == InteractionType.MESSAGE_SEND && request.conversation() == null) {
            throw new IllegalArgumentException("a message send needs its conversation context");
        }
        EvaluationContext context = new EvaluationContext(OffsetDateTime.now(clock));
        if (request.actorId().equals(request.targetUserId())) {
            return evaluateSelf(request, context);
        }

        InteractionOutcome base = InteractionOutcome.ALLOW;
        InteractionReason baseReason = InteractionReason.NONE;
        InteractionReason firstHide = null;
        for (InteractionRule rule : chain) {
            if (!rule.handles().contains(type)) {
                continue;
            }
            Verdict verdict = rule.apply(request, context);
            switch (verdict.kind()) {
                case DENY -> {
                    return new InteractionDecision(InteractionOutcome.DENY, verdict.reason());
                }
                case HIDE -> {
                    if (firstHide == null) {
                        firstHide = verdict.reason();
                    }
                }
                case BASE -> {
                    base = verdict.base();
                    baseReason = verdict.reason();
                }
                case PASS -> {}
            }
        }
        if (firstHide != null) {
            InteractionOutcome hidden = hide(type, base);
            if (hidden != base) {
                return new InteractionDecision(hidden, firstHide);
            }
        }
        return new InteractionDecision(base, baseReason);
    }

    // Acting on one's own post still respects a block with a secondary target, such as the author
    // of the comment being replied to
    private InteractionDecision evaluateSelf(
            InteractionRequest request, EvaluationContext context) {
        Verdict block = blockRule.apply(request, context);
        if (block.kind() == Verdict.Kind.DENY) {
            return new InteractionDecision(InteractionOutcome.DENY, block.reason());
        }
        return new InteractionDecision(InteractionOutcome.ALLOW, InteractionReason.SELF);
    }

    private static InteractionOutcome hide(InteractionType type, InteractionOutcome base) {
        return switch (base) {
            case ALLOW ->
                    HIDDEN_WHEN_ALLOWED.contains(type) ? InteractionOutcome.ALLOW_HIDDEN : base;
            case ROUTE_TO_REQUESTS -> InteractionOutcome.ROUTE_TO_HIDDEN_REQUESTS;
            default -> base;
        };
    }
}
