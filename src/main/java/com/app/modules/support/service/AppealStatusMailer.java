package com.app.modules.support.service;

import java.util.UUID;

/** Mails the read-only status link minted when an appeal is filed through a signed link. */
public interface AppealStatusMailer {

    /**
     * Mails one status link for an appeal that has just been filed.
     *
     * <p>Sent directly rather than through the outbox, following {@code AppealLinkMailer}: the
     * appellant is sitting at the confirmation screen, and the screen and the mail carry the same
     * address, so a queue drain minutes later would be the slower of two copies of one value.
     *
     * <p>Exists because the appellant holds no session. The status token is returned in the
     * response, but the response lives only as long as the tab does, and it is never persisted to
     * browser storage because it is a bearer credential. Without this mail, closing the tab loses
     * the only way back to the appeal.
     *
     * <p>The mail states that an appeal was received and carries the link. It never names the
     * decision, the action taken, the content involved or the reason given, following the same
     * disclosure discipline as the replacement-link mail.
     *
     * <p>Refuses silently for an unverified address, and swallows a send failure. The appeal is
     * already filed by the time this runs, so nothing here may fail the submission.
     *
     * @param userId the account the appeal belongs to, used to check address verification
     * @param contactEmail the address recorded on the ticket
     * @param statusToken the freshly minted read-only status token
     */
    void sendStatusLink(UUID userId, String contactEmail, String statusToken);
}
