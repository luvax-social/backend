package com.app.common.analytics;

import java.sql.SQLException;
import java.util.Set;

/**
 * Turns a driver failure into one of the two exceptions every ClickHouse caller handles.
 *
 * <p>It inspects the driver's {@link SQLException}, never Spring's exception type: every ClickHouse
 * server error carries SQLState {@code 22000}, which Spring's SQLState fallback translates to a
 * {@code DataIntegrityViolationException} whatever the real cause, so the Spring type says nothing
 * about whether the server is merely overloaded.
 */
public final class ClickHouseErrorTranslator {

    // Codes that mean the request itself is wrong: retrying or pausing cannot help, and they must
    // not count against the breaker. Everything else, including an unknown code, is treated as the
    // server being unavailable, which pauses ingestion rather than dead-lettering data.
    private static final Set<Integer> REJECTED =
            Set.of(
                    6, // CANNOT_PARSE_TEXT
                    26, // CANNOT_PARSE_QUOTED_STRING
                    27, // CANNOT_PARSE_INPUT_ASSERTION_FAILED
                    36, // BAD_ARGUMENTS
                    41, // CANNOT_PARSE_DATETIME
                    43, // ILLEGAL_TYPE_OF_ARGUMENT
                    44, // ILLEGAL_COLUMN
                    47, // UNKNOWN_IDENTIFIER
                    53, // TYPE_MISMATCH
                    62, // SYNTAX_ERROR
                    70, // CANNOT_CONVERT_TYPE
                    72, // CANNOT_PARSE_NUMBER
                    117, // INCORRECT_DATA
                    130, // CANNOT_READ_ARRAY_FROM_TEXT
                    396, // TOO_MANY_ROWS_OR_BYTES
                    452, // SETTING_CONSTRAINT_VIOLATION
                    691 // UNKNOWN_ELEMENT_OF_ENUM
                    );

    private static final int MAX_CAUSE_DEPTH = 12;

    private ClickHouseErrorTranslator() {}

    /**
     * Classifies a failure raised while calling ClickHouse.
     *
     * <p>Connection failures (SQLState {@code 08xxx}), timeouts, memory limits, too many parts or
     * queries, an unknown table, a privilege or authentication failure and any unknown code are
     * unavailability. The last four are configuration faults that dead-lettering would turn into
     * data loss, while pausing waits for the fix.
     *
     * @param failure what the driver or Spring's JDBC layer threw
     * @return {@link ClickHouseRequestRejectedException} for a code in the rejected set, otherwise
     *     {@link ClickHouseUnavailableException}
     */
    public static ClickHouseException translate(RuntimeException failure) {
        SQLException sql = findSqlException(failure);
        if (sql == null) {
            return unavailable(failure, "no SQL error in the failure");
        }
        String state = sql.getSQLState();
        if (state != null && state.startsWith("08")) {
            return unavailable(failure, describe(sql));
        }
        if (REJECTED.contains(sql.getErrorCode())) {
            return new ClickHouseRequestRejectedException(
                    "clickhouse rejected the request: " + describe(sql),
                    sql.getErrorCode(),
                    failure);
        }
        return unavailable(failure, describe(sql));
    }

    private static ClickHouseUnavailableException unavailable(
            RuntimeException failure, String detail) {
        return new ClickHouseUnavailableException(
                ClickHouseUnavailableException.Reason.SERVER,
                "clickhouse unavailable: " + detail,
                failure);
    }

    private static String describe(SQLException sql) {
        return "code=" + sql.getErrorCode() + " state=" + sql.getSQLState();
    }

    private static SQLException findSqlException(Throwable failure) {
        Throwable cursor = failure;
        for (int depth = 0; cursor != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cursor instanceof SQLException sql) {
                return sql;
            }
            cursor = cursor.getCause();
        }
        return null;
    }
}
