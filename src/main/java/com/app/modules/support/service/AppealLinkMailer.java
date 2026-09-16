package com.app.modules.support.service;

/** Sends a replacement appeal link to the address a moderation notice was sent to. */
public interface AppealLinkMailer {

    /**
     * Mails one replacement appeal link.
     *
     * <p>Sent directly rather than through the outbox, following {@code SupportConfirmationMailer}:
     * the requester is sitting at the form, and a link that arrives minutes later through a queue
     * drain leaves them staring at a page with no feedback. A lost send costs nothing but another
     * request.
     *
     * <p>The mail states that a link was requested and carries the link. It never names the
     * decision, the action taken or the content involved. Anyone who merely knows an address can
     * cause this mail to be sent, so anything it explains is disclosed to them too.
     *
     * @param contactEmail the verified address on the resolved account
     * @param appealUrl the freshly minted appeal link
     */
    void sendAppealLink(String contactEmail, String appealUrl);
}
