package com.app.modules.support.service.impl;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import com.app.modules.mail.config.MailProperties;
import com.app.modules.mail.service.impl.AbstractTemplateMailSender;
import com.app.modules.mail.util.MailSuppression;
import com.app.modules.support.service.SupportConfirmationMailer;

@Service
public class SupportConfirmationMailerImpl implements SupportConfirmationMailer {

    private static final Logger log = LoggerFactory.getLogger(SupportConfirmationMailerImpl.class);

    private static final String CONFIRM_PATH = "/support/confirm";

    private final AbstractTemplateMailSender mailSender;
    private final MailProperties mailProperties;

    public SupportConfirmationMailerImpl(
            AbstractTemplateMailSender mailSender, MailProperties mailProperties) {
        this.mailSender = mailSender;
        this.mailProperties = mailProperties;
    }

    @Override
    public void sendConfirmation(String contactEmail, String rawToken) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("toName", "there");
        variables.put("appName", mailProperties.getAppName());
        variables.put("confirmUrl", buildConfirmUrl(rawToken));
        try {
            mailSender.sendSupportConfirmation(variables, contactEmail);
        } catch (RuntimeException ex) {
            // Logs neither the address nor the token. The message is the sender's, and the
            // transport redacts address-shaped runs out of provider text before it gets here, so
            // this holds for a string this class does not itself compose. A failure here leaves
            // the ticket in pending_confirmation, where it is invisible to staff and expires with
            // its token, so the safe outcome is simply that the submitter resubmits.
            if (MailSuppression.isRecipientSuppressed(ex)) {
                // Classified like every other lane, so a deployment that was configured never to
                // mail this address does not read as a provider failure in the log.
                log.info(
                        "Suppressed a support confirmation: recipient outside the configured"
                                + " allowlist");
                return;
            }
            log.warn("Failed to send a support confirmation: {}", ex.getMessage());
        }
    }

    private String buildConfirmUrl(String rawToken) {
        return UriComponentsBuilder.fromUriString(mailProperties.getFrontendBaseUrl())
                .path(CONFIRM_PATH)
                .queryParam("token", rawToken)
                .build()
                .toUriString();
    }
}
