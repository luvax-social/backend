package com.app.common.seed.writer;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.app.common.seed.loader.SeedContent;
import com.app.common.seed.model.BadgeRevocationSeed;
import com.app.common.seed.model.BadgeSeed;
import com.app.common.seed.model.LegacyGrantSeed;
import com.app.common.seed.model.VerificationTicketSeed;
import com.app.common.seed.time.SeedTimeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds {@code user_verifications} and the three verification entries in the moderation audit log.
 *
 * <p>Runs after {@link SupportSeedWriter} and {@link ModerationSeedWriter} because a granted badge
 * points at the request that produced it and at the audit row that records the decision, and both
 * of those are written by those two.
 *
 * <p>Two grant lanes. A ticket grant answers an approved {@code verification_request} and carries
 * its {@code request_ticket_id}; a legacy grant predates the request flow and leaves that column
 * null, which nothing else in the seed exercises.
 *
 * <p>Revocation is soft, per {@code support/DATA_RULES.md} section 9: the row stays with {@code
 * revoked_at} set so a moderator reviewing a resubmission can see what was granted before and why
 * it was withdrawn. The {@code system} actor exists for a withdrawal driven by an account status
 * change rather than by a person, and its audit row carries a null {@code admin_id}, matching the
 * convention the discipline ladder's automatic strike already uses.
 *
 * <p>This writer never touches {@code users.is_verified} or {@code users.verified_category}. Both
 * are derived by {@code trg_user_verification_sync} from the rows written here.
 */
@Slf4j
@Service
@Profile("seed & (dev | prod)")
@RequiredArgsConstructor
public class VerificationSeedWriter {

    private static final String INSERT_VERIFICATION_SQL =
            "INSERT INTO user_verifications (id, user_id, category_key, request_ticket_id,"
                    + " granted_by, granted_at, granted_action_id) VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static final String REVOKE_VERIFICATION_SQL =
            "UPDATE user_verifications SET revoked_at = ?, revoked_by = ?, revocation_reason = ?,"
                    + " revocation_actor = ?::verification_revocation_actor, revoked_action_id = ?"
                    + " WHERE id = ?";

    private static final String INSERT_ADMIN_ACTION_SQL =
            "INSERT INTO admin_actions (id, admin_id, action_type, target_user_id,"
                    + " target_entity_type, target_entity_id, reason, created_at) VALUES (?, ?,"
                    + " ?::admin_action_type, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;

