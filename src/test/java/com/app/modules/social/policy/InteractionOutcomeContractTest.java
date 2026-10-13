package com.app.modules.social.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InteractionOutcomeContractTest {

    @Test
    void outcomes_areExactlyTheFiveSpecified() {
        assertThat(InteractionOutcome.values())
                .extracting(Enum::name)
                .containsExactly(
                        "ALLOW",
                        "ALLOW_HIDDEN",
                        "ROUTE_TO_REQUESTS",
                        "ROUTE_TO_HIDDEN_REQUESTS",
                        "DENY");
    }

    @Test
    void decision_isDeniedOnlyForDeny() {
        for (InteractionOutcome outcome : InteractionOutcome.values()) {
            assertThat(new InteractionDecision(outcome, InteractionReason.NONE).denied())
                    .as(outcome.name())
                    .isEqualTo(outcome == InteractionOutcome.DENY);
        }
    }
}
