package com.app.modules.support.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.modules.auth.entity.UserCredential;
import com.app.modules.auth.repository.UserCredentialRepository;
import com.app.modules.mail.config.MailProperties;
import com.app.modules.mail.service.impl.AbstractTemplateMailSender;

@ExtendWith(MockitoExtension.class)
class AppealStatusMailerImplTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String EMAIL = "appellant@example.com";
    private static final String TOKEN = "status-token";

    @Mock private AbstractTemplateMailSender mailSender;
    @Mock private UserCredentialRepository userCredentialRepository;
    @Mock private MailProperties mailProperties;

    private AppealStatusMailerImpl mailer;

    @BeforeEach
    void setUp() {
        mailer = new AppealStatusMailerImpl(mailSender, userCredentialRepository, mailProperties);
        lenient().when(mailProperties.getFrontendBaseUrl()).thenReturn("https://luvax.test");
        lenient().when(mailProperties.getAppName()).thenReturn("Luvax");
    }

    @Test
    void sendStatusLink_verifiedAddress_mailsTheLinkCarryingTheStatusToken() {
        stubVerifiedEmail(true);

        mailer.sendStatusLink(USER_ID, EMAIL, TOKEN);

        ArgumentCaptor<Map<String, Object>> variables = captureVariables();
        assertThat(variables.getValue())
                .containsEntry(
                        "statusUrl", "https://luvax.test/support/appeal/status?token=" + TOKEN);
    }

    // The screen and the mail must resolve to the same address, or a reader who keeps the mail
    // holds a link the appeal was never reachable through. The token is appended verbatim, which
    // is safe because SupportTokenServiceImpl mints it with the URL-safe Base64 alphabet: it
    // carries no character a query parameter would have to escape.
    @Test
    void sendStatusLink_buildsTheAddressTheStatusScreenAnswersOn() {
        stubVerifiedEmail(true);

        mailer.sendStatusLink(USER_ID, EMAIL, "Ab9-_xY");

        ArgumentCaptor<Map<String, Object>> variables = captureVariables();
        assertThat(String.valueOf(variables.getValue().get("statusUrl")))
                .isEqualTo("https://luvax.test/support/appeal/status?token=Ab9-_xY");
    }

    // The same refusal ModerationMailEventHandler and the recovery path apply, and for the same
    // reason: the platform has never proved an unverified address belongs to the account.
    @Test
    void sendStatusLink_unverifiedAddress_mailsNothing() {
        stubVerifiedEmail(false);

        mailer.sendStatusLink(USER_ID, EMAIL, TOKEN);

        verify(mailSender, never()).sendAppealStatusLink(anyMap(), anyString());
    }

    @Test
    void sendStatusLink_oauthAccountWithNoCredentialRow_isTreatedAsVerified() {
        when(userCredentialRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        mailer.sendStatusLink(USER_ID, EMAIL, TOKEN);

        verify(mailSender).sendAppealStatusLink(anyMap(), eq(EMAIL));
    }

    // The appeal is written and its single-use token already spent by the time this runs. Letting
    // a transport failure escape would roll back a submission the appellant cannot repeat.
    @Test
    void sendStatusLink_transportFailure_isSwallowedSoTheAppealStands() {
        stubVerifiedEmail(true);
        when(mailSender.sendAppealStatusLink(anyMap(), anyString()))
                .thenThrow(new IllegalStateException("transport refused"));

        assertThatCode(() -> mailer.sendStatusLink(USER_ID, EMAIL, TOKEN))
                .doesNotThrowAnyException();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> captureVariables() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(mailSender).sendAppealStatusLink(captor.capture(), eq(EMAIL));
        return captor;
    }

    private void stubVerifiedEmail(boolean verified) {
        UserCredential credential = new UserCredential();
        credential.setEmailVerified(verified);
        when(userCredentialRepository.findByUserId(USER_ID)).thenReturn(Optional.of(credential));
    }
}
