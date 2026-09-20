package com.app.common.seed.model;

/**
 * One withdrawal of a granted badge.
 *
 * <p>{@code actor} distinguishes the two kinds the schema models. A {@code moderator} revocation is
 * a decision a person took and names them in {@code revokedBy}; a {@code system} revocation follows
 * an account status change, carries no actor at all, and its audit row's {@code admin_id} is null -
 * the same convention the discipline ladder's automatic strike uses.
 */
public record BadgeRevocationSeed(
        String username, String actor, String revokedBy, String reason, int revokedOffsetDays) {}
