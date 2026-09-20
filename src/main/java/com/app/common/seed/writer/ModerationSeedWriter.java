package com.app.common.seed.writer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.app.common.seed.loader.SeedContent;
import com.app.common.seed.model.ConversationSeed;
import com.app.common.seed.model.ModerationCaseSeed;
import com.app.common.seed.model.ModerationSupplementaryAction;
import com.app.common.seed.model.ModerationSupplementaryReport;
import com.app.common.seed.model.PostSeed;
import com.app.common.seed.model.UserSeed;
import com.app.common.seed.time.SeedTimeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds {@code reports} (~180), {@code admin_actions}, {@code user_warnings} (27) and {@code
 * user_strikes} (14) - every {@code warn_user}/{@code issue_strike} action the source content
 * actually scripts; see this class's "Background reports" note below for why {@code reports} alone
 * is padded beyond its literal content) from {@code moderation_cases.json}'s 6 narrative cases plus
 * its standalone {@code supplementary_actions} and {@code supplementary_reports} arrays, applying
 * every action's side effect (post/comment/story/message removal or restore, warning/strike
 * issuance or revocation, suspension expiry) to the row it targets.
 *
 * <p><b>Timeline translation</b>: each case's {@code timeline} array carries real ISO-8601
 * timestamps authored relative to each other, not to the seed run's own {@code referenceNow}. This
 * writer never uses those timestamps directly - it computes the span from the case's first event to
 * its last, asks {@link SeedTimeline#moderationCaseStart(Duration)} for a translated start instant,
 * then re-applies each event's own offset from the first event on top of that start. A pure
 * translation preserves the original relative order exactly, which is what makes "an {@code
 * admin_actions} row is never earlier than the report that triggered it" hold by construction:
 * every case's {@code report_submitted} events precede its {@code admin_action} events in the
 * source JSON, so they still do after translation. The {@code supplementary_actions} and {@code
 * supplementary_reports} arrays are combined into one shared pseudo-case timeline the same way, per
 * {@link SeedTimeline#moderationCaseStart}'s own Javadoc.
 *
 * <p><b>The one active-suspension case</b>: {@code case_01_spam_escalation}'s outcome is that
 * {@code user_suspended}'s 7-day suspension is still active as of {@code referenceNow} - this is
 * the one case whose start cannot be picked by {@link SeedTimeline#moderationCaseStart}, since that
 * method only guarantees every translated instant lands in the past. It uses {@link
 * SeedTimeline#moderationCaseStartWithActiveAnchor} instead, anchored on the case's {@code
 * suspend_user} event, which is what makes the suspension's expiry land strictly after {@code
 * referenceNow} by construction. No other case or supplementary action carries an outcome that
 * depends on the seed run's "now", so this is the only special case.
 *
 * <p><b>Synthetic targets</b>: {@code moderation_cases.json} references three kinds of target that
 * do not exist as literal rows anywhere else in the seed content - {@code target_story_ref} (a
 * narrative id resolved to "any story this username owns", guaranteed non-empty by {@link
 * StorySeedWriter}'s reserved story-owner slots), {@code target_comment_ref} (a {@code
 * postSeedId#label} pair resolved by creating a real comment on that post the first time any event
 * references it, memoized by ref for every later event that reuses it), and {@code
 * target_conversation_id} + {@code target_message_index} (resolved back to a real conversation via
 * {@link MessageSeedWriter#directPairKey}, then indexed into that conversation's messages ordered
 * by {@code created_at} - an order {@link SeedTimeline#messageCreatedAt} guarantees matches the
 * scripted array order). Migration V79 added {@code admin_action_type}'s 27th value, {@code
 * revoke_session}, which is exactly what five {@code supplementary_actions} entries use; this
 * writer records those the same as every other {@code admin_action}, with no further side-effect
 * table write, since no seeded table tracks individual live sessions.
 *
 * <p><b>Background reports</b>: the 6 cases' {@code report_submitted} events plus {@code
 * supplementary_reports} total only ~40 rows, well short of this writer's ~180 target, and the
 * narrative content alone never exercises {@code report_type = 'user'} at all and leaves {@code
 * 'story'}/{@code 'message'} at zero and {@code 'comment'} short of {@link
 * com.app.common.seed.SeedRunner}'s per-value floor - a plausible report queue needs more
 * day-to-day noise than six narrative cases script anyway. The remainder is filled by {@link
 * #writeBackgroundReports}, drawing a report type from a weighted list ({@code
 * BACKGROUND_REPORT_TYPES}, {@code post}-heavy but every other declared value represented) and a
 * random target of that type never owned by the drawing reporter (matching {@code
 * report/DATA_RULES.md}'s self-report rule) with a fixed-seed {@link Random} independent of {@link
 * SeedTimeline}'s own stream, timestamped via {@link SeedTimeline#likeOrSaveCreatedAt} off the
 * target's own {@code created_at} the same way a like or a save is. Every report this writer
 * inserts - narrative, supplementary or background - is tracked in one {@code (reporter, type,
 * entity)} key set so a background draw can never collide with {@code
 * uq_reports_reporter_type_entity} (V30).
 *
 * <p><b>Row-at-a-time inserts</b>: unlike every other writer in this package, this one does not
 * build one {@code List<Object[]>} per table and batch it at the end. A warning references the
 * {@code admin_actions} row that explains it, a strike references the same, and a report's
 * resolution can only be written once the report itself has an id - every row here depends on an id
 * minted by a row inserted moments earlier in the same case, so the two-phase batch pattern {@link
 * MessageSeedWriter} uses does not fit. Row counts are small enough (low hundreds total) that
 * per-row {@code jdbc.update} calls cost nothing observable.
 */
@Slf4j
@Service
@Profile("seed & (dev | prod)")
@RequiredArgsConstructor
public class ModerationSeedWriter {