    /**
     * Writes every grant, refusal and revocation declared in {@code verification/badges.json}.
     *
     * @param verificationTicketIdByUsername the ticket ids {@link SupportSeedWriter#write} minted,
     *     keyed by the account the claim names
     */
    public void write(
            SeedContent content,
            Map<String, UUID> usersByUsername,
            Map<String, UUID> verificationTicketIdByUsername,
            SeedTimeline timeline) {
        BadgeSeed badges = content.badges();
        UUID moderatorId = requireUser(usersByUsername, "mod1");

        Map<String, UUID> verificationIdByUsername = new HashMap<>();
        int grants = 0;
        int rejections = 0;

        for (VerificationTicketSeed claim : badges.verificationTickets()) {
            UUID subjectId = requireUser(usersByUsername, claim.username());
            UUID ticketId = verificationTicketIdByUsername.get(claim.username());
            if (ticketId == null) {
                throw new IllegalStateException(
                        "VerificationSeedWriter: no verification ticket was written for '"
                                + claim.username()
                                + "'");
            }
            Instant decidedAt = timeline.supportTicketDecidedAt(timeline.referenceNow());

            if ("answered".equals(claim.outcome())) {
                UUID actionId =
                        writeAuditRow(
                                moderatorId,
                                "grant_verification",
                                subjectId,
                                ticketId,
                                "Verified as " + claim.claimedName() + ".",
                                decidedAt);
                UUID verificationId =
                        insertGrant(
                                subjectId,
                                claim.categoryKey(),
                                ticketId,
                                moderatorId,
                                decidedAt,
                                actionId);
                verificationIdByUsername.put(claim.username(), verificationId);
                grants++;
            } else if ("rejected".equals(claim.outcome())) {
                writeAuditRow(
                        moderatorId,
                        "reject_verification",
                        subjectId,
                        ticketId,
                        "Evidence supplied does not meet the bar for " + claim.categoryKey() + ".",
                        decidedAt);
                rejections++;
            }
        }

        for (LegacyGrantSeed grant : badges.legacyGrants()) {
            UUID subjectId = requireUser(usersByUsername, grant.username());
            UUID grantedBy = requireUser(usersByUsername, grant.grantedBy());
            Instant grantedAt = daysBeforeReference(timeline, grant.grantedOffsetDays());
            UUID actionId =
                    writeAuditRow(
                            grantedBy,
                            "grant_verification",
                            subjectId,
                            null,
                            "Verified before the request flow existed; granted directly.",
                            grantedAt);
            // request_ticket_id stays null here on purpose: this grant answers no request, and
            // that nullable path has no other exercise anywhere in the seed.
            UUID verificationId =
                    insertGrant(
                            subjectId, grant.categoryKey(), null, grantedBy, grantedAt, actionId);
            verificationIdByUsername.put(grant.username(), verificationId);
            grants++;
        }

        int revocations = 0;
        for (BadgeRevocationSeed revocation : badges.revocations()) {
            UUID verificationId = verificationIdByUsername.get(revocation.username());
            if (verificationId == null) {
                throw new IllegalStateException(
                        "VerificationSeedWriter: revoking a badge never granted to '"
                                + revocation.username()
                                + "'");
            }
            UUID subjectId = requireUser(usersByUsername, revocation.username());
            boolean bySystem = "system".equals(revocation.actor());
            // A system revocation follows an account status change rather than a decision, so it
            // names no actor at all - on the badge row and on its audit row alike. The
            // user_verifications_system_has_no_actor CHECK enforces the first half of that.
            UUID revokedBy = bySystem ? null : requireUser(usersByUsername, revocation.revokedBy());
            Instant revokedAt = daysBeforeReference(timeline, revocation.revokedOffsetDays());

            UUID actionId =
                    writeAuditRow(
                            revokedBy,
                            "revoke_verification",
                            subjectId,
                            null,
                            revocation.reason(),
                            revokedAt);
            jdbc.update(
                    REVOKE_VERIFICATION_SQL,
                    Timestamp.from(revokedAt),
                    revokedBy,
                    revocation.reason(),
                    revocation.actor(),
                    actionId,
                    verificationId);
            revocations++;
        }

        log.info(
                "[seed] verification: {} grants ({} still active), {} rejections, {} revocations",
                grants,
                grants - revocations,
                rejections,
                revocations);
        assertNoBadgeOnDisciplinedAccount();
    }

    /**
     * Fails the run if a badge is still active on a suspended or banned account.
     *
     * <p>{@code VerificationService.applyStatusChange} withdraws a badge whenever an account is
     * suspended or banned, so seeded data that left one in place would contradict an invariant the
     * running application maintains. Deactivated is deliberately not checked: that status retains
     * the badge.
     */
    private void assertNoBadgeOnDisciplinedAccount() {
        Integer offenders =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM users u JOIN user_verifications v ON v.user_id = u.id"
                                + " WHERE v.revoked_at IS NULL AND u.status IN ('suspended', 'banned')",
                        Integer.class);
        if (offenders != null && offenders > 0) {
            throw new IllegalStateException(
                    "Seed wrote "
                            + offenders
                            + " active badge(s) on suspended or banned accounts; a status change"
                            + " withdraws a badge, so the seeded data contradicts itself");
        }
    }

    private UUID insertGrant(
            UUID userId,
            String categoryKey,
            UUID ticketId,
            UUID grantedBy,
            Instant grantedAt,
            UUID actionId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                INSERT_VERIFICATION_SQL,
                id,
                userId,
                categoryKey,
                ticketId,
                grantedBy,
                Timestamp.from(grantedAt),
                actionId);
        return id;
    }

    private UUID writeAuditRow(
            UUID actorId,
            String actionType,
            UUID targetUserId,
            UUID ticketId,
            String reason,
            Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                INSERT_ADMIN_ACTION_SQL,
                id,
                actorId,
                actionType,
                targetUserId,
                ticketId == null ? null : "support_ticket",
                ticketId,
                reason,
                Timestamp.from(at));
        return id;
    }

    private Instant daysBeforeReference(SeedTimeline timeline, int offsetDays) {
        return timeline.referenceNow().plus(offsetDays, ChronoUnit.DAYS);
    }

    private UUID requireUser(Map<String, UUID> usersByUsername, String username) {
        UUID id = usersByUsername.get(username);
        if (id == null) {
            throw new IllegalStateException(
                    "VerificationSeedWriter: verification/badges.json names unknown account '"
                            + username
                            + "'");
        }
        return id;
    }
}
