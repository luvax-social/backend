package com.app.modules.mail.service;

/** Per-recipient send budget for moderation notices. */
public interface ModerationMailThrottle {

    /**
     * Records one intended send and reports whether it is within the recipient's budget.
     *
     * @param recipientEmail the address the notice would go to
     * @return true when the send may proceed, false when the recipient's window is already full
     */
    boolean tryAcquire(String recipientEmail);
}
