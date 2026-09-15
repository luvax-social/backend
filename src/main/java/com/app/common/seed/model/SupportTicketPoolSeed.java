package com.app.common.seed.model;

import java.util.List;
import java.util.Map;

/**
 * Parsed content of {@code support/support_ticket_pools.json} in full: a pooled request text per
 * non-verification category, a pool of verification claims, and pooled staff-response/escalation
 * prose {@link com.app.common.seed.writer.SupportSeedWriter} draws from instead of repeating one
 * template verbatim across every ticket.
 *
 * <p>{@code requestsByCategory} is keyed by the lowercase {@code support_category} enum literal
 * (e.g. {@code "appeal_ban"}, {@code "bug_report"}) - never {@code "verification_request"}, which
 * has its own pool below.
 */
public record SupportTicketPoolSeed(
        Map<String, List<SupportTicketRequestEntry>> requestsByCategory,
        List<VerificationRequestPoolEntry> verificationRequests,
        List<String> staffResponsesApproved,
        List<String> staffResponsesRejected,
        List<String> escalationReasons) {}
