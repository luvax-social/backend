package com.app.common.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;

import com.app.common.analytics.ClickHouseUnavailableException.Reason;

class ClickHouseErrorTranslatorTest {

    // The driver reports every server error as SQLState 22000 with the ClickHouse code as the
    // vendor
    // error code.
    private static SQLException serverError(int code) {
        return new SQLException("Code: " + code, "22000", code);
    }

    @ParameterizedTest
    @ValueSource(ints = {6, 26, 27, 36, 41, 43, 44, 47, 53, 62, 70, 72, 117, 130, 396, 452, 691})
    void translate_rejectedCode_isRequestRejectedWithThatCode(int code) {
        ClickHouseException translated =
                ClickHouseErrorTranslator.translate(
                        new UncategorizedSQLException("insert", "sql", serverError(code)));

        assertThat(translated).isInstanceOf(ClickHouseRequestRejectedException.class);
        assertThat(((ClickHouseRequestRejectedException) translated).errorCode()).isEqualTo(code);
    }

    @ParameterizedTest
    @ValueSource(ints = {159, 241, 252, 202, 60, 497, 516, 999_999})
    void translate_availabilityOrUnknownCode_isUnavailable(int code) {
        ClickHouseException translated =
                ClickHouseErrorTranslator.translate(
                        new UncategorizedSQLException("select", "sql", serverError(code)));

        assertThat(translated).isInstanceOf(ClickHouseUnavailableException.class);
        assertThat(((ClickHouseUnavailableException) translated).reason()).isEqualTo(Reason.SERVER);
    }

    @Test
    void translate_connectionSqlState_isUnavailableEvenWithARejectedLookingCode() {
        SQLException refused = new SQLException("connect failed", "08000", 6);

        ClickHouseException translated =
                ClickHouseErrorTranslator.translate(
                        new CannotGetJdbcConnectionException("connect", refused));

        assertThat(translated).isInstanceOf(ClickHouseUnavailableException.class);
    }

    @Test
    void translate_poolCheckoutTimeout_isUnavailable() {
        SQLTransientConnectionException timeout =
                new SQLTransientConnectionException("pool timeout");

        ClickHouseException translated =
                ClickHouseErrorTranslator.translate(
                        new CannotGetJdbcConnectionException("connect", timeout));

        assertThat(translated).isInstanceOf(ClickHouseUnavailableException.class);
    }

    @Test
    void translate_springTypeSaysDataIntegrityButCodeIsMemoryLimit_isStillUnavailable() {
        // Spring's SQLState fallback turns SQLState 22000 into DataIntegrityViolationException,
        // which would read as a bad request if the Spring type were consulted.
        DataIntegrityViolationException wrapped =
                new DataIntegrityViolationException("Code: 241", serverError(241));

        ClickHouseException translated = ClickHouseErrorTranslator.translate(wrapped);

        assertThat(translated).isInstanceOf(ClickHouseUnavailableException.class);
    }

    @Test
    void translate_noSqlExceptionInTheChain_isUnavailable() {
        ClickHouseException translated =
                ClickHouseErrorTranslator.translate(new IllegalStateException("socket closed"));

        assertThat(translated).isInstanceOf(ClickHouseUnavailableException.class);
    }

    @Test
    void translate_keepsTheOriginalFailureAsTheCause() {
        UncategorizedSQLException original =
                new UncategorizedSQLException("insert", "sql", serverError(691));

        assertThat(ClickHouseErrorTranslator.translate(original)).hasCause(original);
    }
}
