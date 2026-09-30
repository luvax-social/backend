package com.app.common.analytics.impl;

import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.app.common.analytics.ClickHouseOperations;
import com.app.common.analytics.ClickHouseUnavailableException;
import com.app.common.analytics.ClickHouseUnavailableException.Reason;

/**
 * Stands in when {@code app.analytics.enabled} is false: every call fails as unavailable, so the
 * paths that tolerate an outage behave the same whether ClickHouse is switched off or down.
 */
@Component
@ConditionalOnProperty(name = "app.analytics.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledClickHouseOperations implements ClickHouseOperations {

    @Override
    public <T> T read(String operation, Function<JdbcClient, T> query) {
        throw disabled();
    }

    @Override
    public <T> T readBatch(String operation, Function<JdbcClient, T> query) {
        throw disabled();
    }

    @Override
    public void write(String operation, Consumer<JdbcClient> insert) {
        throw disabled();
    }

    @Override
    public boolean isReady() {
        return false;
    }

    private static ClickHouseUnavailableException disabled() {
        return new ClickHouseUnavailableException(Reason.NOT_READY, "analytics disabled");
    }
}
