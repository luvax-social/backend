package com.app.modules.recommendation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.clickhouse.ClickHouseContainer;

import com.app.modules.recommendation.enums.UserEventType;
import com.app.testsupport.ClickHouseTestSupport;

/**
 * Pins {@link UserEventType} to the {@code Enum8} that {@code user_events.event_type} has after
 * every shipped ClickHouse migration.
 *
 * <p>The column stores the ordinal, so a value inserted in the middle of the enum, reordered, or
 * added to one side only would silently remap stored rows or be rejected at insert time. The
 * declaration order of the Java enum is the column's ordinal order, starting at one.
 */
class UserEventTypeColumnParityIT {

    private static final Pattern ENUM_MEMBER = Pattern.compile("'([a-z_]+)' = (\\d+)");

    static ClickHouseContainer clickhouse;
    static JdbcClient admin;

    @BeforeAll
    static void start() {
        clickhouse = ClickHouseTestSupport.startProvisioned();
        ClickHouseTestSupport.applyMigrations(clickhouse);
        admin =
                ClickHouseTestSupport.clientAs(
                        clickhouse, clickhouse.getUsername(), clickhouse.getPassword());
    }

    @AfterAll
    static void stop() {
        clickhouse.stop();
    }

    @Test
    void eventTypeColumn_declaresEveryUserEventTypeWithItsOrdinal() {
        String columnType =
                admin.sql(
                                "SELECT type FROM system.columns WHERE database ="
                                        + " 'luvax_analytics' AND table = 'user_events' AND name ="
                                        + " 'event_type'")
                        .query(String.class)
                        .single();

        Map<String, Integer> declared = new LinkedHashMap<>();
        Matcher matcher = ENUM_MEMBER.matcher(columnType);
        while (matcher.find()) {
            declared.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
        }

        Map<String, Integer> expected = new LinkedHashMap<>();
        UserEventType[] values = UserEventType.values();
        for (int index = 0; index < values.length; index++) {
            expected.put(values[index].toJson(), index + 1);
        }

        assertThat(columnType).as("the column is an Enum8").startsWith("Enum8(");
        assertThat(declared.entrySet())
                .as("every column member, in order, with the ordinal the enum declares")
                .containsExactlyElementsOf(expected.entrySet());
    }
}
