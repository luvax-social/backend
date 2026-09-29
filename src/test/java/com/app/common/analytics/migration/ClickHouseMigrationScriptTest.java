package com.app.common.analytics.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ClickHouseMigrationScriptTest {

    @Test
    void parse_fileName_yieldsVersionAndDescription() {
        ClickHouseMigrationScript script =
                ClickHouseMigrationScript.parse("V12__add_thing.sql", "SELECT 1;");

        assertThat(script.version()).isEqualTo(12);
        assertThat(script.description()).isEqualTo("add_thing");
        assertThat(script.fileName()).isEqualTo("V12__add_thing.sql");
    }

    @Test
    void parse_badFileName_isRejected() {
        assertThatThrownBy(() -> ClickHouseMigrationScript.parse("create_things.sql", "SELECT 1;"))
                .isInstanceOf(ClickHouseMigrationException.class);
    }

    @Test
    void parse_windowsLineEndings_computeTheSameChecksumAsUnix() {
        String unix = "CREATE TABLE a\n(\n    x UInt8\n)\nENGINE = Memory;\n";
        String windows = unix.replace("\n", "\r\n");

        assertThat(ClickHouseMigrationScript.parse("V1__a.sql", windows).checksum())
                .isEqualTo(ClickHouseMigrationScript.parse("V1__a.sql", unix).checksum())
                .hasSize(64);
    }

    @Test
    void parse_editedContent_changesTheChecksum() {
        assertThat(ClickHouseMigrationScript.parse("V1__a.sql", "SELECT 1;").checksum())
                .isNotEqualTo(ClickHouseMigrationScript.parse("V1__a.sql", "SELECT 2;").checksum());
    }

    @Test
    void splitStatements_endOfLineSemicolon_separatesStatementsAndSkipsCommentLines() {
        String script =
                String.join(
                        "\n",
                        "-- header comment; with a semicolon inside",
                        "CREATE TABLE a",
                        "(",
                        "    -- column comment",
                        "    x UInt8",
                        ")",
                        "ENGINE = Memory;",
                        "",
                        "ALTER TABLE a ADD COLUMN IF NOT EXISTS y UInt8;",
                        "");

        assertThat(ClickHouseMigrationScript.splitStatements(script))
                .containsExactly(
                        "CREATE TABLE a\n(\n    x UInt8\n)\nENGINE = Memory",
                        "ALTER TABLE a ADD COLUMN IF NOT EXISTS y UInt8");
    }

    @Test
    void splitStatements_trailingStatementWithoutSemicolon_isKept() {
        assertThat(ClickHouseMigrationScript.splitStatements("SELECT 1;\nSELECT 2"))
                .containsExactly("SELECT 1", "SELECT 2");
    }

    @Test
    void splitStatements_onlyComments_isEmpty() {
        assertThat(ClickHouseMigrationScript.splitStatements("-- nothing here\n\n")).isEmpty();
    }
}
