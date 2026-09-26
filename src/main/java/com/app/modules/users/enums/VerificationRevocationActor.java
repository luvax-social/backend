package com.app.modules.users.enums;

/**
 * Who withdrew a verified badge, mirroring the {@code verification_revocation_actor} enum.
 *
 * <p>{@code SYSTEM} means an account status change swept the badge away rather than a person
 * judging the account unworthy of it. The distinction lives in the row and not only in the audit
 * log, because a moderator reviewing a resubmission needs it: a badge lost to a suspension is not a
 * verdict on the claim, and reading it as one is how a legitimate account gets refused twice.
 */
public enum VerificationRevocationActor {
    MODERATOR,
    SYSTEM
}
