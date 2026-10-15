package com.app.common.seed.model;

/**
 * A report against one seeded reaction and what moderation did about it, as authored in {@code
 * safety/interaction_control.json}. The reaction is named like a {@link ReactionSeed}: its
 * conversation, the message's position and the account that reacted.
 *
 * <p>{@code action} is {@code remove} (the reaction was removed and the report resolved, and the
 * removal stands), {@code remove_restore} (removed, then restored later), {@code dismiss} (the
 * report was dismissed and the reaction untouched) or {@code none} (the report is still open,
 * pending or under review). {@code admin} is the moderator or administrator who acted, null while
 * the report is pending.
 */
public record ReactionReportSeed(
        String conversationId,
        int messageIndex,
        String reactor,
        String reporter,
        String reportReason,
        String reportStatus,
        String action,
        String admin,
        String note,
        int daysAgo) {}
