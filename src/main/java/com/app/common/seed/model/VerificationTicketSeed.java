package com.app.common.seed.model;

/**
 * One authored verification request from {@code verification/badges.json}.
 *
 * <p>Binds a claim to a named account rather than letting the support writer pick a subject at
 * random, which is what kept a photography claim from landing on a gym account. {@code outcome} is
 * the ticket's final status and is applied verbatim instead of being drawn from the shared status
 * queue, so the counts the badge lanes depend on are fixed rather than probabilistic.
 *
 * <p>At least three of the seven evidence fields are always populated, matching the {@code
 * verification_requests_min_evidence} CHECK and the floor {@code VerificationServiceImpl} enforces.
 */
public record VerificationTicketSeed(
        String username,
        String outcome,
        boolean viaEscalation,
        String categoryKey,
        String claimedName,
        String evidenceWebsite,
        String evidenceOtherProfile,
        String evidenceEmailDomain,
        String evidencePublishedWork,
        String evidencePress,
        String evidenceOfficialListing,
        String evidenceNote) {

    /** True when the ticket reached a terminal status and so can never hold the open-lane slot. */
    public boolean isTerminal() {
        return "answered".equals(outcome) || "rejected".equals(outcome);
    }
}
