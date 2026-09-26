package com.app.modules.mail.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Covers the provider-failure classification only.
 *
 * <p>The rest of {@link ResendMailSender} is a thin call into the vendor SDK and is exercised by
 * the consumer tests and the RabbitMQ integration test; this class pins the one piece of real
 * logic, because it is a message parse and message formats drift.
 */
class ResendMailSenderTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Failed to send email: 422 {\"statusCode\":422,\"name\":\"validation_error\"}",
                "Failed to send email: 400 {\"statusCode\":400}",
                "Failed to send email: 401 {\"statusCode\":401}",
                "Failed to send email: 403 {\"statusCode\":403}",
                "Failed to send email: 404 {\"statusCode\":404}"
            })
    void isPermanentRejection_clientErrorTheProviderWillNotAccept_isPermanent(String message) {
        assertThat(ResendMailSender.isPermanentRejection(message)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // The two 4xx the provider expects a client to retry.
                "Failed to send email: 408 {\"statusCode\":408}",
                "Failed to send email: 429 {\"statusCode\":429}",
                // Server-side failures are the provider being unable to accept the message now.
                "Failed to send email: 500 {\"statusCode\":500}",
                "Failed to send email: 502 {\"statusCode\":502}",
                "Failed to send email: 503 {\"statusCode\":503}"
            })
    void isPermanentRejection_retryableStatus_isTransient(String message) {
        assertThat(ResendMailSender.isPermanentRejection(message)).isFalse();
    }

    @Test
    void isPermanentRejection_noStatusInMessage_isTransient() {
        // Unparseable must fall to transient: retrying a message that would have succeeded is
        // recoverable, dropping one that would have succeeded is not.
        assertThat(ResendMailSender.isPermanentRejection("Connection reset by peer")).isFalse();
    }

    @Test
    void isPermanentRejection_nullMessage_isTransient() {
        assertThat(ResendMailSender.isPermanentRejection(null)).isFalse();
    }

    @Test
    void isPermanentRejection_threeDigitRunInsideAnIdentifier_isNotReadAsAStatus() {
        // The word boundaries exist so an id or a byte count cannot be mistaken for a status.
        assertThat(ResendMailSender.isPermanentRejection("sent id=a4022b size=45000")).isFalse();
    }

    // P7-BE-006. A word boundary was not enough: a hyphen is a non-word character on both sides,
    // so a delimited three-digit run inside a request id matched. Reading it as a status
    // dead-letters a transient failure on the first attempt with no retry, which is the direction
    // this method exists to avoid.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "Failed to send email: 503 req-451-ab",
                "Failed to send email: 500 {\"trace\":\"req-422-9f\"}",
                "Connection reset by peer req-451-ab",
                "timeout after 404-ms contacting provider"
            })
    void isPermanentRejection_delimitedDigitsAwayFromTheStatusPosition_isNotReadAsAStatus(
            String message) {
        assertThat(ResendMailSender.isPermanentRejection(message)).isFalse();
    }

    @Test
    void isPermanentRejection_statusOnlyCountsWhereTheFormatPutsIt_isAnchored() {
        // The same digits, in the documented position, are still read.
        assertThat(ResendMailSender.isPermanentRejection("Failed to send email: 422 req-451-ab"))
                .isTrue();
        // And an unrecognised prefix falls through to transient rather than guessing.
        assertThat(ResendMailSender.isPermanentRejection("Some other wrapper: 422 {}")).isFalse();
    }

    // P7-BE-005. The provider's text reaches email_deliveries.error_text and the dead-letter
    // reason header, and its body is whatever the provider returned, so a validation error naming
    // the offending recipient would write an address into two durable stores.
    @Test
    void redactAddresses_masksAnAddressShapedRun() {
        assertThat(
                        ResendMailSender.redactAddresses(
                                "Failed to send email: 422 {\"message\":\"Invalid to field:"
                                        + " someone@example.com\"}"))
                .doesNotContain("someone@example.com")
                .contains("[redacted-address]")
                // The classification and the provider's own wording survive; only the address goes.
                .contains("422")
                .contains("Invalid to field");
    }

    @Test
    void redactAddresses_masksEveryAddressAndToleratesNone() {
        assertThat(ResendMailSender.redactAddresses("a@b.co and c.d+tag@e-f.example.org"))
                .isEqualTo("[redacted-address] and [redacted-address]");
        assertThat(ResendMailSender.redactAddresses("Connection reset by peer"))
                .isEqualTo("Connection reset by peer");
        assertThat(ResendMailSender.redactAddresses(null)).isNull();
    }
}
