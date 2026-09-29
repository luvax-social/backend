package com.app.common.analytics;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.app.common.analytics.migration.AnalyticsSchemaGate;

class AnalyticsStoreTruncatorTest {

    private DataSource migrator;
    private Connection connection;
    private Statement statement;
    private AnalyticsSchemaGate gate;
    private AnalyticsStoreTruncator truncator;

    @BeforeEach
    void setUp() throws SQLException {
        migrator = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        gate = mock(AnalyticsSchemaGate.class);
        when(migrator.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        truncator = new AnalyticsStoreTruncator(migrator, gate);
    }

    @Test
    void truncateAll_emptiesEveryAnalyticsTableAfterEnsuringTheSchema() throws SQLException {
        truncator.truncateAll();

        InOrder order = inOrder(gate, statement);
        order.verify(gate).attempt();
        order.verify(statement).execute("TRUNCATE TABLE luvax_analytics.user_events");
        order.verify(statement).execute("TRUNCATE TABLE luvax_analytics.admin_actions");
        order.verify(statement).execute("TRUNCATE TABLE luvax_analytics.platform_stats");
    }

    @Test
    void truncateAll_whenClickHouseRefusesATruncation_throws() throws SQLException {
        when(statement.execute("TRUNCATE TABLE luvax_analytics.admin_actions"))
                .thenThrow(new SQLException("connection reset"));

        assertThatThrownBy(() -> truncator.truncateAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connection reset");
    }

    @Test
    void truncateAll_whenTheSchemaCannotBeMigrated_throwsWithoutTruncating() throws SQLException {
        when(gate.attempt()).thenThrow(new IllegalStateException("clickhouse down"));

        assertThatThrownBy(() -> truncator.truncateAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("clickhouse down");
        verify(statement, never()).execute(anyString());
    }
}
