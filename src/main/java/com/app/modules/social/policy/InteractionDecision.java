package com.app.modules.social.policy;

/**
 * The policy's answer for one interaction.
 *
 * @param outcome what the caller does with the interaction
 * @param reason why, for internal use and logging only
 */
public record InteractionDecision(InteractionOutcome outcome, InteractionReason reason) {

    public boolean denied() {
        return outcome == InteractionOutcome.DENY;
    }
}
