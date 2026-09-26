package com.app.modules.support.service.impl;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.app.modules.mail.config.MailProperties;
import com.app.modules.mail.service.impl.AbstractTemplateMailSender;
import com.app.modules.mail.util.MailSuppression;
import com.app.modules.support.service.AppealLinkMailer;

@Service
public class AppealLinkMailerImpl implements AppealLinkMailer {

    private static final Logger log = LoggerFactory.getLogger(AppealLinkMailerImpl.class);

    private final AbstractTemplateMailSender mailSender;
    private final MailProperties mailProperties;

    public AppealLinkMailerImpl(
            AbstractTemplateMailSender mailSender, MailProperties mailProperties) {
        this.mailSender = mailSender;
        this.mailProperties = mailProperties;
    }

    @Override
    public void sendAppealLink(String contactEmail, String appealUrl) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("toName", "there");
        variables.put("appName", mailProperties.getAppName());
        variables.put("appealUrl", appealUrl);
        try {
            mailSender.sendAppealLink(variables, contactEmail);
        } catch (RuntimeException ex) {
            // Swallowed rather than rethrown, and that is a security property here rather than
            // leniency. The caller is anonymous and its response must be identical whether or not
            // the address matched anything; an exception escaping this method would surface as a
            // different status for an address that exists, which is the enumeration channel the
            // whole endpoint is built to close.
            //
            // Logs neither the address nor the token.
            if (MailSuppression.isRecipientSuppressed(ex)) {
                log.info("Suppressed an appeal link: recipient outside the configured allowlist");
                return;
            }
            log.warn("Failed to send an appeal link: {}", ex.getMessage());
        }
    }
}