    private static final String ACTIVE_SUSPENSION_CASE_ID = "case_01_spam_escalation";
    private static final String SUSPEND_USER_ACTION = "suspend_user";
    private static final String WARN_USER_ACTION = "warn_user";
    private static final String REVOKE_WARNING_ACTION = "revoke_warning";
    private static final String ISSUE_STRIKE_ACTION = "issue_strike";
    private static final String REVOKE_STRIKE_ACTION = "revoke_strike";
    private static final String UNSUSPEND_USER_ACTION = "unsuspend_user";
    private static final String REMOVE_POST_ACTION = "remove_post";
    private static final String RESTORE_POST_ACTION = "restore_post";
    private static final String REMOVE_COMMENT_ACTION = "remove_comment";
    private static final String RESTORE_COMMENT_ACTION = "restore_comment";
    private static final String REMOVE_STORY_ACTION = "remove_story";
    private static final String RESTORE_STORY_ACTION = "restore_story";
    private static final String REMOVE_MESSAGE_ACTION = "remove_message";
    private static final String RESTORE_MESSAGE_ACTION = "restore_message";
    private static final String CHANGE_USER_ROLE_ACTION = "change_user_role";
    private static final String CREATE_HASHTAG_ACTION = "create_hashtag";
    private static final String BAN_HASHTAG_ACTION = "ban_hashtag";
    private static final String UNBAN_HASHTAG_ACTION = "unban_hashtag";
    private static final String DELETE_HASHTAG_ACTION = "delete_hashtag";
    private static final String PIN_HASHTAG_ACTION = "pin_hashtag";
    private static final String UNPIN_HASHTAG_ACTION = "unpin_hashtag";
    private static final String DEFAULT_REASON_KEY = "other";
    private static final String PLACEHOLDER_COMMENT_TEXT =
            "[seed] comment content withheld from the moderation-case fixture";
    private static final long BACKGROUND_REPORT_RANDOM_SEED = 9_215_407L;
    private static final int TARGET_REPORT_COUNT = 180;
    private static final int MAX_BACKGROUND_ATTEMPTS_PER_REPORT = 20;
    private static final List<String> REPORT_REASONS =
            List.of(
                    "spam",
                    "nudity",
                    "violence",
                    "hate_speech",
                    "harassment",
                    "false_information",
                    "scam",
                    "other");
    private static final List<String> REPORT_STATUSES =
            List.of("pending", "pending", "reviewing", "resolved", "dismissed");
    // Weighted so 'post' still dominates (matching real-world report volume) while every other
    // declared report_type value gets enough background draws to clear SeedRunner's per-value
    // floor even after narrative content leaves 'comment' short and 'story'/'user'/'message'
    // empty - see this class's Javadoc on the admin_action_type catalog for the precedent this
    // follows.
    private static final List<String> BACKGROUND_REPORT_TYPES =
            List.of(
                    "post", "post", "post", "post", "post", "post", "post", "post", "post", "post",
                    "post", "post", "post", "post", "post", "post", "comment", "comment", "comment",
                    "comment", "comment", "comment", "story", "story", "story", "story", "story",
                    "story", "message", "message", "message", "message", "message", "message",
                    "user", "user", "user", "user", "user", "user");

    private static final String INSERT_REPORT_SQL =
            "INSERT INTO reports (id, reporter_id, report_type, report_reason, entity_id,"
                    + " status, reviewed_by, reviewed_at, resolution_note, escalated_by,"
                    + " escalated_at, escalation_reason, created_at) VALUES (?, ?, ?::report_type,"
                    + " ?::report_reason, ?, ?::report_status, ?, ?, ?, ?, ?, ?, ?)";
    private static final String UPDATE_REPORT_STATUS_SQL =
            "UPDATE reports SET status = ?::report_status, reviewed_by = COALESCE(?, reviewed_by),"
                    + " reviewed_at = COALESCE(?, reviewed_at), resolution_note ="
                    + " COALESCE(?, resolution_note) WHERE id = ?";
    private static final String UPDATE_REPORT_ESCALATION_SQL =
            "UPDATE reports SET escalated_by = ?, escalated_at = ?, escalation_reason = ?"
                    + " WHERE id = ?";
    private static final String INSERT_ADMIN_ACTION_SQL =
            "INSERT INTO admin_actions (id, admin_id, action_type, target_user_id,"
                    + " target_entity_type, target_entity_id, report_id, reason, created_at) VALUES"
                    + " (?, ?, ?::admin_action_type, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_WARNING_SQL =
            "INSERT INTO user_warnings (id, user_id, issued_by, reason_key, note, admin_action_id,"
                    + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
    private static final String REVOKE_WARNING_SQL =
            "UPDATE user_warnings SET revoked_at = ?, revoked_by = ? WHERE id = ("
                    + " SELECT id FROM user_warnings WHERE user_id = ? AND revoked_at IS NULL"
                    + " ORDER BY created_at DESC LIMIT 1)";
    private static final String INSERT_STRIKE_SQL =
            "INSERT INTO user_strikes (id, user_id, strike_number, admin_action_id, created_at)"
                    + " VALUES (?, ?, ?, ?, ?)";
    private static final String REVOKE_STRIKE_SQL =
            "UPDATE user_strikes SET revoked_at = ?, revoked_by = ? WHERE id = ("
                    + " SELECT id FROM user_strikes WHERE user_id = ? AND revoked_at IS NULL"
                    + " ORDER BY created_at DESC LIMIT 1)";
    private static final String UPDATE_SUSPENDED_UNTIL_SQL =
            "UPDATE users SET suspended_until = ? WHERE id = ?";
    private static final String UPDATE_USER_ROLE_SQL =
            "UPDATE users SET role = ?::user_role WHERE id = ?";
    private static final String UPDATE_POST_REMOVE_SQL =
            "UPDATE posts SET status_before_moderation = status, status = 'removed'::post_status,"
                    + " deleted_at = ? WHERE id = ?";
    private static final String UPDATE_POST_RESTORE_SQL =
            "UPDATE posts SET status = COALESCE(status_before_moderation, 'published'::post_status),"
                    + " status_before_moderation = NULL, deleted_at = NULL WHERE id = ?";
    private static final String UPDATE_COMMENT_ADMIN_REMOVED_AT_SQL =
            "UPDATE comments SET admin_removed_at = ? WHERE id = ?";
    private static final String UPDATE_STORY_ADMIN_REMOVED_AT_SQL =
            "UPDATE stories SET admin_removed_at = ? WHERE id = ?";
    private static final String UPDATE_MESSAGE_ADMIN_REMOVED_AT_SQL =
            "UPDATE messages SET admin_removed_at = ? WHERE id = ?";
    private static final String UPDATE_HASHTAG_STATUS_SQL =
            "UPDATE hashtags SET status = ?::hashtag_status WHERE id = ?";
    // pinned_at and pinned_by are written and cleared together because the hashtags_pin_pair
    // CHECK (V92) refuses a row holding one without the other.
    private static final String PIN_HASHTAG_SQL =
            "UPDATE hashtags SET pinned_at = ?, pinned_by = ? WHERE id = ?";
    private static final String UNPIN_HASHTAG_SQL =
            "UPDATE hashtags SET pinned_at = NULL, pinned_by = NULL WHERE id = ?";
    private static final String INSERT_COMMENT_SQL =
            "INSERT INTO comments (id, post_id, user_id, parent_id, root_id, depth, content,"
                    + " created_at) VALUES (?, ?, ?, NULL, ?, 0, ?, ?)";
    private static final String INSERT_HASHTAG_SQL =
            "INSERT INTO hashtags (id, name, status, created_at) VALUES (?, ?, 'active'::hashtag_status, ?)";

    private final JdbcTemplate jdbc;

    private final Map<String, UUID> commentIdByRef = new HashMap<>();
    private final Map<String, UUID> storyIdByOwnerUsername = new HashMap<>();
    private final Map<String, UUID> conversationIdBySeedId = new HashMap<>();
    private final Map<String, List<UUID>> messageIdsByConversationSeedId = new HashMap<>();
    private final Map<String, UUID> hashtagIdByName = new HashMap<>();
    private final Map<String, UUID> reportIdByRef = new HashMap<>();
    private final Map<UUID, Instant> postCreatedAtById = new HashMap<>();
    private final Map<UUID, Integer> strikeNumberByUser = new HashMap<>();
    private final Set<String> usedReportKeys = new HashSet<>();

