package com.app.common.seed.model;

import java.util.List;
import java.util.Map;

/**
 * Parsed content of one entry in {@code moderation_cases.json}.
 *
 * <p>{@code reportedContent} and {@code timeline} are kept as raw key-value maps rather than typed
 * records: each timeline entry's shape varies by its {@code event} discriminator ({@code
 * report_submitted}, {@code report_status_changed}, {@code admin_action}), and typing that variance
 * precisely is left to {@code ModerationSeedWriter}, the first consumer that actually needs to
 * branch on it.
 */
public record ModerationCaseSeed(
        String id,
        String title,
        String targetUsername,
        List<String> reporterUsernames,
        List<Map<String, Object>> reportedContent,
        List<Map<String, Object>> timeline,
        String outcome) {}
