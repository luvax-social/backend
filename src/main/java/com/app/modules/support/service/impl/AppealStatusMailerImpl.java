package com.app.modules.support.service.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import com.app.modules.auth.repository.UserCredentialRepository;
import com.app.modules.mail.config.MailProperties;
import com.app.modules.mail.service.impl.AbstractTemplateMailSender;
import com.app.modules.mail.util.MailSuppression;
import com.app.modules.support.service.AppealStatusMailer;

@Service
public class AppealStatusMailerImpl implements AppealStatusMailer {

    private static final Logger log = LoggerFactory.getLogger(AppealStatusMailerImpl.class);

    private static final String STATUS_PATH = "/support/appeal/status";

    private final AbstractTemplateMailSender mailSender;
    private final UserCredentialRepository userCredentialRepository;
    private final MailProperties mailProperties;

    public AppealStatusMailerImpl(
            AbstractTemplateMailSender mailSender,
            UserCredentialRepository userCredentialRepository,
            MailProperties mailProperties) {
        this.mailSender = mailSender;
        this.userCredentialRepository = userCredentialRepository;
        this.mailProperties = mailProperties;
    }

    @Override
    public void sendStatusLink(UUID userId, String contactEmail, String statusToken) {
        // The same refusal ModerationMailEventHandler and the recovery path both apply. The
        // platform has never proved an unverified address belongs to the account, so mailing it
        // would tell whoever actually owns that mailbox both that the address is registered here
        // and that a moderation decision concerns its supposed owner.
        if (!emailVerified(userId)) {
            return;
        }
        Map<String, Object> variables = new HashMap<>();
        variables.put("toName", "there");
        variables.put("appName", mailProperties.getAppName());
        variables.put("statusUrl", statusUrl(statusToken));
        try {
            mailSender.sendAppealStatusLink(variables, contactEmail);
        } catch (RuntimeException ex) {
            // Swallowed rather than rethrown. The appeal is already written and its token already
            // spent by the time this runs, so letting a transport failure escape would roll back a
            // submission the appellant can no longer repeat: the single-use link that authorised it
            // is gone. A lost mail costs them the convenience of the link; a lost appeal costs them
            // the appeal.
            //
            // Logs neither the address nor the token.
            if (MailSuppression.isRecipientSuppressed(ex)) {
                log.info("Suppressed an appeal status link: recipient outside the allowlist");
                return;
            }
            log.warn("Failed to send an appeal status link: {}", ex.getMessage());
        }
    }

    private boolean emailVerified(UUID userId) {
        return userCredentialRepository
                .findByUserId(userId)
                .map(credential -> credential.isEmailVerified())
                // An account with no credential row signed in through OAuth2 only. The provider
                // verified the address before it ever reached this system.
                .orElse(true);
    }

    private String statusUrl(String rawToken) {
        return UriComponentsBuilder.fromUriString(mailProperties.getFrontendBaseUrl())
                .path(STATUS_PATH)
                .queryParam("token", rawToken)
                .build()
                .toUriString();
    }
}