    /**
     * Inserts every {@code reports}, {@code admin_actions}, {@code user_warnings} and {@code
     * user_strikes} row {@code moderation_cases.json} describes, applying each action's side effect
     * to the row it targets, and returns every generated {@code reports.id} for {@link
     * NotificationSeedWriter} and Task 9's enum-coverage cross-check.
     *
     * <p>Must run after {@link UserSeedWriter}, {@link PostSeedWriter}, {@link CommentSeedWriter},
     * {@link StorySeedWriter} and {@link MessageSeedWriter} - every synthetic target this writer
     * resolves is read back from tables those writers populate.
     *
     * @param usersByUsername username-to-id map produced by {@link UserSeedWriter#write}
     * @param postIdBySeedId seed-id-to-generated-id map produced by {@link PostSeedWriter#write}
     * @return every generated {@code reports.id}, in insertion order
     */
    public List<UUID> write(
            SeedContent content,
            Map<String, UUID> usersByUsername,
            Map<String, UUID> postIdBySeedId,
            SeedTimeline timeline) {
        loadPostCreatedAt();
        loadStoryOwners(content);

        List<UUID> allReportIds = new ArrayList<>();
        int adminActionCount = 0;
        int warningCount = 0;
        int strikeCount = 0;

        for (ModerationCaseSeed caseSeed : content.moderationCases()) {
            CaseTally tally =
                    processTimeline(
                            caseSeed.id(),
                            caseSeed.timeline(),
                            caseSeed.reportedContent(),
                            resolveActiveSuspendEvent(caseSeed),
                            usersByUsername,
                            postIdBySeedId,
                            content,
                            timeline);
            allReportIds.addAll(tally.reportIds());
            adminActionCount += tally.adminActions();
            warningCount += tally.warnings();
            strikeCount += tally.strikes();
        }

        CaseTally supplementaryTally =
                processSupplementary(
                        content.supplementaryModerationActions(),
                        content.supplementaryModerationReports(),
                        usersByUsername,
                        postIdBySeedId,
                        content,
                        timeline);
        allReportIds.addAll(supplementaryTally.reportIds());
        adminActionCount += supplementaryTally.adminActions();
        warningCount += supplementaryTally.warnings();
        strikeCount += supplementaryTally.strikes();

        allReportIds.addAll(
                writeBackgroundReports(content, postIdBySeedId, usersByUsername, timeline));

        log.info(
                "[seed] reports: {} rows written, admin_actions: {} rows written, user_warnings:"
                        + " {} rows written, user_strikes: {} rows written",
                allReportIds.size(),
                adminActionCount,
                warningCount,
                strikeCount);
        return allReportIds;
    }

    // Only case_01_spam_escalation's outcome depends on referenceNow ("expiry is in the future");
    // every other case and every supplementary action resolves to a fully-past, fully-closed
    // narrative, so this is a deliberate one-case special case rather than a generic mechanism.
    private Map<String, Object> resolveActiveSuspendEvent(ModerationCaseSeed caseSeed) {
        if (!ACTIVE_SUSPENSION_CASE_ID.equals(caseSeed.id())) {
            return null;
        }
        for (Map<String, Object> event : caseSeed.timeline()) {
            if ("admin_action".equals(event.get("event"))
                    && SUSPEND_USER_ACTION.equals(event.get("action_type"))) {
                return event;
            }
        }
        return null;
    }

    private CaseTally processTimeline(
            String caseId,
            List<Map<String, Object>> timelineEvents,
            List<Map<String, Object>> reportedContent,
            Map<String, Object> activeSuspendEvent,
            Map<String, UUID> usersByUsername,
            Map<String, UUID> postIdBySeedId,
            SeedContent content,
            SeedTimeline timeline) {
        if (timelineEvents.isEmpty()) {
            return new CaseTally(List.of(), 0, 0, 0);
        }
        Instant firstOriginalAt = parseAt(timelineEvents.get(0));
        Instant lastOriginalAt = parseAt(timelineEvents.get(timelineEvents.size() - 1));
        Duration totalSpan = Duration.between(firstOriginalAt, lastOriginalAt);

        Instant start;
        if (activeSuspendEvent != null) {
            Instant anchorOriginalAt = parseAt(activeSuspendEvent);
            Duration offsetToActive = Duration.between(firstOriginalAt, anchorOriginalAt);
            int durationDays = intField(activeSuspendEvent, "duration_days", 7);
            start =
                    timeline.moderationCaseStartWithActiveAnchor(
                            offsetToActive, Duration.ofDays(durationDays));
        } else {
            start = timeline.moderationCaseStart(totalSpan);
        }

        List<UUID> caseReportIds = new ArrayList<>();
        int reportedContentIndex = 0;
        int adminActionCount = 0;
        int warningCount = 0;
        int strikeCount = 0;

        for (Map<String, Object> event : timelineEvents) {
            Instant translatedAt = start.plus(Duration.between(firstOriginalAt, parseAt(event)));
            String eventKind = (String) event.get("event");
            switch (eventKind) {
                case "report_submitted" -> {
                    if (reportedContent != null && !reportedContent.isEmpty()) {
                        Map<String, Object> entity =
                                reportedContent.get(reportedContentIndex % reportedContent.size());
                        reportedContentIndex++;
                        UUID reportId =
                                insertReport(
                                        event,
                                        entity,
                                        translatedAt,
                                        usersByUsername,
                                        postIdBySeedId,
                                        content,
                                        timeline);
                        if (reportId != null) {
                            caseReportIds.add(reportId);
                        }
                    }
                }
                case "report_status_changed" ->
                        applyReportStatusChanged(
                                event, translatedAt, usersByUsername, caseReportIds);
                case "admin_action" -> {
                    boolean applied =
                            applyAdminAction(
                                    event,
                                    translatedAt,
                                    usersByUsername,
                                    postIdBySeedId,
                                    content,
                                    timeline,
                                    caseReportIds.isEmpty() ? null : caseReportIds.get(0));
                    if (applied) {
                        adminActionCount++;
                        if (WARN_USER_ACTION.equals(event.get("action_type"))) {
                            warningCount++;
                        } else if (ISSUE_STRIKE_ACTION.equals(event.get("action_type"))) {
                            strikeCount++;
                        }
                    }
                }
                default ->
                        log.warn(
                                "[seed] moderation case '{}': unrecognized timeline event '{}',"
                                        + " skipped",
                                caseId,
                                eventKind);
            }
        }
        return new CaseTally(caseReportIds, adminActionCount, warningCount, strikeCount);
    }

