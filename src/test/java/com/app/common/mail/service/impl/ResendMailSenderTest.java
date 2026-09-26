package com.app.common.mail.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.modules.mail.config.MailProperties;
import com.app.modules.mail.config.resend.ResendProperties;
import com.app.modules.mail.enums.MailTemplate;
import com.app.modules.mail.service.impl.ResendMailSender;
import com.app.modules.mail.util.MailTemplateRenderer;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.Emails;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@ExtendWith(MockitoExtension.class)
class ResendMailSenderTest {

    private static final String FROM_ADDRESS = "noreply@example.com";
    private static final String FROM_NAME = "Social";
    private static final String APP_NAME = "Social";
    private static final String TO_EMAIL = "user@example.com";
    private static final String TO_NAME = "Jane Doe";
    private static final String RENDERED_HTML = "<html>rendered</html>";

    @Mock private Resend resend;

    @Mock private Emails emails;

    @Mock private MailTemplateRenderer mailTemplateRenderer;

    private ResendMailSender service;

    @BeforeEach
    void setUp() {
        MailProperties properties = new MailProperties();
        properties.setFromAddress(FROM_ADDRESS);
        properties.setFromName(FROM_NAME);
        properties.setAppName(APP_NAME);
        properties.setFrontendBaseUrl("http://localhost:3000");
        service =
                new ResendMailSender(resend, resendProperties(), properties, mailTemplateRenderer);
        when(resend.emails()).thenReturn(emails);
        when(mailTemplateRenderer.render(any(MailTemplate.class), any())).thenReturn(RENDERED_HTML);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendEmailVerification_assemblesCorrectVariableMapAndDispatchesEmail() throws Exception {
        when(emails.send(any(CreateEmailOptions.class)))
                .thenReturn(mock(CreateEmailResponse.class));

        service.sendEmailVerification(TO_EMAIL, TO_NAME, "https://app.local/verify?t=xyz");

        ArgumentCaptor<Map<String, Object>> varCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mailTemplateRenderer)
                .render(eq(MailTemplate.EMAIL_VERIFICATION), varCaptor.capture());
        Map<String, Object> vars = varCaptor.getValue();
        assertThat(vars)
                .containsEntry("toName", TO_NAME)
                .containsEntry("appName", APP_NAME)
                .containsEntry("verificationUrl", "https://app.local/verify?t=xyz")
                .containsKey("expiryHours");

        ArgumentCaptor<CreateEmailOptions> optionsCaptor =
                ArgumentCaptor.forClass(CreateEmailOptions.class);
        verify(emails).send(optionsCaptor.capture());
        CreateEmailOptions opts = optionsCaptor.getValue();
        assertThat(opts.getFrom()).isEqualTo(FROM_NAME + " <" + FROM_ADDRESS + ">");
        assertThat(opts.getTo()).containsExactly(TO_EMAIL);
        assertThat(opts.getSubject()).isEqualTo("Verify your email address");
        assertThat(opts.getHtml()).isEqualTo(RENDERED_HTML);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendWelcome_doesNotIncludeUrlRelatedVariables() throws Exception {
        when(emails.send(any(CreateEmailOptions.class)))
                .thenReturn(mock(CreateEmailResponse.class));

        service.sendWelcome(TO_EMAIL, TO_NAME);

        ArgumentCaptor<Map<String, Object>> varCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mailTemplateRenderer).render(eq(MailTemplate.WELCOME), varCaptor.capture());
        Map<String, Object> vars = varCaptor.getValue();
        assertThat(vars).containsOnlyKeys("toName", "appName");
    }

