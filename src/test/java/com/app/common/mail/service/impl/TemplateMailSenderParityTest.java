package com.app.common.mail.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.modules.mail.config.MailProperties;
import com.app.modules.mail.config.noop.SentMail;
import com.app.modules.mail.config.noop.SentMailRecorder;
import com.app.modules.mail.config.resend.ResendProperties;
import com.app.modules.mail.enums.MailTemplate;
import com.app.modules.mail.service.MailSender;
import com.app.modules.mail.service.impl.NoopMailSender;
import com.app.modules.mail.service.impl.ResendMailSender;
import com.app.modules.mail.util.MailTemplateRenderer;
import com.resend.Resend;
import com.resend.services.emails.Emails;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;

/**
 * Pins the two transports to the shared rendering layer: whatever a template needs, both transports
 * ask for it identically and deliver the identical result.
 */
@ExtendWith(MockitoExtension.class)
class TemplateMailSenderParityTest {

    private static final String FROM_ADDRESS = "noreply@example.com";
    private static final String FROM_NAME = "Social";
    private static final String TO_EMAIL = "user@example.com";
    private static final String TO_NAME = "Jane Doe";
    private static final String VERIFICATION_URL = "https://app.local/verify?t=xyz";
    private static final String RESET_URL = "https://app.local/reset?t=abc";
    private static final int TEMPLATE_COUNT = 5;

    @Mock private Resend resend;

    @Mock private Emails emails;

    @Mock private MailTemplateRenderer mailTemplateRenderer;

    private ResendMailSender resendMailSender;
    private NoopMailSender noopMailSender;
    private SentMailRecorder sentMailRecorder;

    @BeforeEach
    void setUp() {
        MailProperties properties = new MailProperties();
        properties.setFromAddress(FROM_ADDRESS);
        properties.setFromName(FROM_NAME);
        properties.setAppName("Social");
        properties.setFrontendBaseUrl("http://localhost:3000");
        sentMailRecorder = new SentMailRecorder();
        resendMailSender =
                new ResendMailSender(resend, resendProperties(), properties, mailTemplateRenderer);
        noopMailSender = new NoopMailSender(properties, mailTemplateRenderer, sentMailRecorder);
        when(resend.emails()).thenReturn(emails);
        when(mailTemplateRenderer.render(any(MailTemplate.class), any()))
                .thenAnswer(invocation -> "<html>" + invocation.getArgument(0) + "</html>");
    }

    @Test
    @SuppressWarnings("unchecked")
    void bothTransportsRequestTheSameTemplatesWithTheSameVariables() throws Exception {
        when(emails.send(any(CreateEmailOptions.class)))
                .thenReturn(mock(CreateEmailResponse.class));

        sendEveryMessage(resendMailSender);
        sendEveryMessage(noopMailSender);

        ArgumentCaptor<MailTemplate> templateCaptor = ArgumentCaptor.forClass(MailTemplate.class);
        ArgumentCaptor<Map<String, Object>> varCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mailTemplateRenderer, times(TEMPLATE_COUNT * 2))
                .render(templateCaptor.capture(), varCaptor.capture());

        List<MailTemplate> templates = templateCaptor.getAllValues();
        List<Map<String, Object>> variables = varCaptor.getAllValues();
        assertThat(templates.subList(0, TEMPLATE_COUNT))
                .isEqualTo(templates.subList(TEMPLATE_COUNT, TEMPLATE_COUNT * 2));
        assertThat(variables.subList(0, TEMPLATE_COUNT))
                .isEqualTo(variables.subList(TEMPLATE_COUNT, TEMPLATE_COUNT * 2));
    }

    @Test
    void bothTransportsDeliverTheSameSenderRecipientSubjectAndBody() throws Exception {
        when(emails.send(any(CreateEmailOptions.class)))
                .thenReturn(mock(CreateEmailResponse.class));

        sendEveryMessage(resendMailSender);
        sendEveryMessage(noopMailSender);

        ArgumentCaptor<CreateEmailOptions> resendCaptor =
                ArgumentCaptor.forClass(CreateEmailOptions.class);
        verify(emails, times(TEMPLATE_COUNT)).send(resendCaptor.capture());

        List<CreateEmailOptions> viaResend = resendCaptor.getAllValues();
        List<SentMail> viaNoop = sentMailRecorder.sent();
        assertThat(viaNoop).hasSize(TEMPLATE_COUNT);
        for (int i = 0; i < TEMPLATE_COUNT; i++) {
            CreateEmailOptions expected = viaResend.get(i);
            SentMail actual = viaNoop.get(i);
            assertThat(FROM_NAME + " <" + FROM_ADDRESS + ">").isEqualTo(expected.getFrom());
            assertThat(actual.toEmail()).isEqualTo(expected.getTo().getFirst());
            assertThat(actual.subject()).isEqualTo(expected.getSubject());
            assertThat(actual.htmlBody()).isEqualTo(expected.getHtml());
        }
    }

    private void sendEveryMessage(MailSender mailSender) {
        mailSender.sendEmailVerification(TO_EMAIL, TO_NAME, VERIFICATION_URL);
        mailSender.sendPasswordReset(TO_EMAIL, TO_NAME, RESET_URL);
        mailSender.sendWelcome(TO_EMAIL, TO_NAME);
        mailSender.sendPasswordChanged(TO_EMAIL, TO_NAME);
        mailSender.sendOAuthAccountNoPassword(TO_EMAIL, TO_NAME);
    }

    private static ResendProperties resendProperties() {
        ResendProperties resendProperties = new ResendProperties();
        resendProperties.setApiKey("test-key");
        return resendProperties;
    }
}
