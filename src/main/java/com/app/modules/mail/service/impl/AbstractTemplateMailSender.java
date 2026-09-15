package com.app.modules.mail.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.modules.mail.config.MailProperties;
import com.app.modules.mail.enums.MailTemplate;
import com.app.modules.mail.enums.ModerationMailTemplate;
import com.app.modules.mail.enums.SupportMailTemplate;
import com.app.modules.mail.service.MailSender;
import com.app.modules.mail.util.MailTemplateRenderer;

/**
 * Transport-neutral base for {@link MailSender} implementations.
 *
 * <p>Owns the single copy of every template variable map so a template gaining a variable is a
 * one-line change for all transports. Subclasses see only a recipient, a subject and a rendered
 * HTML body.
 */
public abstract class AbstractTemplateMailSender implements MailSender {

    private static final int EMAIL_VERIFICATION_EXPIRY_HOURS = 24;
    private static final int PASSWORD_RESET_EXPIRY_MINUTES = 15;

    private final MailProperties mailProperties;
    private final MailTemplateRenderer mailTemplateRenderer;

    protected AbstractTemplateMailSender(
            MailProperties mailProperties, MailTemplateRenderer mailTemplateRenderer) {
        this.mailProperties = mailProperties;
        this.mailTemplateRenderer = mailTemplateRenderer;
    }

    /**
     * Delivers an already rendered message through the concrete transport.
     *
     * <p>Implementations must translate a transport failure the provider may recover from into
     * {@code ApiErrorCode.SERVICE_UNAVAILABLE}, and one the provider has permanently refused into
     * {@code ApiErrorCode.MAIL_PERMANENTLY_REJECTED}, so a consumer's retry classifier can tell a
     * temporary outage from a message that will be rejected identically for ever. Implementations
     * must never log the recipient address or any raw token.
     *
     * <p>That obligation extends to text the implementation did not write. A provider's own error
     * body is not under this codebase's control and a validation error can echo the offending
     * address, so an implementation that carries provider text into a log line, an exception
     * message or a stored column must redact an address-shaped run first. The delivery row already
     * holds the recipient; nothing downstream needs a second copy of it.
     *
     * @param toEmail recipient email address
     * @param subject message subject line
     * @param htmlBody rendered HTML body
     * @return the provider's identifier for the accepted message, or null when the transport has
     *     none; the send log stores it so a delivery can be traced at the provider afterwards
     */
    protected abstract String deliver(String toEmail, String subject, String htmlBody);

    /**
     * Applies the recipient allowlist, then hands the message to the transport.
     *
     * <p>Every send routes through here rather than calling {@link #deliver} directly, so a lane
     * added later cannot bypass the check by construction. Campaign mail matters most: it addresses
     * many recipients at once.
     *
     * @param toEmail recipient email address
     * @param subject message subject line
     * @param htmlBody rendered HTML body
     * @return the provider's identifier for the accepted message, or null when it has none
     */
    private String dispatch(String toEmail, String subject, String htmlBody) {
        if (!recipientAllowed(toEmail)) {
            // Not logged with the address, per the contract above. The caller records the
            // suppression against the delivery row, which already holds the recipient.
            throw new AppException(ApiErrorCode.MAIL_RECIPIENT_NOT_ALLOWED);
        }
        return deliver(toEmail, subject, htmlBody);
    }

    /**
     * Whether this deployment may send to the given address.
     *
     * @param toEmail recipient email address
     * @return true when unrestricted, or when the address's domain is on the allowlist
     */
    private boolean recipientAllowed(String toEmail) {
        List<String> allowed = mailProperties.getAllowedRecipientDomains();
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        if (toEmail == null) {
            return false;
        }
        int at = toEmail.lastIndexOf('@');
        if (at < 0 || at == toEmail.length() - 1) {
            return false;
        }
        String domain = toEmail.substring(at + 1).toLowerCase(Locale.ROOT);
        return allowed.stream()
                .filter(entry -> entry != null && !entry.isBlank())
                .anyMatch(entry -> entry.trim().toLowerCase(Locale.ROOT).equals(domain));
    }