    // Combines supplementary_actions and supplementary_reports into one shared pseudo-case
    // timeline, exactly as SeedTimeline.moderationCaseStart's own Javadoc describes: one
    // translated start, every original event's offset from the earliest original "at" re-applied
    // on top of it, so the whole block's relative order survives the translation intact.
    private CaseTally processSupplementary(
            List<ModerationSupplementaryAction> actions,
            List<ModerationSupplementaryReport> reports,
            Map<String, UUID> usersByUsername,
            Map<String, UUID> postIdBySeedId,
            SeedContent content,
            SeedTimeline timeline) {
        List<SupplementaryEvent> events = new ArrayList<>();
        for (ModerationSupplementaryAction action : actions) {
            events.add(new SupplementaryEvent(Instant.parse(action.at()), action, null));
        }
        for (ModerationSupplementaryReport report : reports) {
            events.add(new SupplementaryEvent(Instant.parse(report.at()), null, report));
        }
        if (events.isEmpty()) {
            return new CaseTally(List.of(), 0, 0, 0);
        }
        events.sort((a, b) -> a.originalAt().compareTo(b.originalAt()));

        Instant firstOriginalAt = events.get(0).originalAt();
        Instant lastOriginalAt = events.get(events.size() - 1).originalAt();
        Instant start =
                timeline.moderationCaseStart(Duration.between(firstOriginalAt, lastOriginalAt));

        List<UUID> reportIds = new ArrayList<>();
        int adminActionCount = 0;
        int warningCount = 0;
        int strikeCount = 0;

        for (SupplementaryEvent event : events) {
            Instant translatedAt =
                    start.plus(Duration.between(firstOriginalAt, event.originalAt()));
            if (event.action() != null) {
                boolean applied =
                        applySupplementaryAction(
                                event.action(),
                                translatedAt,
                                usersByUsername,
                                postIdBySeedId,
                                content,
                                timeline);
                if (applied) {
                    adminActionCount++;
                    if (WARN_USER_ACTION.equals(event.action().actionType())) {
                        warningCount++;
                    } else if (ISSUE_STRIKE_ACTION.equals(event.action().actionType())) {
                        strikeCount++;
                    }
                }
            } else {
                UUID reportId =
                        insertSupplementaryReport(
                                event.report(),
                                translatedAt,
                                usersByUsername,
                                postIdBySeedId,
                                content);
                if (reportId != null) {
                    reportIds.add(reportId);
                }
            }
        }
        return new CaseTally(reportIds, adminActionCount, warningCount, strikeCount);
    }

    private UUID insertReport(
            Map<String, Object> event,
            Map<String, Object> reportedContentEntry,
            Instant createdAt,
            Map<String, UUID> usersByUsername,
            Map<String, UUID> postIdBySeedId,
            SeedContent content,
            SeedTimeline timeline) {
        String reporterUsername = (String) event.get("reporter");
        UUID reporterId = requireUser(usersByUsername, reporterUsername, "report_submitted");
        String reportType = (String) reportedContentEntry.get("type");
        UUID entityId =
                resolveEntity(reportType, reportedContentEntry, postIdBySeedId, content, timeline);
        if (entityId == null || !markReportKeyUsed(reporterId, reportType, entityId)) {
            log.warn(
                    "[seed] moderation: report_submitted by '{}' could not resolve a unique"
                            + " ({} , {}) target, skipped",
                    reporterUsername,
                    reportType,
                    reportedContentEntry);
            return null;
        }

        UUID reportId = UUID.randomUUID();
        jdbc.update(
                INSERT_REPORT_SQL,
                reportId,
                reporterId,
                reportType,
                event.get("report_reason"),
                entityId,
                event.get("report_status"),
                null,
                null,
                null,
                null,
                null,
                null,
                java.sql.Timestamp.from(createdAt));
        return reportId;
    }

    // report_status_changed carries no report identifier of its own - every report_submitted event
    // for a case targets the same underlying complaint the case narrates, so a status transition
    // applies to every report generated by the case so far, matching the narrative's own language
    // ("picking up the four spam reports against this account together").
    private void applyReportStatusChanged(
            Map<String, Object> event,
            Instant translatedAt,
            Map<String, UUID> usersByUsername,
            List<UUID> caseReportIds) {
        if (caseReportIds.isEmpty()) {
            return;
        }
        String status = (String) event.get("report_status");
        String actor = (String) event.get("actor");
        UUID actorId = actor == null ? null : usersByUsername.get(actor);
        String note = (String) event.get("note");
        boolean terminal =
                "resolved".equals(status)
                        || "dismissed".equals(status)
                        || "escalated".equals(status);
        for (UUID reportId : caseReportIds) {
            jdbc.update(
                    UPDATE_REPORT_STATUS_SQL,
                    status,
                    actorId,
                    actorId == null ? null : java.sql.Timestamp.from(translatedAt),
                    terminal ? note : null,
                    reportId);
        }
    }

    private boolean applyAdminAction(
            Map<String, Object> event,
            Instant translatedAt,
            Map<String, UUID> usersByUsername,
            Map<String, UUID> postIdBySeedId,
            SeedContent content,
            SeedTimeline timeline,
            UUID caseReportId) {
        String actionType = (String) event.get("action_type");

        String actorUsername = (String) event.get("actor");
        UUID actorId = actorUsername == null ? null : usersByUsername.get(actorUsername);
        String targetUsername = (String) event.get("target_user");
        UUID targetUserId = targetUsername == null ? null : usersByUsername.get(targetUsername);
        // admin_actions.reason is documented as "Human-readable moderation reason"
        // (AdminActionResponse), so the authored narrative note is what belongs here; reason_key
        // and reason are short category codes used only as a fallback when a case has no note.
        String reason =
                firstNonNull(
                        (String) event.get("note"),
                        firstNonNull(
                                (String) event.get("reason_key"), (String) event.get("reason")));

        TargetEntity target =
                resolveActionTarget(
                        actionType, event, targetUserId, postIdBySeedId, content, timeline);

        UUID adminActionId = UUID.randomUUID();
        jdbc.update(
                INSERT_ADMIN_ACTION_SQL,
                adminActionId,
                actorId,
                actionType,
                targetUserId,
                target == null ? null : target.entityType(),
                target == null ? null : target.entityId(),
                caseReportId,
                reason,
                java.sql.Timestamp.from(translatedAt));

        applySideEffect(
                actionType,
                event,
                translatedAt,
                targetUserId,
                target,
                adminActionId,
                usersByUsername);
        if ("escalate_report".equals(actionType) && caseReportId != null) {
            jdbc.update(
                    UPDATE_REPORT_ESCALATION_SQL,
                    actorId,
                    java.sql.Timestamp.from(translatedAt),
                    event.get("escalation_reason"),
                    caseReportId);
        }
        return true;
    }

    private boolean applySupplementaryAction(
            ModerationSupplementaryAction action,
            Instant translatedAt,
            Map<String, UUID> usersByUsername,
            Map<String, UUID> postIdBySeedId,
            SeedContent content,
            SeedTimeline timeline) {
        UUID actorId = action.actor() == null ? null : usersByUsername.get(action.actor());
        UUID targetUserId =
                action.targetUser() == null ? null : usersByUsername.get(action.targetUser());
        // See applyAdminAction's identical precedence: admin_actions.reason is human-readable text,
        // so the authored note wins over the short reason_key/reason category codes.
        String reason =
                firstNonNull(action.note(), firstNonNull(action.reasonKey(), action.reason()));

        Map<String, Object> eventView = supplementaryActionAsEventView(action);
        TargetEntity target =
                resolveActionTarget(
                        action.actionType(),
                        eventView,
                        targetUserId,
                        postIdBySeedId,
                        content,
                        timeline);
        // Only dismiss_report/resolve_report entries ever carry a targetReportRef; every other
        // action type's admin_actions.report_id stays null, as it always did before this field
        // existed.
        UUID reportId =
                action.targetReportRef() == null
                        ? null
                        : reportIdByRef.get(action.targetReportRef());

        UUID adminActionId = UUID.randomUUID();
        jdbc.update(
                INSERT_ADMIN_ACTION_SQL,
                adminActionId,
                actorId,
                action.actionType(),
                targetUserId,
                target == null ? null : target.entityType(),
                target == null ? null : target.entityId(),
                reportId,
                reason,
                java.sql.Timestamp.from(translatedAt));

        applySideEffect(
                action.actionType(),
                eventView,
                translatedAt,
                targetUserId,
                target,
                adminActionId,
                usersByUsername);
        return true;
    }

