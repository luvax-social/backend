package com.app.modules.mail.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.modules.mail.config.MailProperties;
import com.app.modules.mail.enums.MailTemplate;
import com.app.modules.mail.util.MailTemplateRenderer;

/**
 * Covers the recipient allowlist that every send routes through.
 *
 * <p>The hazard it closes: the seeded dataset carries fabricated addresses at gmail.com and
 * outlook.com, which are real deliverable domains, and an ordinary moderation action against a
 * seeded account dispatched live mail to them. The existing protection disabled the mail consumer
 * during seeding only, and not for the period during which the seeded rows are used.
 */
class MailRecipientAllowlistTest {

    /**
     * Records what reached the transport, so a suppressed send is distinguishable from a sent one.
     */
    private static final class RecordingSender extends AbstractTemplateMailSender {

        private String deliveredTo;

        RecordingSender(MailProperties properties) {
            // Rendering is irrelevant here and pulling in the real Thymeleaf templates would make
            // this a test of the template files rather than of the guard.
            super(properties, stubRenderer());
        }

        @Override
        protected String deliver(String toEmail, String subject, String htmlBody) {
            this.deliveredTo = toEmail;
            return "provider-id";
        }
    }

    private static MailTemplateRenderer stubRenderer() {
        MailTemplateRenderer renderer = org.mockito.Mockito.mock(MailTemplateRenderer.class);
        org.mockito.Mockito.lenient()
                .when(renderer.render(org.mockito.ArgumentMatchers.any(MailTemplate.class), any()))
                .thenReturn("<html></html>");
        org.mockito.Mockito.lenient()
                .when(
                        renderer.render(
                                org.mockito.ArgumentMatchers.any(
                                        com.app.modules.mail.enums.ModerationMailTemplate.class),
                                any()))
                .thenReturn("<html></html>");
        org.mockito.Mockito.lenient()
                .when(
                        renderer.render(
                                org.mockito.ArgumentMatchers.any(
                                        com.app.modules.mail.enums.SupportMailTemplate.class),
                                any()))
                .thenReturn("<html></html>");
        return renderer;
    }

    private static MailProperties properties(List<String> allowed) {
        MailProperties properties = new MailProperties();
        properties.setFromAddress("noreply@luvax.test");
        properties.setFromName("Luvax");
        properties.setAppName("Luvax");
        properties.setFrontendBaseUrl("http://localhost:5173");
        properties.setAllowedRecipientDomains(allowed);
        return properties;
    }

    @Test
    void allowlistUnset_sendsToAnyRecipient() {
        // Absent means unrestricted. A production deployment that never sets this property must
        // keep sending; defaulting to block would turn a forgotten property into a mail outage.
        RecordingSender sender = new RecordingSender(properties(null));

        sender.sendEmailVerification("someone@gmail.com", "Someone", "http://localhost/verify");

        assertThat(sender.deliveredTo).isEqualTo("someone@gmail.com");
    }

    @Test
    void allowlistSet_recipientOnIt_stillSends() {
        RecordingSender sender = new RecordingSender(properties(List.of("example.invalid")));

        sender.sendEmailVerification("dev@example.invalid", "Dev", "http://localhost/verify");

        assertThat(sender.deliveredTo).isEqualTo("dev@example.invalid");
    }

    @ParameterizedTest
    @ValueSource(strings = {"tyler.sysadmin@gmail.com", "leo.embedded@outlook.com"})
    void allowlistSet_seededRealDomain_neverReachesTheTransport(String seededAddress) {
        // The two domains the audit actually dispatched live mail to.
        RecordingSender sender = new RecordingSender(properties(List.of("example.invalid")));

        assertThatThrownBy(
                        () ->
                                sender.sendEmailVerification(
                                        seededAddress, "Seeded", "http://localhost/verify"))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.MAIL_RECIPIENT_NOT_ALLOWED);
        assertThat(sender.deliveredTo).isNull();
    }

    @Test
    void allowlistSet_matchIsCaseInsensitive() {
        RecordingSender sender = new RecordingSender(properties(List.of("Example.Invalid")));

        sender.sendEmailVerification("dev@EXAMPLE.invalid", "Dev", "http://localhost/verify");

        assertThat(sender.deliveredTo).isEqualTo("dev@EXAMPLE.invalid");
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-at-sign", "trailing@", "@leading"})
    void allowlistSet_malformedAddress_isRefusedRatherThanGuessedAt(String malformed) {
        RecordingSender sender = new RecordingSender(properties(List.of("example.invalid")));

        assertThatThrownBy(
                        () ->
                                sender.sendEmailVerification(
                                        malformed, "Nobody", "http://localhost/verify"))
                .isInstanceOf(AppException.class);
        assertThat(sender.deliveredTo).isNull();
    }

    @Test
    void moderationNoticeLane_isGuardedToo() {
        // The auth and moderation lanes share the transport and were equally exposed; the guard
        // sits on the one path all three lanes route through, so none can bypass it.
        RecordingSender sender = new RecordingSender(properties(List.of("example.invalid")));

        assertThatThrownBy(
                        () ->
                                sender.sendModerationNotice(
                                        com.app.modules.mail.enums.ModerationMailTemplate
                                                .ACCOUNT_WARNING,
                                        new java.util.HashMap<>(),
                                        "tyler.sysadmin@gmail.com"))
                .isInstanceOf(AppException.class);
        assertThat(sender.deliveredTo).isNull();
    }

    @Test
    void mailTemplateEnumStillResolves() {
        // Guards against the fixture drifting away from the enum the sender renders from.
        assertThat(MailTemplate.EMAIL_VERIFICATION.getDefaultSubject()).isNotBlank();
    }
}