    /**
     * Builds the {@code From} header value shared by every transport.
     *
     * @return the configured sender name and address in {@code Name <address>} form
     */
    protected String fromHeader() {
        return mailProperties.getFromName() + " <" + mailProperties.getFromAddress() + ">";
    }

    @Override
    public void sendEmailVerification(String toEmail, String toName, String verificationUrl) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("toName", toName);
        variables.put("appName", mailProperties.getAppName());
        variables.put("verificationUrl", verificationUrl);
        variables.put("expiryHours", EMAIL_VERIFICATION_EXPIRY_HOURS);
        render(MailTemplate.EMAIL_VERIFICATION, variables, toEmail);
    }

    @Override
    public void sendPasswordReset(String toEmail, String toName, String resetUrl) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("toName", toName);
        variables.put("appName", mailProperties.getAppName());
        variables.put("resetUrl", resetUrl);
        variables.put("expiryMinutes", PASSWORD_RESET_EXPIRY_MINUTES);
        render(MailTemplate.PASSWORD_RESET, variables, toEmail);
    }

    @Override
    public void sendWelcome(String toEmail, String toName) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("toName", toName);
        variables.put("appName", mailProperties.getAppName());
        render(MailTemplate.WELCOME, variables, toEmail);
    }

    @Override
    public void sendPasswordChanged(String toEmail, String toName) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("toName", toName);
        variables.put("appName", mailProperties.getAppName());
        render(MailTemplate.PASSWORD_CHANGED, variables, toEmail);
    }

    @Override
    public void sendOAuthAccountNoPassword(String toEmail, String displayName) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("toName", displayName);
        variables.put("appName", mailProperties.getAppName());
        variables.put("frontendBaseUrl", mailProperties.getFrontendBaseUrl());
        render(MailTemplate.OAUTH_ACCOUNT_NO_PASSWORD, variables, toEmail);
    }

    private void render(MailTemplate template, Map<String, Object> variables, String toEmail) {
        String html = mailTemplateRenderer.render(template, variables);
        dispatch(toEmail, template.getDefaultSubject(), html);
    }

    /**
     * Renders and delivers one campaign mail.
     *
     * @param subject the administrator-authored subject
     * @param bodyHtml the already-sanitized body from {@code CampaignBodyRenderer}
     * @param unsubscribeUrl the recipient's opt-out link, supplied by the application
     * @param toEmail recipient email address
     * @return the provider's identifier for the accepted message, or null
     */
    public String sendCampaign(
            String subject, String bodyHtml, String unsubscribeUrl, String toEmail) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("subject", subject);
        variables.put("appName", mailProperties.getAppName());
        variables.put("bodyHtml", bodyHtml);
        variables.put("unsubscribeUrl", unsubscribeUrl);
        return dispatch(toEmail, subject, mailTemplateRenderer.renderCampaign(variables));
    }

    public String sendSupportConfirmation(Map<String, Object> variables, String toEmail) {
        String html =
                mailTemplateRenderer.render(SupportMailTemplate.CONFIRM_SUPPORT_REQUEST, variables);
        return dispatch(
                toEmail, SupportMailTemplate.CONFIRM_SUPPORT_REQUEST.getDefaultSubject(), html);
    }

    /**
     * Renders and delivers one moderation notice, returning the provider identifier.
     *
     * @param template the moderation template to render
     * @param variables Thymeleaf variables for that template
     * @param toEmail recipient email address
     * @return the provider's identifier for the accepted message, or null when the transport has
     *     none
     */
    public String sendModerationNotice(
            ModerationMailTemplate template, Map<String, Object> variables, String toEmail) {
        String html = mailTemplateRenderer.render(template, variables);
        return dispatch(toEmail, template.getDefaultSubject(), html);
    }
}
