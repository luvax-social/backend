package com.app.modules.mail.util;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;

/**
 * The single place that decides whether a send failed or was deliberately never attempted.
 *
 * <p>{@code AbstractTemplateMailSender.dispatch} refuses a recipient outside the configured
 * allowlist by throwing {@code MAIL_RECIPIENT_NOT_ALLOWED}. That is an operator's configuration
 * decision, not a delivery failure, and every lane that reaches a transport has to tell the two
 * apart the same way. Before this existed each lane answered differently: the moderation lane
 * recorded {@code SKIPPED} while the auth lane dead-lettered - so on any developer machine, where
 * the dev allowlist is {@code example.invalid}, the dead-letter queue filled with decisions the
 * operator had already made.
 *
 * <p>The classification travels as a distinct error code, so this is routing rather than detection.
 * Walks the cause chain because a lane may wrap the original before it gets here.
 */
public final class MailSuppression {

    private MailSuppression() {}

    /**
     * Whether this failure is the allowlist refusing a recipient rather than a transport failing.
     *
     * @param ex the failure a send call threw, or any exception wrapping it
     * @return true when the deployment was configured never to send to that address
     */
    public static boolean isRecipientSuppressed(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof AppException appException) {
                return appException.getErrorCode() == ApiErrorCode.MAIL_RECIPIENT_NOT_ALLOWED;
            }
            current = current.getCause();
        }
        return false;
    }
}