    // ModerationSupplementaryAction is a record with named accessors, while the case-timeline path
    // works off Map<String,Object> - this view lets applySideEffect/resolveActionTarget stay
    // shared between both call sites rather than each growing its own duplicate branch tree.
    private Map<String, Object> supplementaryActionAsEventView(
            ModerationSupplementaryAction action) {
        Map<String, Object> view = new HashMap<>();
        view.put("duration_days", action.durationDays());
        view.put("target_post", action.targetPost());
        view.put("target_comment_ref", action.targetCommentRef());
        view.put("target_story_ref", action.targetStoryRef());
        view.put("target_user", action.targetUser());
        view.put("target_conversation_id", action.targetConversationId());
        view.put("target_message_index", action.targetMessageIndex());
        view.put("hashtag_name", action.hashtagName());
        view.put("actor", action.actor());
        view.put("to_role", action.toRole());
        // Read by applySideEffect's WARN_USER_ACTION case (user_warnings.reason_key/note) - missing
        // here silently fell back to the generic default for every supplementary warn_user action.
        view.put("reason_key", action.reasonKey());
        view.put("note", action.note());
        return view;
    }

    private UUID insertSupplementaryReport(
            ModerationSupplementaryReport report,
            Instant createdAt,
            Map<String, UUID> usersByUsername,
            Map<String, UUID> postIdBySeedId,
            SeedContent content) {
        UUID reporterId = usersByUsername.get(report.reporter());
        if (reporterId == null) {
            log.warn(
                    "[seed] moderation: supplementary report reporter '{}' not found, skipped",
                    report.reporter());
            return null;
        }
        UUID entityId = resolveAnyPostByAuthor(report.targetUsername(), postIdBySeedId, content);
        if (entityId == null) {
            log.warn(
                    "[seed] moderation: supplementary report target '{}' owns no post, skipped",
                    report.targetUsername());
            return null;
        }
        if (!markReportKeyUsed(reporterId, report.reportType(), entityId)) {
            log.warn(
                    "[seed] moderation: supplementary report by '{}' against '{}' duplicates an"
                            + " already-used (reporter, type, entity) key, skipped",
                    report.reporter(),
                    report.targetUsername());
            return null;
        }
        UUID reportId = UUID.randomUUID();
        jdbc.update(
                INSERT_REPORT_SQL,
                reportId,
                reporterId,
                report.reportType(),
                report.reportReason(),
                entityId,
                report.reportStatus(),
                null,
                null,
                null,
                null,
                null,
                null,
                java.sql.Timestamp.from(createdAt));
        if (report.id() != null) {
            reportIdByRef.put(report.id(), reportId);
        }
        return reportId;
    }

    // Fills the gap between the ~40 narrative/supplementary reports and this writer's ~180
    // target with plausible day-to-day noise, drawn from a weighted report_type list so every
    // declared report_type value (not just 'post') gets a realistic share: a random reporter who
    // is never the target's own owner, a random reason and status, timestamped as a reaction to
    // the target the same way EngagementSeedWriter times a like. Every draw is checked against
    // usedReportKeys before insert, so a background row can never collide with the unique index
    // uq_reports_reporter_type_entity (V30) a narrative or supplementary report already claimed.
    private List<UUID> writeBackgroundReports(
            SeedContent content,
            Map<String, UUID> postIdBySeedId,
            Map<String, UUID> usersByUsername,
            SeedTimeline timeline) {
        int remaining = TARGET_REPORT_COUNT - usedReportKeys.size();
        if (remaining <= 0) {
            return List.of();
        }
        Random random = new Random(BACKGROUND_REPORT_RANDOM_SEED);
        List<PostSeed> publishedPosts =
                content.posts().stream().filter(p -> "published".equals(p.status())).toList();
        List<UserSeed> users = content.users();
        if (publishedPosts.isEmpty() || users.size() < 2) {
            return List.of();
        }
        List<BackgroundTarget> comments = loadBackgroundComments();
        List<BackgroundTarget> messages = loadBackgroundMessages();
        List<BackgroundTarget> stories = loadBackgroundStories();
        List<BackgroundTarget> targetUsers = loadBackgroundUsers();

        List<UUID> reportIds = new ArrayList<>();
        for (int i = 0; i < remaining; i++) {
            UUID reportId = null;
            for (int attempt = 0;
                    attempt < MAX_BACKGROUND_ATTEMPTS_PER_REPORT && reportId == null;
                    attempt++) {
                String reportType =
                        BACKGROUND_REPORT_TYPES.get(random.nextInt(BACKGROUND_REPORT_TYPES.size()));
                UserSeed reporterSeed = users.get(random.nextInt(users.size()));
                UUID reporterId = usersByUsername.get(reporterSeed.username());
                if (reporterId == null) {
                    continue;
                }
                BackgroundTarget target =
                        switch (reportType) {
                            case "post" ->
                                    drawBackgroundPost(
                                            publishedPosts, postIdBySeedId, reporterSeed, random);
                            case "comment" -> drawBackgroundTarget(comments, reporterId, random);
                            case "story" -> drawBackgroundTarget(stories, reporterId, random);
                            case "message" -> drawBackgroundTarget(messages, reporterId, random);
                            case "user" -> drawBackgroundTarget(targetUsers, reporterId, random);
                            default -> null;
                        };
                if (target == null
                        || !markReportKeyUsed(reporterId, reportType, target.entityId())) {
                    continue;
                }
                Instant createdAt = timeline.likeOrSaveCreatedAt(target.createdAt());
                String reason = REPORT_REASONS.get(random.nextInt(REPORT_REASONS.size()));
                String status = REPORT_STATUSES.get(random.nextInt(REPORT_STATUSES.size()));
                reportId = UUID.randomUUID();
                jdbc.update(
                        INSERT_REPORT_SQL,
                        reportId,
                        reporterId,
                        reportType,
                        reason,
                        target.entityId(),
                        status,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        java.sql.Timestamp.from(createdAt));
            }
            if (reportId != null) {
                reportIds.add(reportId);
            }
        }
        return reportIds;
    }

    // A random published post never reported by its own author - the one background type whose
    // pool comes from SeedContent rather than a fresh DB read, kept exactly as it always was.
    private BackgroundTarget drawBackgroundPost(
            List<PostSeed> publishedPosts,
            Map<String, UUID> postIdBySeedId,
            UserSeed reporter,
            Random random) {
        PostSeed post = publishedPosts.get(random.nextInt(publishedPosts.size()));
        UUID postId = postIdBySeedId.get(post.id());
        if (postId == null || reporter.username().equals(post.authorUsername())) {
            return null;
        }
        return new BackgroundTarget(postId, postCreatedAtById.get(postId));
    }

