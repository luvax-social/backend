package com.app.modules.mail.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * Binds outbound mail configuration from {@code app.mail.*}.
 *
 * <p>Holds sender identity and application metadata used by every transactional email. Values
 * originate from environment variables via {@code application.yaml} placeholders; never inline
 * secrets here.
 */
@Component
@ConfigurationProperties(prefix = "app.mail")
@Getter
@Setter
public class MailProperties {

    private String fromAddress;
    private String fromName;
    private String appName;
    private String frontendBaseUrl;
    private String resetPasswordPath = "/reset-password";
    private String verifyEmailPath = "/verify-email";

    /**
     * Recipient domains this deployment is permitted to send to.
     *
     * <p>Null or absent means unrestricted, which is what a production deployment wants and what
     * every existing deployment already has. Set it to close the hazard that a database seeded with
     * fabricated addresses at real domains - gmail.com, outlook.com - is one ordinary moderation
     * action away from dispatching live mail to whoever actually holds those mailboxes. The
     * protection for that previously existed only for the seeding run itself, and not for the far
     * longer period during which the seeded rows are used.
     *
     * <p>Deliberately not defaulted to empty-means-block: a deployment that forgets the property
     * would then silently stop sending all mail, which is a worse failure than the one this closes.
     * The restriction is opt-in and recorded as configuration rather than left to a runbook.
     */
    private java.util.List<String> allowedRecipientDomains;
}
