package com.app.modules.support.service;

/** Sends the link that proves a public submitter controls the address they gave. */
public interface SupportConfirmationMailer {

    /**
     * Mails the confirmation link for one pending submission.
     *
     * <p>Sent directly rather than through the outbox, because the submission is worthless until it
     * is confirmed: a confirmation that arrives minutes later through a queue drain would leave the
     * submitter staring at a form with no feedback, and a lost confirmation costs nothing but a
     * resubmission.
     *
     * @param contactEmail the address the submitter gave, unproven at this point
     * @param rawToken the single-use confirmation token
     */
    void sendConfirmation(String contactEmail, String rawToken);
}
