package com.app.common.turnstile;

import org.springframework.stereotype.Component;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;

/**
 * Applies the Turnstile policy shared by the authentication and report surfaces.
 *
 * <p><strong>This guard fails open.</strong> A {@link TurnstileOutcome#REJECTED} refuses the
 * request; a {@link TurnstileOutcome#UNAVAILABLE} lets it through.
 *
 * <p>That is the opposite of the public support form, which refuses on both, and the asymmetry is a
 * decision rather than an oversight. Every endpoint behind this guard already carries a per-caller
 * Redis sliding-window rule in {@code AuthRateLimitFilter}, and that rule - not Turnstile - is the
 * real defence against credential stuffing and mass registration. The public form has no comparable
 * per-caller control, so Turnstile is the only thing standing there and must hold even when
 * Cloudflare is down. Here, holding would mean a Cloudflare outage becoming a total authentication
 * outage: nobody logs in, nobody resets a password, nobody reports anything. Trading bot resistance
 * for availability is the right way round on these six surfaces and the wrong way round on that
 * one.
 *
 * <p>Failing open is safe to the extent that {@link TurnstileOutcome#UNAVAILABLE} is unreachable
 * from client input, which {@link TurnstileVerifier} guarantees. Every outage is counted on {@code
 * turnstile.verification.total} and logged at WARN, so the degraded window is visible rather than
 * silent.
 */
@Component
public class AuthTurnstileGuard {

    private final TurnstileVerifier verifier;
    private final TurnstileProperties properties;

    public AuthTurnstileGuard(TurnstileVerifier verifier, TurnstileProperties properties) {
        this.verifier = verifier;
        this.properties = properties;
    }

    /**
     * Refuses the request when Cloudflare positively rejects the submitted token, and allows it in
     * every other case.
     *
     * <p>Called before any credential check, token issue, mail enqueue, or write, so a refusal
     * leaves no side effect behind. Performs no database or Redis access.
     *
     * @param token the {@code turnstileToken} field from the request body, or null
     * @param clientIp the caller's address as resolved by {@code IpExtractor}, or null
     * @param surface the surface being protected, used as a metric tag
     * @throws AppException with {@link ApiErrorCode#AUTH_CAPTCHA_FAILED} when the token is rejected
     */
    public void require(String token, String clientIp, TurnstileSurface surface) {
        if (!properties.getAuth().isEnabled()) {
            // Operator kill switch. Skips verification outright, which is why the turnstileToken
            // fields on these DTOs carry no @NotBlank: a bean-validation rejection would fire
            // before this method and the switch would not actually switch anything off.
            return;
        }
        if (verifier.verify(token, clientIp, surface) == TurnstileOutcome.REJECTED) {
            throw new AppException(ApiErrorCode.AUTH_CAPTCHA_FAILED);
        }
    }
}
