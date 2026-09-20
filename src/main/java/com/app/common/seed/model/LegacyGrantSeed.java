package com.app.common.seed.model;

/**
 * A badge granted before the request flow existed, so its {@code user_verifications} row carries a
 * null {@code request_ticket_id}.
 *
 * <p>Nothing else in the seed exercises that nullable path: every other grant is anchored to a
 * verification ticket.
 */
public record LegacyGrantSeed(
        String username, String categoryKey, String grantedBy, int grantedOffsetDays) {}
