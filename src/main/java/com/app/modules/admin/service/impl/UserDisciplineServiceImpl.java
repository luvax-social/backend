package com.app.modules.admin.service.impl;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.common.pagination.Cursor;
import com.app.common.pagination.CursorCodec;
import com.app.common.pagination.CursorScope;
import com.app.common.pagination.TimeCursors;
import com.app.common.response.CursorPageResponse;
import com.app.modules.admin.dto.request.AdminActionRequest;
import com.app.modules.admin.dto.request.AdminWarnUserRequest;
import com.app.modules.admin.dto.response.AdminActionResponse;
import com.app.modules.admin.dto.response.AdminViolationResponse;
import com.app.modules.admin.dto.response.AdminWarnUserResponse;
import com.app.modules.admin.dto.response.UserWarningResponse;
import com.app.modules.admin.entity.UserStrike;
import com.app.modules.admin.entity.UserWarning;
import com.app.modules.admin.enums.AdminActionType;
import com.app.modules.admin.mapper.UserDisciplineMapper;
import com.app.modules.admin.messaging.AdminEventTypes;
import com.app.modules.admin.repository.AdminUserRepository;
import com.app.modules.admin.repository.ReportReasonConfigReader;
import com.app.modules.admin.repository.UserStrikeRepository;
import com.app.modules.admin.repository.UserWarningRepository;
import com.app.modules.admin.service.AdminActionRecorder;
import com.app.modules.admin.service.AdminAuthorizationService;
import com.app.modules.admin.service.UserDisciplineService;
import com.app.modules.support.service.VerificationService;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.repository.UserRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class UserDisciplineServiceImpl implements UserDisciplineService {

    /**
     * How long a warning counts toward the next strike.
     *
     * <p>Composes with the post-strike reset rather than replacing it: a warning counts only if it
     * is both inside this window and newer than the account's most recent unrevoked strike.
     */
    private static final int WARNING_RETENTION_DAYS = 90;

    /** Active warnings that produce a strike. */
    private static final int WARNINGS_PER_STRIKE = 3;

    private static final int STRIKE_ONE_SUSPENSION_DAYS = 7;
    private static final int STRIKE_TWO_SUSPENSION_DAYS = 30;

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String TARGET_ENTITY_TYPE = "user";

    /**
     * Stands in for "this account has never been struck" in the active-warning predicate.
     *
     * <p>Bound as a parameter rather than written as {@code '-infinity'} so the same statement is
     * readable by the JPA query parser and by anyone reading it.
     */
    private static final OffsetDateTime NO_STRIKE_YET =
            OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private final UserWarningRepository userWarningRepository;
    private final UserStrikeRepository userStrikeRepository;
    private final AdminUserRepository adminUserRepository;
    private final UserRepository userRepository;
    private final ReportReasonConfigReader reportReasonConfigReader;
    private final AdminActionRecorder adminActionRecorder;
    private final UserDisciplineMapper userDisciplineMapper;
    private final OutboxService outboxService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final VerificationService verificationService;

    public UserDisciplineServiceImpl(
            UserWarningRepository userWarningRepository,
            UserStrikeRepository userStrikeRepository,
            AdminUserRepository adminUserRepository,
            UserRepository userRepository,
            ReportReasonConfigReader reportReasonConfigReader,
            AdminActionRecorder adminActionRecorder,
            UserDisciplineMapper userDisciplineMapper,
            OutboxService outboxService,
            AdminAuthorizationService adminAuthorizationService,
            VerificationService verificationService) {
        this.userWarningRepository = userWarningRepository;
        this.userStrikeRepository = userStrikeRepository;
        this.adminUserRepository = adminUserRepository;
        this.userRepository = userRepository;
        this.reportReasonConfigReader = reportReasonConfigReader;
        this.adminActionRecorder = adminActionRecorder;
        this.userDisciplineMapper = userDisciplineMapper;
        this.outboxService = outboxService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.verificationService = verificationService;
    }

    @Override
    @Transactional
    public AdminWarnUserResponse issueWarning(
            UUID actorId, UUID userId, AdminWarnUserRequest request) {
        if (actorId.equals(userId)) {
            throw new AppException(ApiErrorCode.ADMIN_SELF_ACTION_NOT_ALLOWED);
        }
        validateReasonEnabled(request.reasonKey());
        // Row lock on the target, taken before anything is read or written. Two moderators warning
        // the same account at the same moment would otherwise both read a count of two and both
        // try to issue strike one; uq_user_strikes_active_number would reject the second, but only
        // by failing its whole transaction and losing a legitimate warning with it. Serializing
        // here makes the second warning see the first one's strike and correctly issue none.
        // The unique index stays as the invariant of last resort, not as the mechanism.
        User target =
                adminUserRepository
                        .lockForDiscipline(userId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.USER_NOT_FOUND));
        if (target.getRole() != UserRole.USER) {
            throw new AppException(ApiErrorCode.ADMIN_TARGET_NOT_WARNABLE);
        }

        AdminActionResponse warnAudit =
                adminActionRecorder.record(
                        actorId,
                        AdminActionType.WARN_USER,
                        userId,
                        TARGET_ENTITY_TYPE,
                        userId,
                        null,
                        request.note(),
                        Map.of("reasonKey", request.reasonKey()));
        UserWarning warning =
                userWarningRepository.saveAndFlush(
                        UserWarning.builder()
                                .userId(userId)
                                .issuedBy(actorId)
                                .reasonKey(request.reasonKey())
                                .note(request.note().trim())
                                .adminActionId(warnAudit.id())
                                .build());

        OffsetDateTime windowStart = warningWindowStart();
        long activeWarnings =
                userWarningRepository.countActiveWarnings(userId, windowStart, NO_STRIKE_YET);

        UserStrike strike = null;
        if (activeWarnings >= WARNINGS_PER_STRIKE) {
            strike =
                    issueStrike(
                            actorId,
                            target,
                            userWarningRepository.findActiveWarningIds(
                                    userId, windowStart, NO_STRIKE_YET));
            // A strike consumes the warnings that produced it, so the count restarts from here.
            activeWarnings = 0;
        }

        enqueueWarningNotification(userId, warning.getId(), warnAudit.id());
        log.info(
                "Warning issued: actorId={}, targetId={}, reasonKey={}, activeWarnings={},"
                        + " strikeIssued={}",
                actorId,
                userId,
                request.reasonKey(),
                activeWarnings,
                strike != null);
        return new AdminWarnUserResponse(
                userDisciplineMapper.toWarningResponse(warning),
                activeWarnings,
                strike != null,
                strike == null ? null : userDisciplineMapper.toStrikeResponse(strike),
                target.getStatus().toJson());
    }

    // The one place the retention window is computed. issueWarning and countActiveWarnings
    // both read it, so the rule cannot drift between the count a reviewer is shown and the count
    // the strike decision acts on.
    private static OffsetDateTime warningWindowStart() {
        return OffsetDateTime.now(ZoneOffset.UTC).minusDays(WARNING_RETENTION_DAYS);
    }

    @Override
    @Transactional(readOnly = true)
    public long countActiveWarnings(UUID userId) {
        return userWarningRepository.countActiveWarnings(
                userId, warningWindowStart(), NO_STRIKE_YET);
    }

    private static String cursorScope(boolean includeStrikes, boolean includeRevoked) {
        if (includeStrikes) {
            return includeRevoked
                    ? CursorScope.ADMIN_VIOLATIONS_FULL_ALL
                    : CursorScope.ADMIN_VIOLATIONS_FULL;
        }
        return includeRevoked
                ? CursorScope.ADMIN_VIOLATIONS_WARNINGS_ALL
                : CursorScope.ADMIN_VIOLATIONS_WARNINGS;
    }

    private List<UserWarning> readWarnings(
            UUID userId,
            OffsetDateTime cursorCreatedAt,
            UUID cursorId,
            int queryLimit,
            boolean includeRevoked) {
        if (cursorCreatedAt == null) {
            return includeRevoked
                    ? userWarningRepository.findFirstPageIncludingRevoked(userId, queryLimit)
                    : userWarningRepository.findFirstActivePage(userId, queryLimit);
        }
        return includeRevoked
                ? userWarningRepository.findPageAfterCursorIncludingRevoked(
                        userId, cursorCreatedAt, cursorId, queryLimit)
                : userWarningRepository.findActivePageAfterCursor(
                        userId, cursorCreatedAt, cursorId, queryLimit);
    }

    private List<UserStrike> readStrikes(
            UUID userId,
            OffsetDateTime cursorCreatedAt,
            UUID cursorId,
            int queryLimit,
            boolean includeRevoked) {
        if (cursorCreatedAt == null) {
            return includeRevoked
                    ? userStrikeRepository.findFirstPageIncludingRevoked(userId, queryLimit)
                    : userStrikeRepository.findFirstActivePage(userId, queryLimit);
        }
        return includeRevoked
                ? userStrikeRepository.findPageAfterCursorIncludingRevoked(
                        userId, cursorCreatedAt, cursorId, queryLimit)
                : userStrikeRepository.findActivePageAfterCursor(
                        userId, cursorCreatedAt, cursorId, queryLimit);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<AdminViolationResponse> listViolations(
            UUID actorId, UUID userId, String cursor, int limit, boolean includeRevoked) {
        // Read from the row rather than from the security context, for the same reason every other
        // authorization decision in this module does: the caller must not be able to choose what
        // the listing contains, and a future non-controller caller must get the same answer.
        UserRole actorRole =
                userRepository
                        .findByIdAndDeletedAtIsNull(actorId)
                        .map(User::getRole)
                        .orElseThrow(() -> new AppException(ApiErrorCode.FORBIDDEN));
        boolean includeStrikes = actorRole == UserRole.ADMIN;
        // Four scopes rather than two. Including revoked rows changes which rows a cursor points
        // past, so replaying one listing's cursor on the other would skip or repeat entries.
        String scope = cursorScope(includeStrikes, includeRevoked);
        int pageSize = normalizeLimit(limit);
        Cursor decoded = CursorCodec.decode(cursor, scope);
        OffsetDateTime cursorCreatedAt =
                decoded == null ? null : TimeCursors.fromMicros(decoded.sortValueMicros());
        UUID cursorId = decoded == null ? null : decoded.id();
        int queryLimit = pageSize + 1;

        // Warnings and strikes are two tables read separately and merged here rather than in one
        // UNION. Each side is fetched one page deep, so the merge can never miss a row that belongs
        // on this page: any row it drops is older than every row it keeps.
        List<AdminViolationResponse> merged = new ArrayList<>();
        List<UserWarning> warnings =
                readWarnings(userId, cursorCreatedAt, cursorId, queryLimit, includeRevoked);
        warnings.stream().map(userDisciplineMapper::toViolation).forEach(merged::add);
        if (includeStrikes) {
            List<UserStrike> strikes =
                    readStrikes(userId, cursorCreatedAt, cursorId, queryLimit, includeRevoked);
            strikes.stream().map(userDisciplineMapper::toViolation).forEach(merged::add);
        }
        merged.sort(VIOLATION_ORDER);

        boolean hasNextPage = merged.size() > pageSize;
        List<AdminViolationResponse> content = hasNextPage ? merged.subList(0, pageSize) : merged;
        if (content.isEmpty()) {
            return CursorPageResponse.of(List.of(), false, null, null, cursor != null);
        }
        return CursorPageResponse.of(
                content,
                hasNextPage,
                encode(content.get(0).createdAt(), content.get(0).id(), scope),
                encode(
                        content.get(content.size() - 1).createdAt(),
                        content.get(content.size() - 1).id(),
                        scope),
                cursor != null);
    }

    @Override
    @Transactional
    public AdminActionResponse revokeWarning(
            UUID actorId, UUID warningId, AdminActionRequest request) {
        // Revoking a warning erases a rung of the discipline ladder, and a strike is the record
        // behind a ban, so both revocations are administrator-only. Until now the sole gate was the
        // per-method annotation narrowing the controller's wider moderator-and-administrator class
        // annotation, and neither revocation read the actor's role at all.
        adminAuthorizationService.assertActorIsAdministrator(actorId);
        UserWarning warning =
                userWarningRepository
                        .findById(warningId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.WARNING_NOT_FOUND));
        if (warning.getRevokedAt() != null) {
            throw new AppException(ApiErrorCode.ADMIN_INVALID_TRANSITION);
        }
        warning.setRevokedAt(OffsetDateTime.now(ZoneOffset.UTC));
        warning.setRevokedBy(actorId);
        userWarningRepository.save(warning);
        // Deliberately nothing else. Revoking a warning does not revoke a strike it contributed to
        // and does not touch users.status: one click on a warning must never silently lift a ban.
        log.info("Warning revoked: actorId={}, warningId={}", actorId, warningId);
        return adminActionRecorder.record(
                actorId,
                AdminActionType.REVOKE_WARNING,
                warning.getUserId(),
                "warning",
                warningId,
                request.reportId(),
                request.reason(),
                Map.of("reasonKey", warning.getReasonKey()));
    }

    @Override
    @Transactional
    public AdminActionResponse revokeStrike(
            UUID actorId, UUID strikeId, AdminActionRequest request) {
        adminAuthorizationService.assertActorIsAdministrator(actorId);
        UserStrike strike =
                userStrikeRepository
                        .findById(strikeId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.STRIKE_NOT_FOUND));
        if (strike.getRevokedAt() != null) {
            throw new AppException(ApiErrorCode.ADMIN_INVALID_TRANSITION);
        }
        strike.setRevokedAt(OffsetDateTime.now(ZoneOffset.UTC));
        strike.setRevokedBy(actorId);
        userStrikeRepository.save(strike);
        // The account's status is left exactly as it is. Lifting the suspension or ban this strike
        // caused is a separate decision, taken through the account-status endpoints, where it is
        // audited as what it is.
        log.info("Strike revoked: actorId={}, strikeId={}", actorId, strikeId);
        return adminActionRecorder.record(
                actorId,
                AdminActionType.REVOKE_STRIKE,
                strike.getUserId(),
                "strike",
                strikeId,
                request.reportId(),
                request.reason(),
                Map.of("strikeNumber", strike.getStrikeNumber()));
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<UserWarningResponse> listOwnWarnings(
            UUID userId, String cursor, int limit) {
        int pageSize = normalizeLimit(limit);
        Cursor decoded = CursorCodec.decode(cursor, CursorScope.OWN_WARNINGS);
        List<UserWarning> rows =
                decoded == null
                        ? userWarningRepository.findFirstActivePage(userId, pageSize + 1)
                        : userWarningRepository.findActivePageAfterCursor(
                                userId,
                                TimeCursors.fromMicros(decoded.sortValueMicros()),
                                decoded.id(),
                                pageSize + 1);
        boolean hasNextPage = rows.size() > pageSize;
        List<UserWarning> page = hasNextPage ? rows.subList(0, pageSize) : rows;
        if (page.isEmpty()) {
            return CursorPageResponse.of(List.of(), false, null, null, cursor != null);
        }
        return CursorPageResponse.of(
                page.stream().map(userDisciplineMapper::toOwnWarningResponse).toList(),
                hasNextPage,
                encode(page.get(0).getCreatedAt(), page.get(0).getId(), CursorScope.OWN_WARNINGS),
                encode(
                        page.get(page.size() - 1).getCreatedAt(),
                        page.get(page.size() - 1).getId(),
                        CursorScope.OWN_WARNINGS),
                cursor != null);
    }

    /**
     * The merge order, matching the ORDER BY both queries use, byte for byte.
     *
     * <p>The tie-break is not theoretical here. A strike and the warning that produced it are
     * written in the same transaction, so {@code NOW()} gives them the identical creation time and
     * the identifier decides which comes first. PostgreSQL compares a uuid as sixteen unsigned
     * bytes; {@link UUID#compareTo} compares its two halves as signed longs, so the two disagree
     * for roughly half of all pairs. Sorting the merge one way while the cursor predicate filters
     * the other way would drop or repeat exactly those tied rows at a page boundary.
     */
    private static final Comparator<AdminViolationResponse> VIOLATION_ORDER =
            Comparator.comparing(AdminViolationResponse::createdAt)
                    .thenComparing(
                            AdminViolationResponse::id,
                            UserDisciplineServiceImpl::compareAsPostgresOrders)
                    .reversed();

    private static int compareAsPostgresOrders(UUID left, UUID right) {
        int high =
                Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0
                ? high
                : Long.compareUnsigned(
                        left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }

    private void validateReasonEnabled(String reasonKey) {
        boolean enabled =
                reportReasonConfigReader
                        .findEnabledByReasonKey(reasonKey)
                        // An unknown key and a disabled one are the same answer to the caller: this
                        // is not a reason you may cite right now. Splitting them would let a caller
                        // enumerate which reasons exist but are switched off.
                        .orElse(false);
        if (!enabled) {
            throw new AppException(ApiErrorCode.WARNING_REASON_DISABLED);
        }
    }

    private UserStrike issueStrike(UUID actorId, User target, List<UUID> countingWarningIds) {
        short strikeNumber = (short) (userStrikeRepository.countActiveStrikes(target.getId()) + 1);
        UserStatus intendedStatus = consequenceStatus(strikeNumber);
        OffsetDateTime intendedUntil = consequenceSuspendedUntil(strikeNumber);
        boolean applied = applyConsequenceIfStronger(target, intendedStatus, intendedUntil);

        // Second audit row, with a null actor. The strike is a consequence of the rule, not a
        // decision the moderator took: it wrote the warning, the ladder wrote this. Recording the
        // moderator as the actor would attribute a ban to someone who never chose one, which is
        // exactly the sort of thing an audit log exists to get right.
        AdminActionResponse strikeAudit =
                adminActionRecorder.record(
                        null,
                        AdminActionType.ISSUE_STRIKE,
                        target.getId(),
                        TARGET_ENTITY_TYPE,
                        target.getId(),
                        null,
                        "Automatic strike after " + WARNINGS_PER_STRIKE + " active warnings",
                        Map.of(
                                "triggeredByModeratorId",
                                actorId.toString(),
                                "strikeNumber",
                                strikeNumber,
                                "warningIds",
                                countingWarningIds.stream().map(UUID::toString).toList(),
                                "consequenceApplied",
                                applied,
                                "resultingStatus",
                                target.getStatus().toJson()));

        UserStrike strike =
                userStrikeRepository.saveAndFlush(
                        UserStrike.builder()
                                .userId(target.getId())
                                .strikeNumber(strikeNumber)
                                .triggeredBy(actorId)
                                .adminActionId(strikeAudit.id())
                                .build());
        log.info(
                "Strike issued: targetId={}, strikeNumber={}, consequenceApplied={},"
                        + " resultingStatus={}",
                target.getId(),
                strikeNumber,
                applied,
                target.getStatus().toJson());
        return strike;
    }

    private static UserStatus consequenceStatus(short strikeNumber) {
        return strikeNumber >= 3 ? UserStatus.BANNED : UserStatus.SUSPENDED;
    }

    private static OffsetDateTime consequenceSuspendedUntil(short strikeNumber) {
        return switch (strikeNumber) {
            case 1 -> OffsetDateTime.now(ZoneOffset.UTC).plusDays(STRIKE_ONE_SUSPENSION_DAYS);
            case 2 -> OffsetDateTime.now(ZoneOffset.UTC).plusDays(STRIKE_TWO_SUSPENSION_DAYS);
            default -> null;
        };
    }

    /**
     * Applies the strike's consequence only when it is strictly stronger than the account's current
     * state, and reports whether it did.
     *
     * <p>A strike must never weaken a penalty already in force. Without this, a moderator issuing
     * an ordinary warning against an account an administrator had banned would silently downgrade
     * that ban to a seven-day suspension. The strike row is still written either way, because the
     * strike happened; only the status change is conditional.
     */
    private boolean applyConsequenceIfStronger(
            User target, UserStatus intendedStatus, OffsetDateTime intendedUntil) {
        if (!isStrictlyStronger(
                intendedStatus, intendedUntil, target.getStatus(), target.getSuspendedUntil())) {
            return false;
        }
        target.setStatus(intendedStatus);
        target.setSuspendedUntil(intendedStatus == UserStatus.SUSPENDED ? intendedUntil : null);
        adminUserRepository.save(target);
        // The ladder is the second writer of users.status. The badge withdrawal was wired into the
        // administrator's own endpoint only, so a three-strike ban - the strongest action in the
        // system - used to leave the platform's identity claim standing. Same transaction as the
        // save, so the status and the badge can never be observed disagreeing.
        verificationService.applyStatusChange(target.getId(), intendedStatus);
        return true;
    }

    /**
     * Severity comparison over the account-state ladder.
     *
     * <p>The ranks are active, then a suspension that ends, then one that does not, then a ban.
     * Within the "suspension that ends" rank the later end date is the stronger state, so strike
     * two's thirty days does replace strike one's seven: extending a suspension is not weakening
     * it, and without this tie-break an account struck twice in one week would serve seven days
     * instead of thirty.
     *
     * <p>Deactivated is ranked with banned rather than placed on the ladder. It is the state a
     * self-service account removal would produce, and no strike should undo one.
     */
    private static boolean isStrictlyStronger(
            UserStatus intendedStatus,
            OffsetDateTime intendedUntil,
            UserStatus currentStatus,
            OffsetDateTime currentUntil) {
        int intendedRank = severityRank(intendedStatus, intendedUntil);
        int currentRank = severityRank(currentStatus, currentUntil);
        if (intendedRank != currentRank) {
            return intendedRank > currentRank;
        }
        return intendedRank == 1 && intendedUntil != null && intendedUntil.isAfter(currentUntil);
    }

    private static int severityRank(UserStatus status, OffsetDateTime suspendedUntil) {
        return switch (status) {
            case ACTIVE -> 0;
            case SUSPENDED -> suspendedUntil == null ? 2 : 1;
            case BANNED, DEACTIVATED -> 3;
        };
    }

    private void enqueueWarningNotification(UUID userId, UUID warningId, UUID adminActionId) {
        // Same transaction as the warning row, through the outbox, so the account is told if and
        // only if the warning was actually recorded.
        outboxService.enqueue(
                AdminEventTypes.USER_WARNED_V1,
                AdminEventTypes.USER_WARNED_V1,
                TARGET_ENTITY_TYPE,
                userId,
                null,
                Map.of(
                        "userId",
                        userId.toString(),
                        "warningId",
                        warningId.toString(),
                        "adminActionId",
                        adminActionId.toString()));
    }

    private static String encode(OffsetDateTime createdAt, UUID id, String scope) {
        return CursorCodec.encode(new Cursor(TimeCursors.toMicros(createdAt), id), scope);
    }

    private static int normalizeLimit(int limit) {
        return limit < 1 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
    }
}
