package com.app.common.turnstile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.SocketTimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class TurnstileVerifierTest {

    private static final String VERIFY_URL = "https://turnstile.test/siteverify";
    private static final String TOKEN = "a-client-supplied-token";

    private TurnstileProperties properties;
    private SimpleMeterRegistry registry;
    private MockRestServiceServer server;
    private TurnstileVerifier verifier;

    @BeforeEach
    void setUp() {
        properties = new TurnstileProperties();
        properties.setSecretKey("1x0000000000000000000000000000000AA");
        properties.setVerifyUrl(VERIFY_URL);
        registry = new SimpleMeterRegistry();
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        verifier =
                new TurnstileVerifier(properties, new TurnstileMetrics(registry), builder.build());
    }

    @Test
    void verify_cloudflareAnswersSuccessTrue_isVerified() {
        server.expect(requestTo(VERIFY_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

        assertThat(verifier.verify(TOKEN, "1.2.3.4", TurnstileSurface.LOGIN))
                .isEqualTo(TurnstileOutcome.VERIFIED);
        server.verify();
    }

    @Test
    void verify_cloudflareAnswersSuccessFalse_isRejected() {
        server.expect(requestTo(VERIFY_URL))
                .andRespond(
                        withSuccess(
                                "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}",
                                MediaType.APPLICATION_JSON));

        assertThat(verifier.verify(TOKEN, null, TurnstileSurface.LOGIN))
                .isEqualTo(TurnstileOutcome.REJECTED);
    }

    @Test
    void verify_blankToken_isRejectedWithoutCallingCloudflare() {
        assertThat(verifier.verify("", null, TurnstileSurface.LOGIN))
                .isEqualTo(TurnstileOutcome.REJECTED);
        assertThat(verifier.verify("   ", null, TurnstileSurface.LOGIN))
                .isEqualTo(TurnstileOutcome.REJECTED);
        assertThat(verifier.verify(null, null, TurnstileSurface.LOGIN))
                .isEqualTo(TurnstileOutcome.REJECTED);
        // No expectation was registered, so any outbound call would fail this.
        server.verify();
    }

    @Test
    void verify_blankSecret_isUnavailableWithoutCallingCloudflare() {
        properties.setSecretKey("");

        assertThat(verifier.verify(TOKEN, null, TurnstileSurface.REGISTER))
                .isEqualTo(TurnstileOutcome.UNAVAILABLE);
        server.verify();
    }

    @Test
    void verify_readTimeout_isUnavailable() {
        server.expect(requestTo(VERIFY_URL))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThat(verifier.verify(TOKEN, null, TurnstileSurface.FORGOT_PASSWORD))
                .isEqualTo(TurnstileOutcome.UNAVAILABLE);
    }

    @Test
    void verify_connectionFailure_isUnavailable() {
        server.expect(requestTo(VERIFY_URL))
                .andRespond(withException(new IOException("Connection refused")));

        assertThat(verifier.verify(TOKEN, null, TurnstileSurface.RESET_PASSWORD))
                .isEqualTo(TurnstileOutcome.UNAVAILABLE);
    }

    @Test
    void verify_nonTwoHundredStatus_isUnavailable() {
        server.expect(requestTo(VERIFY_URL)).andRespond(withServerError());

        assertThat(verifier.verify(TOKEN, null, TurnstileSurface.REPORT))
                .isEqualTo(TurnstileOutcome.UNAVAILABLE);
    }

    @Test
    void verify_malformedBody_isUnavailable() {
        server.expect(requestTo(VERIFY_URL))
                .andRespond(withSuccess("not json at all", MediaType.APPLICATION_JSON));

        assertThat(verifier.verify(TOKEN, null, TurnstileSurface.PUBLIC_SUPPORT))
                .isEqualTo(TurnstileOutcome.UNAVAILABLE);
    }

    @Test
    void verify_bodyWithoutBooleanSuccess_isUnavailable() {
        // A well-formed refusal always carries a boolean. Anything else is an answer we cannot
        // read, which is an outage symptom rather than a verdict on the token.
        server.expect(requestTo(VERIFY_URL))
                .andRespond(withSuccess("{\"success\":\"yes\"}", MediaType.APPLICATION_JSON));

        assertThat(verifier.verify(TOKEN, null, TurnstileSurface.LOGIN))
                .isEqualTo(TurnstileOutcome.UNAVAILABLE);
    }

    @Test
    void verify_sendsSecretResponseRemoteIpAndIdempotencyKey() {
        server.expect(requestTo(VERIFY_URL))
                .andExpect(
                        content().string(org.hamcrest.Matchers.containsString("remoteip=1.2.3.4")))
                .andExpect(
                        content().string(org.hamcrest.Matchers.containsString("idempotency_key=")))
                .andExpect(
                        content().string(org.hamcrest.Matchers.containsString("response=" + TOKEN)))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

        verifier.verify(TOKEN, "1.2.3.4", TurnstileSurface.LOGIN);
        server.verify();
    }

    @Test
    void verify_blankRemoteIp_omitsRemoteIpFromTheForm() {
        server.expect(requestTo(VERIFY_URL))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString("remoteip"))))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

        verifier.verify(TOKEN, "  ", TurnstileSurface.LOGIN);
        server.verify();
    }

    @Test
    void verify_countsOutcomeAndSurfaceOnTheMetric() {
        server.expect(requestTo(VERIFY_URL))
                .andRespond(withSuccess("{\"success\":false}", MediaType.APPLICATION_JSON));

        verifier.verify(TOKEN, null, TurnstileSurface.RESEND_VERIFICATION);

        assertThat(
                        registry.get("turnstile.verification.total")
                                .tag("outcome", "rejected")
                                .tag("surface", "resend_verification")
                                .counter()
                                .count())
                .isEqualTo(1.0);
        assertThat(registry.get("turnstile.verification.duration").timer().count()).isEqualTo(1L);
    }
}
