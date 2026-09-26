package com.app.common.turnstile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;

@ExtendWith(MockitoExtension.class)
class AuthTurnstileGuardTest {

    @Mock private TurnstileVerifier verifier;

    private TurnstileProperties properties;
    private AuthTurnstileGuard guard;

    @BeforeEach
    void setUp() {
        properties = new TurnstileProperties();
        guard = new AuthTurnstileGuard(verifier, properties);
    }

    @Test
    void require_verified_allowsTheRequest() {
        when(verifier.verify("token", "1.2.3.4", TurnstileSurface.LOGIN))
                .thenReturn(TurnstileOutcome.VERIFIED);

        assertThatCode(() -> guard.require("token", "1.2.3.4", TurnstileSurface.LOGIN))
                .doesNotThrowAnyException();
    }

    @Test
    void require_rejected_refusesWithTheCaptchaCode() {
        when(verifier.verify("bad", null, TurnstileSurface.LOGIN))
                .thenReturn(TurnstileOutcome.REJECTED);

        assertThatThrownBy(() -> guard.require("bad", null, TurnstileSurface.LOGIN))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_CAPTCHA_FAILED);
    }

    @Test
    void require_unavailable_failsOpen() {
        // The asymmetry with the public support form, which refuses on this outcome. Every
        // endpoint behind this guard is already covered by a per-caller rate-limit rule, so a
        // Cloudflare outage must not become an authentication outage.
        when(verifier.verify("token", null, TurnstileSurface.REGISTER))
                .thenReturn(TurnstileOutcome.UNAVAILABLE);

        assertThatCode(() -> guard.require("token", null, TurnstileSurface.REGISTER))
                .doesNotThrowAnyException();
    }

    @Test
    void require_killSwitchDisabled_skipsVerificationAndAcceptsABlankToken() {
        properties.getAuth().setEnabled(false);

        assertThatCode(() -> guard.require(null, null, TurnstileSurface.LOGIN))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.require("", null, TurnstileSurface.RESET_PASSWORD))
                .doesNotThrowAnyException();

        verify(verifier, never()).verify(any(), any(), any());
    }

    @Test
    void killSwitch_defaultsToEnabled() {
        assertThat(new TurnstileProperties().getAuth().isEnabled()).isTrue();
    }
}
