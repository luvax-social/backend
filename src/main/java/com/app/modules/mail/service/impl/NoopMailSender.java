package com.app.modules.mail.service.impl;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.app.modules.mail.config.MailProperties;
import com.app.modules.mail.config.noop.SentMail;
import com.app.modules.mail.config.noop.SentMailRecorder;
import com.app.modules.mail.util.MailTemplateRenderer;

/**
 * Non-network mail sender used for the entire test phase.
 *
 * <p>Never opens a connection or issues a request; records what it was asked to send into a {@link
 * SentMailRecorder} instead, so a test can assert on rendered content without any risk of reaching
 * a real provider.
 */
@Component
@ConditionalOnProperty(prefix = "app.mail", name = "transport", havingValue = "noop")
public class NoopMailSender extends AbstractTemplateMailSender {

    private final SentMailRecorder sentMailRecorder;

    public NoopMailSender(
            MailProperties mailProperties,
            MailTemplateRenderer mailTemplateRenderer,
            SentMailRecorder sentMailRecorder) {
        super(mailProperties, mailTemplateRenderer);
        this.sentMailRecorder = sentMailRecorder;
    }

    @Override
    protected String deliver(String toEmail, String subject, String htmlBody) {
        sentMailRecorder.record(new SentMail(toEmail, subject, htmlBody));
        // No provider, so no provider identifier. The send log records the row as sent with a null
        // provider_message_id rather than inventing one.
        return null;
    }
}
