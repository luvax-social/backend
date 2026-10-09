package com.app.modules.social.policy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.text.PhraseMatcher;
import com.app.modules.social.entity.InteractionLimit;
import com.app.modules.social.policy.ConversationContext;
import com.app.modules.social.policy.ConversationRequestState;
import com.app.modules.social.policy.InteractionDecision;
import com.app.modules.social.policy.InteractionOutcome;
import com.app.modules.social.policy.InteractionReason;
import com.app.modules.social.policy.InteractionRequest;
import com.app.modules.social.policy.InteractionType;
import com.app.modules.social.repository.FollowRepository;
import com.app.modules.social.repository.InteractionLimitRepository;
import com.app.modules.social.service.HiddenWordService;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.enums.MessagePermission;
import com.app.modules.users.service.UserPrivacySettingsService;

@ExtendWith(MockitoExtension.class)
class InteractionPolicyServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final OffsetDateTime NOW_UTC = NOW.atOffset(ZoneOffset.UTC);

    private final UUID actor = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private final UUID parentAuthor = UUID.randomUUID();

    @Mock private SocialService socialService;
    @Mock private FollowRepository followRepository;
    @Mock private InteractionLimitRepository limitRepository;
    @Mock private UserPrivacySettingsService privacySettings;
    @Mock private HiddenWordService hiddenWordService;

    private InteractionPolicyServiceImpl service;

    @BeforeEach
    void setUp() {
        service =
                new InteractionPolicyServiceImpl(
                        new BlockRule(socialService),
                        new LimitRule(limitRepository, followRepository),
                        new MessagePermissionRule(privacySettings, followRepository),
                        new PendingRequestRule(),
                        new HiddenWordsRule(hiddenWordService),
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void comment_blockedEitherDirection_denies() {
        when(socialService.isBlockedBetween(actor, target)).thenReturn(true);

        InteractionDecision decision = service.evaluate(comment("hello"));

        assertThat(decision).isEqualTo(denied(InteractionReason.BLOCKED));
        assertThat(decision.denied()).isTrue();
    }

    @Test
    void comment_blockedWithParentAuthor_denies() {
        when(socialService.isBlockedBetween(actor, target)).thenReturn(false);
        when(socialService.isBlockedBetween(actor, parentAuthor)).thenReturn(true);
        InteractionRequest reply =
                new InteractionRequest(
                        actor, target, InteractionType.COMMENT, "hi", List.of(parentAuthor), null);

        assertThat(service.evaluate(reply)).isEqualTo(denied(InteractionReason.BLOCKED));
    }

    @Test
    void comment_ownerLimitsNonFollowersAndActorDoesNotFollow_allowHidden() {
        limit(true, false);
        noPersonalMatch();

        assertThat(service.evaluate(comment("hello")))
                .isEqualTo(
                        decision(
                                InteractionOutcome.ALLOW_HIDDEN,
                                InteractionReason.LIMITED_NON_FOLLOWER));
    }

    @Test
    void comment_ownerLimitsRecentFollowersAndFollowAcceptedSixDaysAgo_allowHidden() {
        limit(false, true);
        actorFollowsTargetSince(NOW_UTC.minusDays(6));
        noPersonalMatch();

        assertThat(service.evaluate(comment("hello")))
                .isEqualTo(
                        decision(
                                InteractionOutcome.ALLOW_HIDDEN,
                                InteractionReason.LIMITED_RECENT_FOLLOWER));
    }

    @Test
    void comment_ownerLimitsRecentFollowersAndFollowAcceptedEightDaysAgo_allow() {
        limit(false, true);
        actorFollowsTargetSince(NOW_UTC.minusDays(8));
        noPersonalMatch();

        assertThat(service.evaluate(comment("hello")))
                .isEqualTo(decision(InteractionOutcome.ALLOW, InteractionReason.NONE));
    }

    @Test
    void comment_limitExpired_allow() {
        // findActive filters on expires_at > now, so an expired row reads as absent
        when(limitRepository.findActive(target, NOW_UTC)).thenReturn(Optional.empty());
        noPersonalMatch();

        assertThat(service.evaluate(comment("hello")))
                .isEqualTo(decision(InteractionOutcome.ALLOW, InteractionReason.NONE));
        verifyNoInteractions(followRepository);
    }

    @Test
    void comment_contentMatchesOwnerPersonalList_allowHidden() {
        when(hiddenWordService.matcherFor(target))
                .thenReturn(PhraseMatcher.compile(List.of("spam")));

        assertThat(service.evaluate(comment("Buy SP4M now")))
                .isEqualTo(
                        decision(InteractionOutcome.ALLOW_HIDDEN, InteractionReason.HIDDEN_WORD));
    }

    @Test
    void comment_contentMatchesSystemListButOwnerDisabledIt_allow() {
        // matcherFor leaves the platform-wide list out when the owner turned it off
        noPersonalMatch();

        assertThat(service.evaluate(comment("a word only the system list holds")))
                .isEqualTo(decision(InteractionOutcome.ALLOW, InteractionReason.NONE));
    }

    @Test
    void comment_onOwnPost_allowSelfAndSkipsLookups() {
        InteractionRequest own =
                new InteractionRequest(
                        actor, actor, InteractionType.COMMENT, "spam", List.of(), null);

        assertThat(service.evaluate(own))
                .isEqualTo(decision(InteractionOutcome.ALLOW, InteractionReason.SELF));
        verifyNoInteractions(
                socialService,
                followRepository,
                limitRepository,
                privacySettings,
                hiddenWordService);
    }

    @Test
    void comment_onOwnPostReplyingToBlockedCommenter_denies() {
        when(socialService.isBlockedBetween(actor, parentAuthor)).thenReturn(true);
        InteractionRequest own =
                new InteractionRequest(
                        actor, actor, InteractionType.COMMENT, "hi", List.of(parentAuthor), null);

        assertThat(service.evaluate(own)).isEqualTo(denied(InteractionReason.BLOCKED));
    }

    @Test
    void mention_limitedActor_allowHiddenSoNoNotification() {
        limit(true, false);

        assertThat(service.evaluate(request(InteractionType.MENTION)))
                .isEqualTo(
                        decision(
                                InteractionOutcome.ALLOW_HIDDEN,
                                InteractionReason.LIMITED_NON_FOLLOWER));
        verifyNoInteractions(hiddenWordService, privacySettings);
    }

    @Test
    void mention_blocked_denies() {
        when(socialService.isBlockedBetween(actor, target)).thenReturn(true);

        assertThat(service.evaluate(request(InteractionType.MENTION)))
                .isEqualTo(denied(InteractionReason.BLOCKED));
    }

    @Test
    void initiate_permissionNobody_denies() {
        when(privacySettings.messagePermissionOf(target)).thenReturn(MessagePermission.NOBODY);

        assertThat(service.evaluate(request(InteractionType.MESSAGE_INITIATE)))
                .isEqualTo(denied(InteractionReason.PERMISSION_NOBODY));
    }

    @Test
    void initiate_permissionFollowingAndRecipientDoesNotFollowActor_denies() {
        when(privacySettings.messagePermissionOf(target)).thenReturn(MessagePermission.FOLLOWING);

        assertThat(service.evaluate(request(InteractionType.MESSAGE_INITIATE)))
                .isEqualTo(denied(InteractionReason.PERMISSION_FOLLOWING_ONLY));
    }

    @Test
    void initiate_permissionEveryoneNotFollowed_routeToRequests() {
        when(privacySettings.messagePermissionOf(target)).thenReturn(MessagePermission.EVERYONE);

        assertThat(service.evaluate(request(InteractionType.MESSAGE_INITIATE)))
                .isEqualTo(
                        decision(
                                InteractionOutcome.ROUTE_TO_REQUESTS,
                                InteractionReason.NOT_FOLLOWED_REQUEST));
        verifyNoInteractions(hiddenWordService);
    }

    @Test
    void initiate_recipientFollowsActor_allow() {
        when(privacySettings.messagePermissionOf(target)).thenReturn(MessagePermission.FOLLOWING);
        recipientFollowsActor();

        assertThat(service.evaluate(request(InteractionType.MESSAGE_INITIATE)))
                .isEqualTo(decision(InteractionOutcome.ALLOW, InteractionReason.NONE));
    }

    @Test
    void initiate_limitedAndNotFollowed_routeToHiddenRequests() {
        limit(true, false);
        when(privacySettings.messagePermissionOf(target)).thenReturn(MessagePermission.EVERYONE);

        assertThat(service.evaluate(request(InteractionType.MESSAGE_INITIATE)))
                .isEqualTo(
                        decision(
                                InteractionOutcome.ROUTE_TO_HIDDEN_REQUESTS,
                                InteractionReason.LIMITED_NON_FOLLOWER));
    }

    @Test
    void initiate_limitedButRecipientFollowsActor_allow() {
        limit(true, false);
        when(privacySettings.messagePermissionOf(target)).thenReturn(MessagePermission.EVERYONE);
        when(followRepository.findAcceptedAt(actor, target)).thenReturn(Optional.empty());
        recipientFollowsActor();

        assertThat(service.evaluate(request(InteractionType.MESSAGE_INITIATE)))
                .isEqualTo(decision(InteractionOutcome.ALLOW, InteractionReason.NONE));
    }

    @Test
    void send_accepted_onlyBlockApplies() {
        InteractionDecision decision =
                service.evaluate(send(ConversationRequestState.ACCEPTED, true, 5, "text", "spam"));

        assertThat(decision).isEqualTo(decision(InteractionOutcome.ALLOW, InteractionReason.NONE));
        verify(socialService).isBlockedBetween(actor, target);
        verifyNoInteractions(followRepository, limitRepository, privacySettings, hiddenWordService);
    }

    @Test
    void send_acceptedButBlocked_denies() {
        when(socialService.isBlockedBetween(actor, target)).thenReturn(true);

        assertThat(service.evaluate(send(ConversationRequestState.ACCEPTED, true, 5, "text", "hi")))
                .isEqualTo(denied(InteractionReason.BLOCKED));
    }

    @Test
    void send_pendingSecondMessage_deniesAwaitingAcceptance() {
        assertThat(service.evaluate(send(ConversationRequestState.PENDING, true, 1, "text", "hi")))
                .isEqualTo(denied(InteractionReason.AWAITING_ACCEPTANCE));
    }

    @Test
    void send_pendingFirstImage_deniesAwaitingAcceptance() {
        assertThat(service.evaluate(send(ConversationRequestState.PENDING, true, 0, "image", null)))
                .isEqualTo(denied(InteractionReason.AWAITING_ACCEPTANCE));
    }

    @Test
    void send_pendingFirstText_routeToRequests() {
        noPersonalMatch();

        assertThat(service.evaluate(send(ConversationRequestState.PENDING, true, 0, "text", "hi")))
                .isEqualTo(decision(InteractionOutcome.ROUTE_TO_REQUESTS, InteractionReason.NONE));
    }

    @Test
    void send_pendingFirstTextMatchingRecipientList_routeToHiddenRequests() {
        when(hiddenWordService.matcherFor(target))
                .thenReturn(PhraseMatcher.compile(List.of("spam")));

        assertThat(
                        service.evaluate(
                                send(ConversationRequestState.PENDING, true, 0, "text", "spam!")))
                .isEqualTo(
                        decision(
                                InteractionOutcome.ROUTE_TO_HIDDEN_REQUESTS,
                                InteractionReason.HIDDEN_WORD));
    }

    @Test
    void send_pendingHiddenFirstText_staysInHiddenRequests() {
        noPersonalMatch();

        assertThat(
                        service.evaluate(
                                send(
                                        ConversationRequestState.PENDING_HIDDEN,
                                        true,
                                        0,
                                        "text",
                                        "hi")))
                .isEqualTo(
                        decision(
                                InteractionOutcome.ROUTE_TO_HIDDEN_REQUESTS,
                                InteractionReason.NONE));
    }

    @Test
    void send_pendingByRecipient_allowsSoTheMessageModuleCanAccept() {
        InteractionDecision decision =
                service.evaluate(send(ConversationRequestState.PENDING, false, 0, "image", "spam"));

        assertThat(decision).isEqualTo(decision(InteractionOutcome.ALLOW, InteractionReason.NONE));
        verifyNoInteractions(followRepository, limitRepository, privacySettings, hiddenWordService);
    }

    @Test
    void send_declinedByRecipient_deniesWithSameReasonAsPending() {
        assertThat(service.evaluate(send(ConversationRequestState.DECLINED, true, 0, "text", "hi")))
                .isEqualTo(denied(InteractionReason.AWAITING_ACCEPTANCE));
        verifyNoInteractions(followRepository, limitRepository, privacySettings, hiddenWordService);
    }

    @Test
    void send_acceptedConversationContentMatchingList_allow() {
        assertThat(
                        service.evaluate(
                                send(ConversationRequestState.ACCEPTED, true, 3, "text", "spam")))
                .isEqualTo(decision(InteractionOutcome.ALLOW, InteractionReason.NONE));
        verifyNoInteractions(hiddenWordService);
    }

    @Test
    void send_withoutConversationContext_isRefused() {
        assertThatThrownBy(() -> service.evaluate(request(InteractionType.MESSAGE_SEND)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conversation");
    }

    @Test
    void reservedTypes_areRefused() {
        assertThatThrownBy(() -> service.evaluate(request(InteractionType.TAG)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TAG");
        assertThatThrownBy(() -> service.evaluate(request(InteractionType.STORY_REPLY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("interaction not enabled: STORY_REPLY");
        assertThatThrownBy(() -> service.evaluate(request(InteractionType.STORY_RESPONSE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("interaction not enabled: STORY_RESPONSE");
        verifyNoInteractions(socialService);
    }

    private InteractionRequest comment(String content) {
        return new InteractionRequest(
                actor, target, InteractionType.COMMENT, content, List.of(), null);
    }

    private InteractionRequest request(InteractionType type) {
        return new InteractionRequest(actor, target, type, null, List.of(), null);
    }

    private InteractionRequest send(
            ConversationRequestState state,
            boolean actorIsRequester,
            long actorMessagesSoFar,
            String messageType,
            String content) {
        return new InteractionRequest(
                actor,
                target,
                InteractionType.MESSAGE_SEND,
                content,
                List.of(),
                new ConversationContext(state, actorIsRequester, actorMessagesSoFar, messageType));
    }

    private void limit(boolean nonFollowers, boolean recentFollowers) {
        InteractionLimit row =
                InteractionLimit.builder()
                        .userId(target)
                        .limitNonFollowers(nonFollowers)
                        .limitRecentFollowers(recentFollowers)
                        .expiresAt(NOW_UTC.plus(Duration.ofDays(3)))
                        .build();
        when(limitRepository.findActive(target, NOW_UTC)).thenReturn(Optional.of(row));
    }

    private void actorFollowsTargetSince(OffsetDateTime acceptedAt) {
        when(followRepository.findAcceptedAt(actor, target)).thenReturn(Optional.of(acceptedAt));
    }

    private void recipientFollowsActor() {
        when(followRepository.findAcceptedAt(target, actor))
                .thenReturn(Optional.of(NOW_UTC.minusDays(100)));
    }

    private void noPersonalMatch() {
        when(hiddenWordService.matcherFor(any()))
                .thenReturn(PhraseMatcher.compile(List.of("unrelated")));
    }

    private static InteractionDecision decision(
            InteractionOutcome outcome, InteractionReason reason) {
        return new InteractionDecision(outcome, reason);
    }

    private static InteractionDecision denied(InteractionReason reason) {
        return new InteractionDecision(InteractionOutcome.DENY, reason);
    }
}
