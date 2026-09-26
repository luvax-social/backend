package com.app.modules.mail.enums;

import lombok.Getter;

/**
 * Support mail that is neither auth mail nor a moderation notice.
 *
 * <p>Kept as its own enum rather than folded into {@link ModerationMailTemplate}, because a
 * confirmation link is not a moderation decision: it carries no community-standards line, it is
 * sent to an address the platform has not yet proved anything about, and it is the only mail here
 * sent outside the queue.
 */
@Getter
public enum SupportMailTemplate {
    CONFIRM_SUPPORT_REQUEST("mail/support/confirm-support-request", "Confirm your support request"),
    // Subject and body both deliberately say nothing about the decision. Anyone who knows
    // an address can cause this mail to be sent, so anything it explains is explained to
    // them. It links; it does not describe.
    RESEND_APPEAL_LINK("mail/support/resend-appeal-link", "Your link to contest a decision"),
    // Sent once, when an appeal filed through a signed link is accepted. Follows the same
    // discipline as the link above: it confirms that an appeal arrived and carries the link
    // for following it, and names no action, no content and no reason.
    APPEAL_STATUS_LINK("mail/support/appeal-status-link", "Following your appeal");

    private final String templatePath;
    private final String defaultSubject;

    SupportMailTemplate(String templatePath, String defaultSubject) {
        this.templatePath = templatePath;
        this.defaultSubject = defaultSubject;
    }
}
