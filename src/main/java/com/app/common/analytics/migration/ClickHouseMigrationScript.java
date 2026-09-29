package com.app.common.analytics.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One versioned ClickHouse schema script: {@code V{n}__{snake_case_description}.sql}.
 *
 * @param version the ascending version number
 * @param description the description part of the file name, underscores kept
 * @param fileName the file name, recorded in the history table
 * @param checksum SHA-256 of the script with line endings normalised to LF, so a Windows checkout
 *     computes the same value as a Linux one
 * @param statements the script's statements in order, without their terminating semicolon
 */
public record ClickHouseMigrationScript(
        int version,
        String description,
        String fileName,
        String checksum,
        List<String> statements) {

    private static final Pattern FILE_NAME = Pattern.compile("V(\\d+)__(.+)\\.sql");

    /**
     * Parses one script.
     *
     * @param fileName the file name, which must match {@code V{n}__{description}.sql}
     * @param content the script text
     * @return the parsed script
     */
    public static ClickHouseMigrationScript parse(String fileName, String content) {
        Matcher matcher = FILE_NAME.matcher(fileName);
        if (!matcher.matches()) {
            throw new ClickHouseMigrationException(
                    "Migration file name does not match V{n}__{description}.sql: " + fileName);
        }
        String normalised = content.replace("\r\n", "\n").replace('\r', '\n');
        return new ClickHouseMigrationScript(
                Integer.parseInt(matcher.group(1)),
                matcher.group(2),
                fileName,
                sha256(normalised),
                splitStatements(normalised));
    }

    /**
     * Splits a script into statements. A statement ends at a semicolon that ends a line, and lines
     * that start with {@code --} are comments and skipped.
     *
     * @param script the script text with LF line endings
     * @return the statements in order, without their terminating semicolon
     */
    static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : script.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("--")) {
                continue;
            }
            if (trimmed.isEmpty() && current.isEmpty()) {
                continue;
            }
            if (trimmed.endsWith(";")) {
                current.append(trimmed, 0, trimmed.length() - 1);
                addIfNotBlank(statements, current);
                current.setLength(0);
            } else {
                current.append(line).append('\n');
            }
        }
        addIfNotBlank(statements, current);
        return List.copyOf(statements);
    }

    private static void addIfNotBlank(List<String> statements, StringBuilder statement) {
        String text = statement.toString().strip();
        if (!text.isEmpty()) {
            statements.add(text);
        }
    }

    private static String sha256(String text) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