    // Shared draw for the four DB-backed background pools (comment/story/message/user): a
    // uniform random pick, rejected only when its owner is the drawing reporter. For the 'user'
    // pool, entityId and ownerId are the same account (report/DATA_RULES.md Section 4: a 'user'
    // report's entity_id is the reported account's own id, not a piece of content it authored),
    // so the same self-report guard applies without a bespoke branch.
    private BackgroundTarget drawBackgroundTarget(
            List<BackgroundTarget> pool, UUID reporterId, Random random) {
        if (pool.isEmpty()) {
            return null;
        }
        BackgroundTarget candidate = pool.get(random.nextInt(pool.size()));
        return reporterId.equals(candidate.ownerId()) ? null : candidate;
    }

    private List<BackgroundTarget> loadBackgroundComments() {
        List<BackgroundTarget> pool = new ArrayList<>();
        jdbc.query(
                "SELECT id, user_id, created_at FROM comments WHERE deleted_at IS NULL"
                        + " AND admin_removed_at IS NULL AND"
                        + " moderation_status = 'approved'",
                rs -> {
                    pool.add(
                            new BackgroundTarget(
                                    (UUID) rs.getObject("id"),
                                    (UUID) rs.getObject("user_id"),
                                    rs.getTimestamp("created_at").toInstant()));
                });
        return pool;
    }

    private List<BackgroundTarget> loadBackgroundMessages() {
        List<BackgroundTarget> pool = new ArrayList<>();
        jdbc.query(
                "SELECT id, sender_id, created_at FROM messages WHERE is_deleted = false AND"
                        + " admin_removed_at IS NULL AND sender_id IS NOT NULL",
                rs -> {
                    pool.add(
                            new BackgroundTarget(
                                    (UUID) rs.getObject("id"),
                                    (UUID) rs.getObject("sender_id"),
                                    rs.getTimestamp("created_at").toInstant()));
                });
        return pool;
    }

    private List<BackgroundTarget> loadBackgroundStories() {
        List<BackgroundTarget> pool = new ArrayList<>();
        jdbc.query(
                "SELECT id, user_id, created_at FROM stories",
                rs -> {
                    pool.add(
                            new BackgroundTarget(
                                    (UUID) rs.getObject("id"),
                                    (UUID) rs.getObject("user_id"),
                                    rs.getTimestamp("created_at").toInstant()));
                });
        return pool;
    }

    private List<BackgroundTarget> loadBackgroundUsers() {
        List<BackgroundTarget> pool = new ArrayList<>();
        jdbc.query(
                "SELECT id, created_at FROM users",
                rs -> {
                    UUID id = (UUID) rs.getObject("id");
                    pool.add(
                            new BackgroundTarget(
                                    id, id, rs.getTimestamp("created_at").toInstant()));
                });
        return pool;
    }

    // entityId doubles as ownerId only for the 'user' report type, where the reported entity and
    // its owner are the same account; every other background pool passes the content's own author
    // as ownerId so drawBackgroundTarget can reject a self-report.
    private record BackgroundTarget(UUID entityId, UUID ownerId, Instant createdAt) {
        BackgroundTarget(UUID entityId, Instant createdAt) {
            this(entityId, null, createdAt);
        }
    }

    // Every report row this writer inserts - narrative, supplementary or background - funnels
    // through this method exactly once, so usedReportKeys always reflects the true set of
    // (reporter, type, entity) triples already claimed against uq_reports_reporter_type_entity.
    private boolean markReportKeyUsed(UUID reporterId, String reportType, UUID entityId) {
        return usedReportKeys.add(reporterId + ":" + reportType + ":" + entityId);
    }

    private record TargetEntity(String entityType, UUID entityId) {}

    private TargetEntity resolveActionTarget(
            String actionType,
            Map<String, Object> event,
            UUID targetUserId,
            Map<String, UUID> postIdBySeedId,
            SeedContent content,
            SeedTimeline timeline) {
        return switch (actionType) {
            case REMOVE_POST_ACTION, RESTORE_POST_ACTION -> {
                String postSeedId = (String) event.get("target_post");
                UUID postId = postSeedId == null ? null : postIdBySeedId.get(postSeedId);
                yield postId == null ? null : new TargetEntity("post", postId);
            }
            case REMOVE_COMMENT_ACTION, RESTORE_COMMENT_ACTION -> {
                String ref = (String) event.get("target_comment_ref");
                UUID commentId =
                        ref == null
                                ? null
                                : resolveOrCreateComment(
                                        ref, null, postIdBySeedId, content, timeline);
                yield commentId == null ? null : new TargetEntity("comment", commentId);
            }
            case REMOVE_STORY_ACTION, RESTORE_STORY_ACTION -> {
                String targetUser = (String) event.get("target_user");
                UUID storyId = targetUser == null ? null : storyIdByOwnerUsername.get(targetUser);
                yield storyId == null ? null : new TargetEntity("story", storyId);
            }
            case REMOVE_MESSAGE_ACTION, RESTORE_MESSAGE_ACTION -> {
                String conversationSeedId = (String) event.get("target_conversation_id");
                Object indexRaw = event.get("target_message_index");
                UUID messageId =
                        conversationSeedId == null || indexRaw == null
                                ? null
                                : resolveMessage(
                                        conversationSeedId,
                                        ((Number) indexRaw).intValue(),
                                        content);
                yield messageId == null ? null : new TargetEntity("message", messageId);
            }
            case CREATE_HASHTAG_ACTION,
                    BAN_HASHTAG_ACTION,
                    UNBAN_HASHTAG_ACTION,
                    DELETE_HASHTAG_ACTION,
                    PIN_HASHTAG_ACTION,
                    UNPIN_HASHTAG_ACTION,
                    "edit_hashtag" -> {
                String hashtagName = (String) event.get("hashtag_name");
                UUID hashtagId =
                        hashtagName == null
                                ? null
                                : resolveOrCreateHashtag(actionType, hashtagName, timeline);
                yield hashtagId == null ? null : new TargetEntity("hashtag", hashtagId);
            }
            default -> null;
        };
    }

