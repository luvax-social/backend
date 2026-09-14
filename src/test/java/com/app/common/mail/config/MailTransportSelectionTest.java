package com.app.common.mail.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.app.modules.mail.config.MailProperties;
import com.app.modules.mail.config.MailTransportGuard;
import com.app.modules.mail.config.noop.SentMailRecorder;
import com.app.modules.mail.config.resend.ResendProperties;
import com.app.modules.mail.service.MailSender;
import com.app.modules.mail.service.impl.MailServiceImpl;
import com.app.modules.mail.service.impl.NoopMailSender;
import com.app.modules.mail.service.impl.ResendMailSender;
import com.app.modules.mail.util.MailTemplateRenderer;
import com.resend.Resend;

class MailTransportSelectionTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withBean(MailProperties.class)
                    // ResendMailSender takes this in its constructor. Without it the context failed
                    // to start on every transport=resend case, so the two assertions below never
                    // ran and reported a missing-bean error instead of what they were checking.
                    .withBean(ResendProperties.class)
                    .withBean(MailTemplateRenderer.class, () -> mock(MailTemplateRenderer.class))
                    .withBean(Resend.class, () -> mock(Resend.class))
                    .withBean(SentMailRecorder.class, SentMailRecorder::new)
                    .withUserConfiguration(
                            MailTransportGuard.class,
                            ResendMailSender.class,
                            NoopMailSender.class,
                            MailServiceImpl.class);

    @Test
    void resendTransport_registersOnlyTheResendSender() {
        runner.withPropertyValues("spring.profiles.active=dev", "app.mail.transport=resend")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(MailSender.class);
                            assertThat(context).hasSingleBean(ResendMailSender.class);
                            assertThat(context).doesNotHaveBean(NoopMailSender.class);
                        });
    }

    @Test
    void noopTransport_registersOnlyTheNoopSender() {
        runner.withPropertyValues("spring.profiles.active=dev", "app.mail.transport=noop")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(MailSender.class);
                            assertThat(context).hasSingleBean(NoopMailSender.class);
                            assertThat(context).doesNotHaveBean(ResendMailSender.class);
                        });
    }

    @Test
    void absentTransport_failsNamingThePropertyAndTheAcceptedValues() {
        runner.withPropertyValues("spring.profiles.active=dev")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            // Asserting only that startup failed would pass on the defect being
                            // fixed here: absence used to reach Spring's generic "no qualifying
                            // bean of type MailSender" message, which gives an operator no reason
                            // to suspect the mail transport property.
                            assertThat(context.getStartupFailure())
                                    .rootCause()
                                    .isInstanceOf(IllegalStateException.class)
                                    .hasMessageContaining("app.mail.transport")
                                    .hasMessageContaining("resend")
                                    .hasMessageContaining("noop");
                        });
    }

    @Test
    void unrecognisedTransport_failsNamingTheValueItFoundAndTheAcceptedValues() {
        runner.withPropertyValues("spring.profiles.active=dev", "app.mail.transport=sendgrid")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .rootCause()
                                    .isInstanceOf(IllegalStateException.class)
                                    .hasMessageContaining("app.mail.transport")
                                    .hasMessageContaining("sendgrid")
                                    .hasMessageContaining("resend")
                                    .hasMessageContaining("noop");
                        });
    }

    @Test
    void noopTransportOutsideDevelopment_refusesToStartAndNamesTransportAndProfiles() {
        runner.withPropertyValues("spring.profiles.active=prod", "app.mail.transport=noop")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .rootCause()
                                    .isInstanceOf(IllegalStateException.class)
                                    .hasMessageContaining("noop")
                                    .hasMessageContaining("[prod]");
                        });
    }

    @Test
    void resendTransportOutsideDevelopment_startsNormally() {
        runner.withPropertyValues("spring.profiles.active=prod", "app.mail.transport=resend")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(ResendMailSender.class);
                        });
    }

    @Test
    void noopTransportWithNoActiveProfile_refusesToStart() {
        runner.withPropertyValues("app.mail.transport=noop")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .rootCause()
                                    .isInstanceOf(IllegalStateException.class)
                                    .hasMessageContaining("noop");
                        });
    }
}
