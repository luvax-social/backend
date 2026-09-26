package com.app.modules.support.enums;

/**
 * What a support ticket is about, mirroring the {@code support_category} PostgreSQL enum.
 *
 * <p>The four {@code APPEAL_} values are the restricted set. Only an administrator may respond to
 * or close one, because unban, unsuspend, revoke-warning and revoke-strike are all
 * administrator-only actions: a moderator closing an appeal would be issuing a verdict they have no
 * capability to execute.
 */
public enum SupportCategory {
    APPEAL_BAN,
    APPEAL_SUSPENSION,
    APPEAL_WARNING_STRIKE,
    APPEAL_CONTENT_REMOVAL,
    ACCOUNT_ACCESS,
    ACCOUNT_DATA,
    BUG_REPORT,
    SAFETY_CONCERN,
    OTHER,
    /**
     * A request for a verified badge.
     *
     * <p>Deliberately not an appeal. {@link #isAppeal()} returning false here is what admits a
     * moderator to the verification queue: the appeal-requires-admin rule reads that method, and
     * verification is a discretionary grant rather than a verdict only an administrator can
     * execute.
     */
    VERIFICATION_REQUEST;

    /**
     * Whether this category is an appeal against a moderation decision.
     *
     * @return true for the four {@code APPEAL_} values
     */
    public boolean isAppeal() {
        return this == APPEAL_BAN
                || this == APPEAL_SUSPENSION
                || this == APPEAL_WARNING_STRIKE
                || this == APPEAL_CONTENT_REMOVAL;
    }
}