    private void applySideEffect(
            String actionType,
            Map<String, Object> event,
            Instant translatedAt,
            UUID targetUserId,
            TargetEntity target,
            UUID adminActionId,
            Map<String, UUID> usersByUsername) {
        java.sql.Timestamp at = java.sql.Timestamp.from(translatedAt);
        switch (actionType) {
            case WARN_USER_ACTION -> {
                if (targetUserId == null) {
                    return;
                }
                String reasonKey =
                        firstNonNull((String) event.get("reason_key"), DEFAULT_REASON_KEY);
                String note = (String) event.get("note");
                jdbc.update(
                        INSERT_WARNING_SQL,
                        UUID.randomUUID(),
                        targetUserId,
                        null,
                        reasonKey,
                        note == null || note.isBlank()
                                ? "Warned per moderation case fixture"
                                : note,
                        adminActionId,
                        at);
            }
            case REVOKE_WARNING_ACTION -> {
                if (targetUserId == null) {
                    return;
                }
                jdbc.update(REVOKE_WARNING_SQL, at, null, targetUserId);
            }
            case ISSUE_STRIKE_ACTION -> {
                if (targetUserId == null) {
                    return;
                }
                int strikeNumber = strikeNumberByUser.merge(targetUserId, 1, Integer::sum);
                jdbc.update(
                        INSERT_STRIKE_SQL,
                        UUID.randomUUID(),
                        targetUserId,
                        strikeNumber,
                        adminActionId,
                        at);
            }
            case REVOKE_STRIKE_ACTION -> {
                if (targetUserId == null) {
                    return;
                }
                jdbc.update(REVOKE_STRIKE_SQL, at, null, targetUserId);
            }
            case SUSPEND_USER_ACTION -> {
                if (targetUserId == null) {
                    return;
                }
                int durationDays = intField(event, "duration_days", 7);
                jdbc.update(
                        UPDATE_SUSPENDED_UNTIL_SQL,
                        java.sql.Timestamp.from(translatedAt.plus(Duration.ofDays(durationDays))),
                        targetUserId);
            }
            case UNSUSPEND_USER_ACTION -> {
                if (targetUserId != null) {
                    jdbc.update(UPDATE_SUSPENDED_UNTIL_SQL, (Object) null, targetUserId);
                }
            }
            case CHANGE_USER_ROLE_ACTION -> {
                String toRole = (String) event.get("to_role");
                if (targetUserId != null && toRole != null) {
                    jdbc.update(UPDATE_USER_ROLE_SQL, toRole, targetUserId);
                }
            }
            case REMOVE_POST_ACTION -> {
                if (target != null) {
                    jdbc.update(UPDATE_POST_REMOVE_SQL, at, target.entityId());
                }
            }
            case RESTORE_POST_ACTION -> {
                if (target != null) {
                    jdbc.update(UPDATE_POST_RESTORE_SQL, target.entityId());
                }
            }
            case REMOVE_COMMENT_ACTION -> {
                if (target != null) {
                    jdbc.update(UPDATE_COMMENT_ADMIN_REMOVED_AT_SQL, at, target.entityId());
                }
            }
            case RESTORE_COMMENT_ACTION -> {
                if (target != null) {
                    jdbc.update(
                            UPDATE_COMMENT_ADMIN_REMOVED_AT_SQL, (Object) null, target.entityId());
                }
            }
            case REMOVE_STORY_ACTION -> {
                if (target != null) {
                    jdbc.update(UPDATE_STORY_ADMIN_REMOVED_AT_SQL, at, target.entityId());
                }
            }
            case RESTORE_STORY_ACTION -> {
                if (target != null) {
                    jdbc.update(
                            UPDATE_STORY_ADMIN_REMOVED_AT_SQL, (Object) null, target.entityId());
                }
            }
            case REMOVE_MESSAGE_ACTION -> {
                if (target != null) {
                    jdbc.update(UPDATE_MESSAGE_ADMIN_REMOVED_AT_SQL, at, target.entityId());
                }
            }
            case RESTORE_MESSAGE_ACTION -> {
                if (target != null) {
                    jdbc.update(
                            UPDATE_MESSAGE_ADMIN_REMOVED_AT_SQL, (Object) null, target.entityId());
                }
            }
            case BAN_HASHTAG_ACTION -> updateHashtagStatus(target, "banned");
            case UNBAN_HASHTAG_ACTION -> updateHashtagStatus(target, "active");
            case DELETE_HASHTAG_ACTION -> updateHashtagStatus(target, "deleted");
            case PIN_HASHTAG_ACTION -> pinHashtag(target, at, resolveActor(event, usersByUsername));
            case UNPIN_HASHTAG_ACTION -> unpinHashtag(target);
            default -> {
                // resolve_report / dismiss_report / escalate_report / force_logout /
                // create_hashtag / edit_hashtag: the admin_actions audit row is the whole
                // effect this seed writer models; no further table mutation is needed.
            }
        }
    }

    private void updateHashtagStatus(TargetEntity target, String status) {
        if (target != null && target.entityId() != null) {
            jdbc.update(UPDATE_HASHTAG_STATUS_SQL, status, target.entityId());
        }
    }

    private void pinHashtag(TargetEntity target, java.sql.Timestamp at, UUID actorId) {
        if (target != null && target.entityId() != null) {
            jdbc.update(PIN_HASHTAG_SQL, at, actorId, target.entityId());
        }
    }

    private void unpinHashtag(TargetEntity target) {
        if (target != null && target.entityId() != null) {
            jdbc.update(UNPIN_HASHTAG_SQL, target.entityId());
        }
    }

    private UUID resolveActor(Map<String, Object> event, Map<String, UUID> usersByUsername) {
        String actorUsername = (String) event.get("actor");
        return actorUsername == null ? null : usersByUsername.get(actorUsername);
    }

    private UUID resolveEntity(
            String reportType,
            Map<String, Object> reportedContentEntry,
            Map<String, UUID> postIdBySeedId,
            SeedContent content,
            SeedTimeline timeline) {
        return switch (reportType) {
            case "post" -> postIdBySeedId.get((String) reportedContentEntry.get("post_id"));
            case "comment" ->
                    resolveOrCreateComment(
                            (String) reportedContentEntry.get("comment_ref"),
                            reportedContentEntry,
                            postIdBySeedId,
                            content,
                            timeline);
            case "story" -> storyIdByOwnerUsername.get((String) reportedContentEntry.get("owner"));
            // A 'user' report's entity_id is the reported account's own id, not a piece of
            // content it authored (report/DATA_RULES.md Section 4).
            case "user" -> lookupUserByUsername((String) reportedContentEntry.get("owner"));
            case "message" ->
                    resolveMessage(
                            (String) reportedContentEntry.get("conversation_id"),
                            intField(reportedContentEntry, "message_index", 0),
                            content);
            default ->
                    throw new IllegalStateException(
                            "ModerationSeedWriter: unrecognized reported_content type '"
                                    + reportType
                                    + "'");
        };
    }

    // Creates a real comment the first time any event references a given ref (postSeedId#label),
    // then memoizes it - a synthetic narrative id per StorySeedWriter's own precedent for
    // target_story_ref, not a literal comment already present in comment_pools.json.
    private UUID resolveOrCreateComment(
            String ref,
            Map<String, Object> reportedContentEntryOrNull,
            Map<String, UUID> postIdBySeedId,
            SeedContent content,
            SeedTimeline timeline) {
        if (ref == null) {
            return null;
        }
        UUID cached = commentIdByRef.get(ref);
        if (cached != null) {
            return cached;
        }
        int separator = ref.indexOf('#');
        String postSeedId = separator < 0 ? ref : ref.substring(0, separator);
        UUID postId = postIdBySeedId.get(postSeedId);
        if (postId == null) {
            log.warn(
                    "[seed] moderation: comment ref '{}' resolves to unknown post '{}', skipped",
                    ref,
                    postSeedId);
            return null;
        }
        Instant postCreatedAt = postCreatedAtById.get(postId);

        String ownerUsername =
                reportedContentEntryOrNull == null
                        ? null
                        : (String) reportedContentEntryOrNull.get("owner");
        UUID authorId = null;
        if (ownerUsername != null) {
            authorId = lookupUserByUsername(ownerUsername);
        }
        if (authorId == null) {
            authorId = resolvePostAuthorId(postSeedId, content);
        }
        String text =
                reportedContentEntryOrNull != null && reportedContentEntryOrNull.get("text") != null
                        ? (String) reportedContentEntryOrNull.get("text")
                        : PLACEHOLDER_COMMENT_TEXT;

        UUID commentId = UUID.randomUUID();
        Instant createdAt = timeline.commentCreatedAt(postCreatedAt);
        jdbc.update(
                INSERT_COMMENT_SQL,
                commentId,
                postId,
                authorId,
                commentId,
                text,
                java.sql.Timestamp.from(createdAt));
        commentIdByRef.put(ref, commentId);
        return commentId;
    }

