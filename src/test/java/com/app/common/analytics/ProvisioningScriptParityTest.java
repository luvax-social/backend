package com.app.common.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.app.testsupport.ClickHouseTestSupport;

/**
 * Keeps the provisioning SQL every ClickHouse test runs identical to the block in the observability
 * stack's script, so a test exercises the same users, profiles and grants production gets.
 */
class ProvisioningScriptParityTest {

    private static final Path SCRIPT =
            Path.of("..", "observability", "clickhouse", "initdb", "02-create-analytics.sh");

    @Test
    void provisioningSql_testResource_equalsTheObservabilityScriptBlock() throws IOException {
        assumeTrue(
                Files.exists(SCRIPT),
                "the observability checkout is not next to this repository, so there is nothing"
                        + " to compare with");

        List<String> lines = Files.readAllLines(SCRIPT);
        int start = -1;
        int end = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (start < 0 && lines.get(i).stripTrailing().endsWith("<<SQL")) {
                start = i + 1;
            } else if (start >= 0 && lines.get(i).strip().equals("SQL")) {
                end = i;
                break;
            }
        }
        assertThat(start).as("the script has a <<SQL heredoc").isPositive();
        assertThat(end).as("the heredoc is closed by SQL").isGreaterThan(start);

        String scriptBlock = normalise(String.join("\n", lines.subList(start, end)));

        assertThat(normalise(ClickHouseTestSupport.readProvisioningSql())).isEqualTo(scriptBlock);
    }

    private static String normalise(String sql) {
        return sql.replace("\r\n", "\n").strip();
    }
}
