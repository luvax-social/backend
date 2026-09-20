package com.app.common.seed.model;

import java.util.List;

/** Parsed contents of {@code verification/badges.json}. */
public record BadgeSeed(
        List<VerificationTicketSeed> verificationTickets,
        List<LegacyGrantSeed> legacyGrants,
        List<BadgeRevocationSeed> revocations) {}