    private UUID resolvePostAuthorId(String postSeedId, SeedContent content) {
        return content.posts().stream()
                .filter(p -> p.id().equals(postSeedId))
                .findFirst()
                .map(p -> lookupUserByUsername(p.authorUsername()))
                .orElse(null);
    }

    private UUID lookupUserByUsername(String username) {
        List<UUID> rows =
                jdbc.query(
                        "SELECT id FROM users WHERE username = ?",
                        (rs, rowNum) -> (UUID) rs.getObject("id"),
                        username);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private UUID resolveMessage(String conversationSeedId, int messageIndex, SeedContent content) {
        UUID conversationId = resolveConversationId(conversationSeedId, content);
        if (conversationId == null) {
            return null;
        }
        List<UUID> messageIds =
                messageIdsByConversationSeedId.computeIfAbsent(
                        conversationSeedId,
                        key ->
                                jdbc.query(
                                        "SELECT id FROM messages WHERE conversation_id = ? ORDER"
                                                + " BY created_at ASC",
                                        (rs, rowNum) -> (UUID) rs.getObject("id"),
                                        conversationId));
        if (messageIndex < 0 || messageIndex >= messageIds.size()) {
            log.warn(
                    "[seed] moderation: message_index {} out of range for conversation '{}'"
                            + " ({} messages), skipped",
                    messageIndex,
                    conversationSeedId,
                    messageIds.size());
            return null;
        }
        return messageIds.get(messageIndex);
    }

    private UUID resolveConversationId(String conversationSeedId, SeedContent content) {
        UUID cached = conversationIdBySeedId.get(conversationSeedId);
        if (cached != null) {
            return cached;
        }
        ConversationSeed seed =
                content.conversations().stream()
                        .filter(c -> c.id().equals(conversationSeedId))
                        .findFirst()
                        .orElse(null);
        if (seed == null || seed.participants().size() != 2) {
            return null;
        }
        UUID userIdA = lookupUserByUsername(seed.participants().get(0));
        UUID userIdB = lookupUserByUsername(seed.participants().get(1));
        if (userIdA == null || userIdB == null) {
            return null;
        }
        String directPairKey = MessageSeedWriter.directPairKey(userIdA, userIdB);
        List<UUID> rows =
                jdbc.query(
                        "SELECT id FROM conversations WHERE direct_pair_key = ?",
                        (rs, rowNum) -> (UUID) rs.getObject("id"),
                        directPairKey);
        UUID conversationId = rows.isEmpty() ? null : rows.get(0);
        if (conversationId != null) {
            conversationIdBySeedId.put(conversationSeedId, conversationId);
        }
        return conversationId;
    }

    private UUID resolveAnyPostByAuthor(
            String username, Map<String, UUID> postIdBySeedId, SeedContent content) {
        return content.posts().stream()
                .filter(p -> p.authorUsername().equals(username))
                .findFirst()
                .map(p -> postIdBySeedId.get(p.id()))
                .orElse(null);
    }

    // create_hashtag: hashtags.json's registry does not carry every hashtag_name a supplementary
    // action names (e.g. "reactjs19"), so a first reference inserts a new active row; every other
    // action here targets a hashtag PostSeedWriter's own catalog already inserted, resolved by
    // name and memoized. A handful of "illustrative_*" names in the fixture's own notes are
    // explicitly documented as not present in the shipped hashtag catalog - resolving to null and
    // skipping the table mutation for those is the documented behavior, not a bug.
    private UUID resolveOrCreateHashtag(
            String actionType, String hashtagName, SeedTimeline timeline) {
        UUID cached = hashtagIdByName.get(hashtagName);
        if (cached != null) {
            return cached;
        }
        List<UUID> rows =
                jdbc.query(
                        "SELECT id FROM hashtags WHERE name = ?",
                        (rs, rowNum) -> (UUID) rs.getObject("id"),
                        hashtagName);
        if (!rows.isEmpty()) {
            hashtagIdByName.put(hashtagName, rows.get(0));
            return rows.get(0);
        }
        if (!CREATE_HASHTAG_ACTION.equals(actionType)) {
            return null;
        }
        UUID hashtagId = UUID.randomUUID();
        jdbc.update(
                INSERT_HASHTAG_SQL,
                hashtagId,
                hashtagName,
                java.sql.Timestamp.from(timeline.referenceNow()));
        hashtagIdByName.put(hashtagName, hashtagId);
        return hashtagId;
    }

    private void loadPostCreatedAt() {
        jdbc.query(
                "SELECT id, created_at FROM posts",
                rs -> {
                    UUID id = (UUID) rs.getObject("id");
                    Instant createdAt = rs.getTimestamp("created_at").toInstant();
                    postCreatedAtById.put(id, createdAt);
                });
    }

    // One story per guaranteed owner is all ModerationSeedWriter ever needs - StorySeedWriter's
    // GUARANTEED_STORY_OWNERS list is exactly the set of usernames moderation_cases.json resolves
    // target_story_ref against.
    private void loadStoryOwners(SeedContent content) {
        jdbc.query(
                "SELECT DISTINCT ON (u.username) u.username, s.id FROM stories s JOIN users u ON"
                        + " u.id = s.user_id ORDER BY u.username, s.created_at ASC",
                rs -> {
                    String username = rs.getString("username");
                    UUID storyId = (UUID) rs.getObject("id");
                    storyIdByOwnerUsername.put(username, storyId);
                });
    }

    private UUID requireUser(Map<String, UUID> usersByUsername, String username, String context) {
        UUID userId = usersByUsername.get(username);
        if (userId == null) {
            throw new IllegalStateException(
                    "ModerationSeedWriter: "
                            + context
                            + " references username '"
                            + username
                            + "' which does not exist in users.json");
        }
        return userId;
    }

    private Instant parseAt(Map<String, Object> event) {
        return Instant.parse((String) event.get("at"));
    }

    private int intField(Map<String, Object> event, String key, int defaultValue) {
        Object value = event.get(key);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private record CaseTally(List<UUID> reportIds, int adminActions, int warnings, int strikes) {}

    private record SupplementaryEvent(
            Instant originalAt,
            ModerationSupplementaryAction action,
            ModerationSupplementaryReport report) {}
}
