package com.app.modules.social.policy.impl;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.app.modules.social.policy.InteractionOutcome;
import com.app.modules.social.policy.InteractionReason;
import com.app.modules.social.policy.InteractionRequest;
import com.app.modules.social.policy.InteractionType;
import com.app.modules.social.repository.FollowRepository;
import com.app.modules.users.enums.TagPermission;
import com.app.modules.users.service.UserPrivacySettingsService;
import com.app.modules.users.service.UserPrivacySettingsService.TagSettings;

/**
 * Applies the tagged account's "who can tag me" and "manually approve tags" settings.
 *
 * <p>{@code nobody} denies. {@code following} denies unless the tagged account follows the tagger,
 * which is the direction message permission uses too. When manual approval is on, the base outcome
 * becomes {@code ROUTE_TO_REQUESTS}: the tag exists but only the post owner and the tagged account
 * see it until the tagged account approves it.
 */
@Component
class TagPermissionRule implements InteractionRule {

    private static final Set<InteractionType> HANDLES = EnumSet.of(InteractionType.TAG);

    private final UserPrivacySettingsService privacySettings;
    private final FollowRepository followRepository;

    TagPermissionRule(
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
        TagSettings settings = privacySettings.tagSettingsOf(request.targetUserId());
        if (settings.permission() == TagPermission.NOBODY) {
            return Verdict.deny(InteractionReason.PERMISSION_NOBODY);
        }
        if (settings.permission() == TagPermission.FOLLOWING
                && followRepository
                        .findAcceptedAt(request.targetUserId(), request.actorId())
                        .isEmpty()) {
            return Verdict.deny(InteractionReason.PERMISSION_FOLLOWING_ONLY);
        }
        if (settings.manualApproval()) {
            return Verdict.base(
                    InteractionOutcome.ROUTE_TO_REQUESTS, InteractionReason.APPROVAL_REQUIRED);
        }
        return Verdict.pass();
    }
}
