package com.app.common.turnstile;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Server-side verification of a Cloudflare Turnstile token. The single siteverify implementation in
 * this codebase.
 *
 * <p>Verification happens before anything is written. Turnstile proves the submitter is probably
 * not a bot; it proves nothing about who they are or about any address they typed, which is what
 * the credential check and the separate email confirmation step are for.
 *
 * <p><strong>This class reports, it does not decide.</strong> It returns {@link TurnstileOutcome}
 * and every caller applies its own policy, because the right answer to "Cloudflare did not respond"
 * differs by surface:
 *
 * <ul>
 *   <li>The public support form fails closed on {@link TurnstileOutcome#UNAVAILABLE}. Failing open
 *       would keep appeals flowing during a Cloudflare outage, but it would also mean that anyone
 *       able to cause a timeout - which, for an outbound call from our own network, is not a high
 *       bar - can switch the bot control off at will. A control that an attacker can disable by
 *       making one request slow is not a control. Failing closed makes an outage visible and
 *       temporary: the public form stops, the two authenticated paths and every signed appeal link
 *       in existing moderation mail keep working, so no one who was actually mailed a decision
 *       loses their route to contest it.
 *   <li>The authentication and report surfaces fail open on {@link TurnstileOutcome#UNAVAILABLE}.
 *       Each already carries a per-caller Redis sliding-window rule, which is the real defence
 *       there, so the same outage must not escalate into a total authentication outage. See {@link
 *       AuthTurnstileGuard}.
 * </ul>
 *
 * <p>{@link TurnstileOutcome#UNAVAILABLE} is never reachable from client input: a blank, malformed,
 * expired or replayed token is a {@link TurnstileOutcome#REJECTED}, decided either locally or by
 * Cloudflare. Only a genuine infrastructure failure produces the fail-open outcome, which is what
 * stops a caller electing to bypass the control.
 *
 * <p>No token, secret, or raw response body is ever logged, at any level.
 */
@Component
public class TurnstileVerifier {

    private static final Logger log = LoggerFactory.getLogger(TurnstileVerifier.class);

    private final TurnstileProperties properties;
    private final TurnstileMetrics metrics;
    private final RestClient restClient;

    public TurnstileVerifier(TurnstileProperties properties, TurnstileMetrics metrics) {
        this(
                properties,
                metrics,
                RestClient.builder().requestFactory(requestFactory(properties)).build());
    }

    // Seam for the outcome-mapping tests, which bind a MockRestServiceServer to the builder rather
    // than reaching Cloudflare. The public constructor above is the only one Spring uses, so the
    // connect and read timeouts are always the configured ones in production.
    TurnstileVerifier(
            TurnstileProperties properties, TurnstileMetrics metrics, RestClient restClient) {
        this.properties = properties;
        this.metrics = metrics;
        this.restClient = restClient;
    }

    /**
     * Verifies one Turnstile token against Cloudflare's siteverify endpoint.
     *
     * <p>Performs at most one outbound HTTPS request, bounded by the configured connect and read
     * timeouts, and makes no database or Redis call of its own. A blank token short-circuits to
     * {@link TurnstileOutcome#REJECTED} without any network call.
     *
     * @param token the token the client submitted, or null when the client sent none
     * @param remoteIp the caller's address as resolved by {@code IpExtractor}, or null; forwarded
     *     to Cloudflare as {@code remoteip} only when non-blank
     * @param surface the submission surface, used solely as a metric tag
     * @return what the attempt established; never null
     */
    public TurnstileOutcome verify(String token, String remoteIp, TurnstileSurface surface) {
        long startNanos = System.nanoTime();
        Attempt attempt = evaluate(token, remoteIp);
        metrics.record(surface, attempt.outcome(), System.nanoTime() - startNanos);
        if (attempt.outcome() == TurnstileOutcome.UNAVAILABLE) {
            log.warn(
                    "Turnstile verification unavailable: surface={} reason={}",
                    surface.tag(),
                    attempt.reason());
        }
        return attempt.outcome();
    }

    /**
     * The outcome plus a short machine-readable reason for the WARN line.
     *
     * <p>The reason is a fixed vocabulary rather than an exception message: a non-2xx from
     * Cloudflare surfaces as a {@code RestClientResponseException} whose message embeds the
     * response body, which must never be logged.
     */
    private record Attempt(TurnstileOutcome outcome, String reason) {

        static Attempt of(TurnstileOutcome outcome) {
            return new Attempt(outcome, null);
        }
    }

    private Attempt evaluate(String token, String remoteIp) {
        if (!StringUtils.hasText(token)) {
            // Decided locally and deliberately not UNAVAILABLE: an absent token is the one failure
            // a client can produce at will, so routing it to the fail-open outcome would let any
            // caller opt out of the control by sending nothing.
            return Attempt.of(TurnstileOutcome.REJECTED);
        }
        if (!StringUtils.hasText(properties.getSecretKey())) {
            // A deployment fault rather than a transient one, so it is logged louder than the
            // outages below and must be fixed rather than waited out.
            log.error("Turnstile secret is not configured; verification cannot be performed");
            return new Attempt(TurnstileOutcome.UNAVAILABLE, "secret_not_configured");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", properties.getSecretKey());
        form.add("response", token);
        if (StringUtils.hasText(remoteIp)) {
            form.add("remoteip", remoteIp);
        }
        // Lets Cloudflare recognise a retry of this exact attempt as the same attempt rather than
        // as a replay of an already-spent token.
        form.add("idempotency_key", UUID.randomUUID().toString());

        try {
            Map<?, ?> body =
                    restClient
                            .post()
                            .uri(properties.getVerifyUrl())
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .body(form)
                            .retrieve()
                            .body(Map.class);
            if (body == null || !(body.get("success") instanceof Boolean success)) {
                // Well-formed refusals always carry a boolean success. Anything else is an answer
                // we cannot read, which is an outage symptom, not a verdict on the token.
                return new Attempt(TurnstileOutcome.UNAVAILABLE, "malformed_response");
            }
            return Attempt.of(success ? TurnstileOutcome.VERIFIED : TurnstileOutcome.REJECTED);
        } catch (RuntimeException ex) {
            // Timeout, connection failure, non-2xx, or an unparseable payload. Only the exception
            // type is reported: its message can carry the response body verbatim.
            return new Attempt(TurnstileOutcome.UNAVAILABLE, ex.getClass().getSimpleName());
        }
    }

    private static SimpleClientHttpRequestFactory requestFactory(TurnstileProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(nonNull(properties.getConnectTimeout(), Duration.ofSeconds(3)));
        factory.setReadTimeout(nonNull(properties.getReadTimeout(), Duration.ofSeconds(5)));
        return factory;
    }

    private static Duration nonNull(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }
}
