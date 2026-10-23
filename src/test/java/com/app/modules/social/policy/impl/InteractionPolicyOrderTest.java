package com.app.modules.social.policy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
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
import com.app.modules.users.service.UserPrivacySettingsService;

@ExtendWith(MockitoExtension.class)
class InteractionPolicyOrderTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final OffsetDateTime NOW_UTC = NOW.atOffset(ZoneOffset.UTC);

    private final UUID actor = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

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
                        new TagPermissionRule(privacySettings, followRepository),
                        new PendingRequestRule(),
                        new HiddenWordsRule(hiddenWordService),
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void blockedActor_neverReadsLimitsSettingsOrWordLists() {
        when(socialService.isBlockedBetween(actor, target)).thenReturn(true);
        List<InteractionRequest> everyEnabledType =
                List.of(
                        request(InteractionType.COMMENT, null),
                        request(InteractionType.MENTION, null),
                        request(InteractionType.MESSAGE_INITIATE, null),
                        request(
                                InteractionType.MESSAGE_SEND,
                                new ConversationContext(
                                        ConversationRequestState.PENDING, true, 0, "text")));

        for (InteractionRequest blocked : everyEnabledType) {
            assertThat(service.evaluate(blocked))
                    .as(blocked.type().name())
                    .isEqualTo(
                            new InteractionDecision(
                                    InteractionOutcome.DENY, InteractionReason.BLOCKED));
        }
        verifyNoInteractions(limitRepository, followRepository, privacySettings, hiddenWordService);
    }

    @Test
    void limitAndHiddenWordBothMatch_reportsLimitReason() {
        when(limitRepository.findActive(target, NOW_UTC))
                .thenReturn(
                        Optional.of(
                                InteractionLimit.builder()
                                        .userId(target)
                                        .limitNonFollowers(true)
                                        .expiresAt(NOW_UTC.plusDays(1))
                                        .build()));
        when(hiddenWordService.matcherFor(target))
                .thenReturn(PhraseMatcher.compile(List.of("spam")));

        assertThat(service.evaluate(request(InteractionType.COMMENT, null)))
                .isEqualTo(
                        new InteractionDecision(
                                InteractionOutcome.ALLOW_HIDDEN,
                                InteractionReason.LIMITED_NON_FOLLOWER));
    }

    private InteractionRequest request(InteractionType type, ConversationContext conversation) {
        return new InteractionRequest(actor, target, type, "spam", List.of(), conversation);
    }
}
