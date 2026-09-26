package com.app.common.seed.writer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import com.app.common.seed.loader.SeedContent;
import com.app.common.seed.model.SupportTicketPoolSeed;
import com.app.common.seed.model.SupportTicketRequestEntry;
import com.app.common.seed.model.VerificationTicketSeed;
import com.app.common.seed.time.SeedTimeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds {@code support_tickets} (70), their {@code verification_requests} children (10) and the
 * {@code admin_actions} audit rows a real staff decision produces - {@code respond_support_ticket},
 * {@code reject_support_ticket} and {@code escalate_support_ticket} - from {@code
 * support/support_ticket_pools.json}'s pooled request/response prose.
 *
 * <p>There is no message thread to seed: {@code support_tickets} carries exactly one {@code
 * staff_response} and one {@code internal_note}, never a conversation (see V97's own header
 * comment). Every ticket is therefore inserted once, already in its final resolved shape, so {@code
 * trg_support_tickets_updated_at} (which fires on UPDATE, never on INSERT) never stamps a row with
 * the real wall clock.
 *
 * <p><b>Attribution</b>: every decision is attributed to one of the two fixed QA staff accounts,
 * {@code admin} or {@code mod1}. {@code appeal_*} tickets are always decided by {@code admin} (a
 * moderator may claim and escalate one but never decide it, per {@code
 * SupportAuthorizationServiceImpl}'s appeal-requires-admin rule); every other category is decided
 * by {@code mod1} unless it went through the escalate-to-admin path, in which case {@code admin}
 * makes the final call.
 *
 * <p><b>Appeal linkage</b>: an {@code appeal_*} ticket's {@code admin_action_id} is resolved by
 * reading back a real punitive {@code admin_actions} row {@link ModerationSeedWriter} already wrote
 * (e.g. {@code ban_user} for {@code appeal_ban}), matching production's signed-link path, which
 * binds the ticket to the audit row it appeals.
 *
 * <p>Must run after {@link UserSeedWriter} and {@link ModerationSeedWriter}.
 */
@Slf4j
@Service
@Profile("seed & (dev | prod)")
@RequiredArgsConstructor
public class SupportSeedWriter {

    private static final long SUPPORT_RANDOM_SEED = 7_331_902L;

    private static final List<CategoryPlan> CATEGORY_PLANS =
            List.of(
                    new CategoryPlan("appeal_ban", 6, List.of("ban_user")),
                    new CategoryPlan("appeal_suspension", 6, List.of("suspend_user")),
                    new CategoryPlan(
                            "appeal_warning_strike", 6, List.of("warn_user", "issue_strike")),
                    new CategoryPlan(
                            "appeal_content_removal",
                            6,
                            List.of(
                                    "remove_post",
                                    "remove_comment",
                                    "remove_story",
                                    "remove_message")),
                    new CategoryPlan("account_access", 6, List.of()),
                    new CategoryPlan("account_data", 6, List.of()),
                    new CategoryPlan("bug_report", 6, List.of()),
                    new CategoryPlan("safety_concern", 6, List.of()),
                    new CategoryPlan("other", 6, List.of()));

    private static final Set<String> PENDING_CONFIRMATION_CATEGORIES =
            Set.of("bug_report", "account_access", "other");

    // Every status-plan entry the category loop draws from, shuffled once per run: 10 OPEN, 8
    // IN_PROGRESS, 5 ESCALATED (still undecided), 16 ANSWERED (12 decided directly, 4 decided after
    // an escalation), 12 REJECTED (9 direct, 3 after escalation). 51 entries = the 54 category-loop
    // tickets minus the 3 deterministic PENDING_CONFIRMATION ones, so the queue is drained exactly.
    //
    // The verification lane no longer draws from here. Its outcomes are authored per ticket in
    // verification/badges.json, because the badge lanes need exactly five approvals, five refusals
    // and five open requests and a shuffled draw cannot promise any of those counts.
    private static List<StatusPlan> buildStatusPlanQueue(Random random) {
        List<StatusPlan> plans = new ArrayList<>();
        addRepeated(plans, "open", false, 10);
        addRepeated(plans, "in_progress", false, 8);
        addRepeated(plans, "escalated", false, 5);
        addRepeated(plans, "answered", false, 12);
        addRepeated(plans, "answered", true, 4);
        addRepeated(plans, "rejected", false, 9);
        addRepeated(plans, "rejected", true, 3);
        Collections.shuffle(plans, random);
        return plans;
    }

    private static void addRepeated(
            List<StatusPlan> plans, String status, boolean viaEscalation, int count) {
        for (int i = 0; i < count; i++) {
            plans.add(new StatusPlan(status, viaEscalation));
        }
    }

    private static final String INSERT_TICKET_SQL =
            "INSERT INTO support_tickets (id, user_id, contact_email, category, subject, body,"
                    + " status, source, admin_action_id, assigned_to, assigned_at, staff_response,"
                    + " internal_note, responded_by, responded_at, escalated_by, escalated_at,"
                    + " escalation_reason, created_at, updated_at) VALUES (?, ?, ?,"
                    + " ?::support_category, ?, ?, ?::support_ticket_status, ?::support_source, ?,"
                    + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_VERIFICATION_REQUEST_SQL =
            "INSERT INTO verification_requests (ticket_id, user_id, category_key, claimed_name,"
                    + " evidence_website, evidence_other_profile, evidence_email_domain,"
                    + " evidence_published_work, evidence_press, evidence_official_listing,"
                    + " evidence_note, evidence_field_count, created_at) VALUES (?, ?, ?, ?, ?, ?, ?,"
                    + " ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_ADMIN_ACTION_SQL =
            "INSERT INTO admin_actions (id, admin_id, action_type, target_user_id,"
                    + " target_entity_type, target_entity_id, report_id, reason, created_at) VALUES"
                    + " (?, ?, ?::admin_action_type, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;

    public Map<String, UUID> write(
            SeedContent content, Map<String, UUID> usersByUsername, SeedTimeline timeline) {
        Random random = new Random(SUPPORT_RANDOM_SEED);
        SupportTicketPoolSeed pools = content.supportTicketPools();

        UUID adminId = requireUser(usersByUsername, "admin");
        UUID moderatorId = requireUser(usersByUsername, "mod1");
        List<UUID> ordinaryUserIds =
                content.users().stream()
                        .filter(u -> "user".equals(u.role()))
                        .map(u -> usersByUsername.get(u.username()))
                        .filter(Objects::nonNull)
                        .toList();
        Map<UUID, Instant> userCreatedAtById = fetchUserCreatedAt();
        Map<UUID, String> userEmailById = fetchUserEmail();
        Map<String, List<AdminActionRow>> punitiveActionsByType = fetchPunitiveAdminActions();

        Set<UUID> supportLaneOccupied = new HashSet<>();
        Set<UUID> verificationLaneOccupied = new HashSet<>();
        List<StatusPlan> statusQueue = buildStatusPlanQueue(random);

        int ticketCount = 0;
        int verificationCount = 0;
        int pendingConfirmationCount = 0;
        int adminActionCount = 0;

        for (CategoryPlan plan : CATEGORY_PLANS) {
            List<SupportTicketRequestEntry> requestPool =
                    pools.requestsByCategory().getOrDefault(plan.category(), List.of());
            if (requestPool.isEmpty()) {
                throw new IllegalStateException(
                        "SupportSeedWriter: support_ticket_pools.json has no requests for category"
                                + " '"
                                + plan.category()
                                + "'");
            }
            boolean isAppeal = !plan.sourceActionTypes().isEmpty();

            for (int i = 0; i < plan.count(); i++) {
                SupportTicketRequestEntry text =
                        requestPool.get(random.nextInt(requestPool.size()));

                // The first ticket of each of the three public-eligible categories is a
                // PENDING_CONFIRMATION public-form submission - deterministic, not probabilistic,
                // so exactly 3 are always produced and the status-plan queue below always drains to
                // zero.
                if (i == 0 && PENDING_CONFIRMATION_CATEGORIES.contains(plan.category())) {
                    writePendingConfirmationTicket(plan.category(), text, timeline, random);
                    pendingConfirmationCount++;
                    ticketCount++;
                    continue;
                }

                StatusPlan statusPlan = statusQueue.remove(statusQueue.size() - 1);
                boolean nonTerminal =
                        !"answered".equals(statusPlan.status())
                                && !"rejected".equals(statusPlan.status());

                TicketTarget target =
                        isAppeal
                                ? resolveAppealTarget(
                                        plan.sourceActionTypes(),
                                        punitiveActionsByType,
                                        ordinaryUserIds,
                                        supportLaneOccupied,
                                        nonTerminal,
                                        random)
                                : resolveOrdinaryTarget(
                                        ordinaryUserIds, supportLaneOccupied, nonTerminal, random);
                if (target == null) {
                    log.warn(
                            "[seed] support: no eligible target for category '{}', skipping one"
                                    + " ticket",
                            plan.category());
                    continue;
                }
                if (nonTerminal) {
                    supportLaneOccupied.add(target.userId());
                }

                Instant createdAt =
                        timeline.supportTicketCreatedAt(
                                userCreatedAtById.getOrDefault(
                                        target.userId(), timeline.referenceNow()));
                adminActionCount +=
                        writeDecidedOrPendingTicket(
                                plan.category(),
                                text,
                                target,
                                isAppeal ? "signed_link" : "authenticated",
                                userEmailById.getOrDefault(target.userId(), "unknown@example.com"),
                                isAppeal,
                                moderatorId,
                                adminId,
                                createdAt,
                                statusPlan,
                                pools,
                                timeline,
                                random);
                ticketCount++;
            }
        }

        // The subject of a verification claim is named in verification/badges.json rather than
        // drawn from ordinaryUserIds at random. A random subject is what let a claim about an
        // illustration studio land on a gym account, and it left an approved ticket with no badge
        // behind it, because nothing downstream knew which account had been approved.
        Map<String, UUID> verificationTicketIdByUsername = new HashMap<>();
        for (VerificationTicketSeed claim : content.badges().verificationTickets()) {
            UUID subjectId = usersByUsername.get(claim.username());
            if (subjectId == null) {
                throw new IllegalStateException(
                        "SupportSeedWriter: verification/badges.json names unknown account '"
                                + claim.username()
                                + "'");
            }
            // V107 admits one non-terminal verification ticket per account. badges.json is
            // authored to hold that, and this makes a later edit that breaks it fail loudly here
            // rather than as a constraint violation halfway through the run.
            if (!claim.isTerminal() && !verificationLaneOccupied.add(subjectId)) {
                throw new IllegalStateException(
                        "SupportSeedWriter: account '"
                                + claim.username()
                                + "' holds more than one open verification request, which"
                                + " uq_support_tickets_one_open_verification_per_user refuses");
            }

            StatusPlan statusPlan = new StatusPlan(claim.outcome(), claim.viaEscalation());
            Instant createdAt =
                    timeline.supportTicketCreatedAt(
                            userCreatedAtById.getOrDefault(subjectId, timeline.referenceNow()));
            UUID ticketId = UUID.randomUUID();
            adminActionCount +=
                    writeTicketRow(
                            ticketId,
                            subjectId,
                            userEmailById.getOrDefault(subjectId, "unknown@example.com"),
                            "verification_request",
                            "Verification request: " + claim.claimedName(),
                            "Requesting a verified badge for " + claim.claimedName() + ".",
                            "authenticated",
                            null,
                            false,
                            moderatorId,
                            adminId,
                            createdAt,
                            statusPlan,
                            pools,
                            timeline,
                            random);
            writeVerificationRequestRow(ticketId, subjectId, claim, createdAt);
            verificationTicketIdByUsername.put(claim.username(), ticketId);
            ticketCount++;
            verificationCount++;
        }

        log.info(
                "[seed] support_tickets: {} rows written ({} verification_request,"
                        + " {} pending_confirmation), admin_actions: {} rows written",
                ticketCount,
                verificationCount,
                pendingConfirmationCount,
                adminActionCount);
        return verificationTicketIdByUsername;
    }

    // Resolves an appeal ticket's target from a real punitive admin_actions row
    // ModerationSeedWriter
    // already wrote, falling back to any ordinary user (with no admin_action_id link) only if no
    // matching row exists at all - mirroring ModerationSeedWriter's own "skip and log" tolerance
    // for
    // an unresolvable reference rather than failing the whole run.
    private TicketTarget resolveAppealTarget(
            List<String> sourceActionTypes,
            Map<String, List<AdminActionRow>> punitiveActionsByType,
            List<UUID> ordinaryUserIds,
            Set<UUID> supportLaneOccupied,
            boolean requireLaneFree,
            Random random) {
        List<AdminActionRow> candidates = new ArrayList<>();
        for (String actionType : sourceActionTypes) {
            candidates.addAll(punitiveActionsByType.getOrDefault(actionType, List.of()));
        }
        Collections.shuffle(candidates, random);
        for (AdminActionRow candidate : candidates) {
            if (!requireLaneFree || !supportLaneOccupied.contains(candidate.targetUserId())) {
                return new TicketTarget(candidate.targetUserId(), candidate.id());
            }
        }
        TicketTarget fallback =
                resolveOrdinaryTarget(
                        ordinaryUserIds, supportLaneOccupied, requireLaneFree, random);
        return fallback == null ? null : new TicketTarget(fallback.userId(), null);
    }

    private TicketTarget resolveOrdinaryTarget(
            List<UUID> ordinaryUserIds,
            Set<UUID> laneOccupied,
            boolean requireLaneFree,
            Random random) {
        List<UUID> shuffled = new ArrayList<>(ordinaryUserIds);
        Collections.shuffle(shuffled, random);
        for (UUID candidate : shuffled) {
            if (!requireLaneFree || !laneOccupied.contains(candidate)) {
                return new TicketTarget(candidate, null);
            }
        }
        return null;
    }

    // Writes one category-loop ticket in its final resolved shape and, when a decision or an
    // escalation happened, the matching admin_actions row(s) - returns how many admin_actions rows
    // it wrote, for the run summary log.
    private int writeDecidedOrPendingTicket(
            String category,
            SupportTicketRequestEntry text,
            TicketTarget target,
            String source,
            String contactEmail,
            boolean isAppeal,
            UUID moderatorId,
            UUID adminId,
            Instant createdAt,
            StatusPlan statusPlan,
            SupportTicketPoolSeed pools,
            SeedTimeline timeline,
            Random random) {
        UUID ticketId = UUID.randomUUID();
        return writeTicketRow(
                ticketId,
                target.userId(),
                contactEmail,
                category,
                text.subject(),
                text.body(),
                source,
                target.adminActionId(),
                isAppeal,
                moderatorId,
                adminId,
                createdAt,
                statusPlan,
                pools,
                timeline,
                random);
    }

    // Shared insert path for both an ordinary/appeal ticket and a verification ticket: applies the
    // status-plan entry (claim/escalate/decide timestamps and actor attribution), inserts the
    // support_tickets row already in its final shape, and inserts every admin_actions row the
    // decision produces. Returns the number of admin_actions rows written.
    private int writeTicketRow(
            UUID ticketId,
            UUID userId,
            String contactEmail,
            String category,
            String subject,
            String body,
            String source,
            UUID adminActionId,
            boolean isAppeal,
            UUID moderatorId,
            UUID adminId,
            Instant createdAt,
            StatusPlan statusPlan,
            SupportTicketPoolSeed pools,
            SeedTimeline timeline,
            Random random) {
        UUID assignedTo = null;
        Instant assignedAt = null;
        UUID escalatedBy = null;
        Instant escalatedAt = null;
        String escalationReason = null;
        UUID respondedBy = null;
        Instant respondedAt = null;
        String staffResponse = null;
        String internalNote = null;
        int adminActionsWritten = 0;

        boolean decided =
                "answered".equals(statusPlan.status()) || "rejected".equals(statusPlan.status());
        boolean escalated = "escalated".equals(statusPlan.status()) || statusPlan.viaEscalation();

        if (escalated) {
            assignedTo = moderatorId;
            assignedAt = timeline.supportTicketDecidedAt(createdAt);
            escalatedBy = moderatorId;
            escalatedAt = timeline.supportTicketDecidedAt(assignedAt);
            escalationReason =
                    pools.escalationReasons().get(random.nextInt(pools.escalationReasons().size()));
            insertAdminAction(
                    moderatorId,
                    "escalate_support_ticket",
                    userId,
                    ticketId,
                    escalationReason,
                    escalatedAt);
            adminActionsWritten++;
        } else if ("in_progress".equals(statusPlan.status())) {
            assignedTo = moderatorId;
            assignedAt = timeline.supportTicketDecidedAt(createdAt);
        }

        if (decided) {
            UUID decider = (isAppeal || statusPlan.viaEscalation()) ? adminId : moderatorId;
            if (assignedTo == null) {
                // Not escalated first: the decider claims and decides in the same pass.
                assignedTo = decider;
                assignedAt = timeline.supportTicketDecidedAt(createdAt);
            } else if (!assignedTo.equals(decider)) {
                // Escalated to admin: the final decider differs from the moderator who claimed and
                // escalated it, matching how an escalated ticket changes hands in production.
                assignedTo = decider;
            }
            // Anchored on escalatedAt (already strictly after assignedAt) when escalated, otherwise
            // on assignedAt itself, so the chain createdAt < assignedAt < [escalatedAt <]
            // respondedAt
            // holds by construction rather than from two independent draws off the same base.
            Instant decisionBase = escalated ? escalatedAt : assignedAt;
            respondedBy = decider;
            respondedAt = timeline.supportTicketDecidedAt(decisionBase);
            boolean approved = "answered".equals(statusPlan.status());
            List<String> responsePool =
                    approved ? pools.staffResponsesApproved() : pools.staffResponsesRejected();
            staffResponse = responsePool.get(random.nextInt(responsePool.size()));
            internalNote =
                    approved
                            ? "Reviewed and closed as answered - see staff_response for the reasoning"
                                    + " shared with the user."
                            : "Reviewed and closed as rejected - original decision stands.";
            insertAdminAction(
                    decider,
                    approved ? "respond_support_ticket" : "reject_support_ticket",
                    userId,
                    ticketId,
                    staffResponse,
                    respondedAt);
            adminActionsWritten++;
        }

        jdbc.update(
                INSERT_TICKET_SQL,
                ticketId,
                userId,
                contactEmail,
                category,
                subject,
                body,
                statusPlan.status(),
                source,
                adminActionId,
                assignedTo,
                toTimestamp(assignedAt),
                staffResponse,
                internalNote,
                respondedBy,
                toTimestamp(respondedAt),
                escalatedBy,
                toTimestamp(escalatedAt),
                escalationReason,
                Timestamp.from(createdAt),
                // No UPDATE ever follows this INSERT, so updated_at is written once, matching
                // created_at at the point the ticket first reached this exact final state -
                // trg_support_tickets_updated_at fires on UPDATE only and never touches this row.
                Timestamp.from(
                        respondedAt != null
                                ? respondedAt
                                : escalatedAt != null
                                        ? escalatedAt
                                        : assignedAt != null ? assignedAt : createdAt));

        return adminActionsWritten;
    }

    private void writePendingConfirmationTicket(
            String category, SupportTicketRequestEntry text, SeedTimeline timeline, Random random) {
        UUID ticketId = UUID.randomUUID();
        String contactEmail = "external.reporter" + random.nextInt(1_000_000) + "@example.com";
        Instant createdAt =
                timeline.supportTicketCreatedAt(timeline.referenceNow().minusSeconds(1));
        jdbc.update(
                INSERT_TICKET_SQL,
                ticketId,
                null,
                contactEmail,
                category,
                text.subject(),
                text.body(),
                "pending_confirmation",
                "public_form",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt));
    }

    private void writeVerificationRequestRow(
            UUID ticketId, UUID userId, VerificationTicketSeed claim, Instant createdAt) {
        List<String> evidenceValues =
                List.of(
                        nullToEmpty(claim.evidenceWebsite()),
                        nullToEmpty(claim.evidenceOtherProfile()),
                        nullToEmpty(claim.evidenceEmailDomain()),
                        nullToEmpty(claim.evidencePublishedWork()),
                        nullToEmpty(claim.evidencePress()),
                        nullToEmpty(claim.evidenceOfficialListing()),
                        nullToEmpty(claim.evidenceNote()));
        short evidenceFieldCount =
                (short) evidenceValues.stream().filter(v -> !v.isBlank()).count();
        jdbc.update(
                INSERT_VERIFICATION_REQUEST_SQL,
                ticketId,
                userId,
                claim.categoryKey(),
                claim.claimedName(),
                claim.evidenceWebsite(),
                claim.evidenceOtherProfile(),
                claim.evidenceEmailDomain(),
                claim.evidencePublishedWork(),
                claim.evidencePress(),
                claim.evidenceOfficialListing(),
                claim.evidenceNote(),
                evidenceFieldCount,
                Timestamp.from(createdAt));
    }

    private void insertAdminAction(
            UUID adminId,
            String actionType,
            UUID targetUserId,
            UUID ticketId,
            String reason,
            Instant createdAt) {
        jdbc.update(
                INSERT_ADMIN_ACTION_SQL,
                UUID.randomUUID(),
                adminId,
                actionType,
                targetUserId,
                "support_ticket",
                ticketId,
                null,
                reason,
                Timestamp.from(createdAt));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Map<UUID, Instant> fetchUserCreatedAt() {
        Map<UUID, Instant> result = new HashMap<>();
        jdbc.query(
                "SELECT id, created_at FROM users",
                (RowCallbackHandler)
                        rs ->
                                result.put(
                                        (UUID) rs.getObject("id"),
                                        rs.getTimestamp("created_at").toInstant()));
        return result;
    }

    private Map<UUID, String> fetchUserEmail() {
        Map<UUID, String> result = new HashMap<>();
        jdbc.query(
                "SELECT id, email FROM users",
                (RowCallbackHandler)
                        rs -> result.put((UUID) rs.getObject("id"), rs.getString("email")));
        return result;
    }

    // Every admin_actions row targeting a specific account, grouped by action_type - the pool
    // resolveAppealTarget draws from to link an appeal ticket back to the real punitive decision it
    // appeals.
    private Map<String, List<AdminActionRow>> fetchPunitiveAdminActions() {
        Map<String, List<AdminActionRow>> result = new HashMap<>();
        jdbc.query(
                "SELECT id, action_type::text AS action_type, target_user_id FROM admin_actions"
                        + " WHERE target_user_id IS NOT NULL",
                rs -> {
                    String actionType = rs.getString("action_type");
                    result.computeIfAbsent(actionType, key -> new ArrayList<>())
                            .add(
                                    new AdminActionRow(
                                            (UUID) rs.getObject("id"),
                                            (UUID) rs.getObject("target_user_id")));
                });
        return result;
    }

    private UUID requireUser(Map<String, UUID> usersByUsername, String username) {
        UUID userId = usersByUsername.get(username);
        if (userId == null) {
            throw new IllegalStateException(
                    "SupportSeedWriter: fixed QA account '"
                            + username
                            + "' does not exist in users.json");
        }
        return userId;
    }

    private record CategoryPlan(String category, int count, List<String> sourceActionTypes) {}

    private record StatusPlan(String status, boolean viaEscalation) {}

    private record TicketTarget(UUID userId, UUID adminActionId) {}

    private record AdminActionRow(UUID id, UUID targetUserId) {}
}
