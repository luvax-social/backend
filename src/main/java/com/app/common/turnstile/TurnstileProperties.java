package com.app.common.turnstile;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Binds the Cloudflare Turnstile configuration from {@code app.turnstile.*}.
 *
 * <p>The secret defaults to empty in the base profile and to Cloudflare's published always-passes
 * test key in {@code dev}. An empty secret produces {@link TurnstileOutcome#UNAVAILABLE}, which the
 * public support form refuses on, so a deployment that forgets to configure the real key fails that
 * form closed instead of silently removing the control.
 */
@ConfigurationProperties(prefix = "app.turnstile")
@Getter
@Setter
public class TurnstileProperties {

    private String secretKey = "";

    private String verifyUrl = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    /** Short: Cloudflare is on the critical path of a user-facing submit. */
    private Duration connectTimeout = Duration.ofSeconds(3);

    private Duration readTimeout = Duration.ofSeconds(5);

    private Auth auth = new Auth();

    /** Switches that apply to the authentication and report surfaces only. */
    @Getter
    @Setter
    public static class Auth {

        /**
         * Kill switch for the auth and report surfaces.
         *
         * <p>Exists so an operator can restore authentication without a redeploy if Cloudflare
         * breaks in a way the fail-open policy does not cover - a provider that answers quickly and
         * wrongly with {@code success: false}, for instance, which reads as a legitimate rejection
         * and no amount of failure handling will distinguish. It does not govern the public support
         * form, which stays enabled unconditionally.
         */
        private boolean enabled = true;
    }
}
