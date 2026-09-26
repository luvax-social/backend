package com.app.modules.support.messaging;

/** Versioned support-domain event types published through the transactional outbox. */
public final class SupportEventTypes {

    /**
     * An account's verified badge was granted or revoked.
     *
     * <p>Consumed by the notification tier to rewrite the verified-actor flag on that account's
     * notifications. The payload carries only the account id: the consumer reads the badge's
     * current state, so a grant and a revocation processed out of order still converge on the
     * truth.
     */
    public static final String USER_VERIFICATION_CHANGED_V1 = "user.verification-changed.v1";

    private SupportEventTypes() {}
}