    @Test
    void sendWelcome_success_logDoesNotContainRawEmailAddress() throws Exception {
        when(emails.send(any(CreateEmailOptions.class)))
                .thenReturn(mock(CreateEmailResponse.class));

        Logger logger = (Logger) LoggerFactory.getLogger(ResendMailSender.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.sendWelcome(TO_EMAIL, TO_NAME);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .isNotEmpty()
                .noneMatch(event -> event.getFormattedMessage().contains(TO_EMAIL));
    }

    @Test
    void sendWelcome_failure_logDoesNotContainRawEmailAddress() throws Exception {
        doThrow(new ResendException("upstream failure"))
                .when(emails)
                .send(any(CreateEmailOptions.class));

        Logger logger = (Logger) LoggerFactory.getLogger(ResendMailSender.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> service.sendWelcome(TO_EMAIL, TO_NAME))
                    .isInstanceOf(AppException.class);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .isNotEmpty()
                .noneMatch(event -> event.getFormattedMessage().contains(TO_EMAIL));
    }

    @Test
    void send_whenResendThrows_rethrowsAsServiceUnavailable() throws Exception {
        doThrow(new ResendException("upstream failure"))
                .when(emails)
                .send(any(CreateEmailOptions.class));

        assertThatThrownBy(() -> service.sendWelcome(TO_EMAIL, TO_NAME))
                .isInstanceOf(AppException.class)
                .satisfies(
                        ex ->
                                assertThat(((AppException) ex).getErrorCode())
                                        .isEqualTo(ApiErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendOAuthAccountNoPassword_assemblesCorrectVariableMapAndDispatchesEmail()
            throws Exception {
        when(emails.send(any(CreateEmailOptions.class)))
                .thenReturn(mock(CreateEmailResponse.class));

        service.sendOAuthAccountNoPassword(TO_EMAIL, TO_NAME);

        ArgumentCaptor<Map<String, Object>> varCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mailTemplateRenderer)
                .render(eq(MailTemplate.OAUTH_ACCOUNT_NO_PASSWORD), varCaptor.capture());
        Map<String, Object> vars = varCaptor.getValue();
        assertThat(vars)
                .containsEntry("toName", TO_NAME)
                .containsEntry("appName", APP_NAME)
                .containsEntry("frontendBaseUrl", "http://localhost:3000");

        ArgumentCaptor<CreateEmailOptions> optionsCaptor =
                ArgumentCaptor.forClass(CreateEmailOptions.class);
        verify(emails).send(optionsCaptor.capture());
        CreateEmailOptions opts = optionsCaptor.getValue();
        assertThat(opts.getFrom()).isEqualTo(FROM_NAME + " <" + FROM_ADDRESS + ">");
        assertThat(opts.getTo()).containsExactly(TO_EMAIL);
        assertThat(opts.getSubject()).isEqualTo("Sign in with Google to access your account");
        assertThat(opts.getHtml()).isEqualTo(RENDERED_HTML);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendPasswordChanged_assemblesCorrectVariableMapAndDispatchesEmail() throws Exception {
        when(emails.send(any(CreateEmailOptions.class)))
                .thenReturn(mock(CreateEmailResponse.class));

        service.sendPasswordChanged(TO_EMAIL, TO_NAME);

        ArgumentCaptor<Map<String, Object>> varCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mailTemplateRenderer).render(eq(MailTemplate.PASSWORD_CHANGED), varCaptor.capture());
        Map<String, Object> vars = varCaptor.getValue();
        assertThat(vars).containsEntry("toName", TO_NAME).containsEntry("appName", APP_NAME);

        ArgumentCaptor<CreateEmailOptions> optionsCaptor =
                ArgumentCaptor.forClass(CreateEmailOptions.class);
        verify(emails).send(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getTo()).containsExactly(TO_EMAIL);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendPasswordReset_assemblesCorrectVariableMapAndDispatchesEmail() throws Exception {
        when(emails.send(any(CreateEmailOptions.class)))
                .thenReturn(mock(CreateEmailResponse.class));

        service.sendPasswordReset(TO_EMAIL, TO_NAME, "https://app.local/reset?t=abc");

        ArgumentCaptor<Map<String, Object>> varCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mailTemplateRenderer).render(eq(MailTemplate.PASSWORD_RESET), varCaptor.capture());
        Map<String, Object> vars = varCaptor.getValue();
        assertThat(vars)
                .containsEntry("toName", TO_NAME)
                .containsEntry("appName", APP_NAME)
                .containsEntry("resetUrl", "https://app.local/reset?t=abc")
                .containsKey("expiryMinutes");
    }

    private static ResendProperties resendProperties() {
        ResendProperties resendProperties = new ResendProperties();
        resendProperties.setApiKey("test-key");
        return resendProperties;
    }
}
