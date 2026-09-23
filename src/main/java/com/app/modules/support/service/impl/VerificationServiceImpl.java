package com.app.modules.support.service.impl;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.outbox.service.OutboxService;
import com.app.common.response.UserSummaryResponse;
import com.app.modules.admin.dto.response.AdminActionResponse;
import com.app.modules.admin.enums.AdminActionType;
import com.app.modules.admin.service.AdminActionRecorder;
import com.app.modules.admin.service.AdminAuthorizationService;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.service.NotificationDraft;
import com.app.modules.notification.service.NotificationService;
import com.app.modules.support.dto.request.CreateVerificationRequest;
import com.app.modules.support.dto.request.VerificationDecisionRequest;
import com.app.modules.support.dto.response.VerificationCategoryResponse;
import com.app.modules.support.dto.response.VerificationGrantResponse;
import com.app.modules.support.dto.response.VerificationQueueItemResponse;
import com.app.modules.support.dto.response.VerificationStateResponse;
import com.app.modules.support.entity.SupportTicket;
import com.app.modules.support.entity.VerificationCategory;
import com.app.modules.support.entity.VerificationRequest;
import com.app.modules.support.enums.SupportCategory;
import com.app.modules.support.enums.SupportSource;
import com.app.modules.support.enums.SupportTicketStatus;
import com.app.modules.support.messaging.SupportEventTypes;
import com.app.modules.support.repository.SupportTicketRepository;
import com.app.modules.support.repository.VerificationCategoryRepository;
import com.app.modules.support.repository.VerificationRequestRepository;
import com.app.modules.support.service.SupportAuthorizationService;
import com.app.modules.support.service.SupportAuthorizationService.StaffAction;
import com.app.modules.support.service.VerificationService;
import com.app.modules.users.entity.User;
import com.app.modules.users.entity.UserVerification;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.enums.VerificationRevocationActor;
import com.app.modules.users.repository.UserRepository;
import com.app.modules.users.repository.UserVerificationRepository;
import com.app.modules.users.service.UserSummaryService;

@Service
public class VerificationServiceImpl implements VerificationService {

    private static final String TARGET_ENTITY_TYPE = "verification_request";
    private static final String REVOKE_TARGET_ENTITY_TYPE = "user_verification";

    /** The floor the brief sets, enforced again by a CHECK constraint on the table. */
    private static final int MIN_EVIDENCE_FIELDS = 3;

    private static final int MAX_PAGE_SIZE = 100;

    /**
     * The subject stored on the ticket.
     *
     * <p>Fixed rather than user-supplied. A verification ticket's subject carries no information
     * the structured row does not already hold, and a free-text subject on a queue row is one more
     * place for somebody to write something that has to be moderated in its own right.
     */
    private static final String TICKET_SUBJECT = "Verification request";

    private final SupportTicketRepository supportTicketRepository;
    private final VerificationRequestRepository verificationRequestRepository;
    private final VerificationCategoryRepository verificationCategoryRepository;
    private final UserVerificationRepository userVerificationRepository;
    private final SupportAuthorizationService supportAuthorizationService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final AdminActionRecorder adminActionRecorder;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final UserSummaryService userSummaryService;
    private final OutboxService outboxService;

    public VerificationServiceImpl(
            SupportTicketRepository supportTicketRepository,
            VerificationRequestRepository verificationRequestRepository,
            VerificationCategoryRepository verificationCategoryRepository,
            UserVerificationRepository userVerificationRepository,
            SupportAuthorizationService supportAuthorizationService,
            AdminAuthorizationService adminAuthorizationService,
            AdminActionRecorder adminActionRecorder,
            NotificationService notificationService,
            UserRepository userRepository,
            UserSummaryService userSummaryService,
            OutboxService outboxService) {
        this.supportTicketRepository = supportTicketRepository;
        this.verificationRequestRepository = verificationRequestRepository;
        this.verificationCategoryRepository = verificationCategoryRepository;
        this.userVerificationRepository = userVerificationRepository;
        this.supportAuthorizationService = supportAuthorizationService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.adminActionRecorder = adminActionRecorder;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.userSummaryService = userSummaryService;
        this.outboxService = outboxService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<VerificationCategoryResponse> listCategories() {
        return verificationCategoryRepository.findSelectable().stream()
                .map(
                        category ->
                                new VerificationCategoryResponse(
                                        category.getCategoryKey(),
                                        category.getDisplayName(),
                                        category.getCovers(),
                                        category.getIconKey()))
                .toList();
    }

    @Override
    @Transactional
    public VerificationStateResponse submit(UUID userId, CreateVerificationRequest request) {
        User user = requireUser(userId);

        // The evidence rule is checked first because it is the caller's own error and depends on
        // nothing they could not see. The category and the account state follow, so a caller who
        // sent two fields is told that rather than being told about a category they got right.
        short filled = countFilled(evidenceOf(request));
        if (filled < MIN_EVIDENCE_FIELDS) {
            throw new AppException(ApiErrorCode.VERIFICATION_INSUFFICIENT_EVIDENCE);
        }

        VerificationCategory category =
                verificationCategoryRepository
                        .findById(request.categoryKey())
                        .filter(VerificationCategory::isEnabled)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ApiErrorCode.VERIFICATION_CATEGORY_NOT_FOUND));

        if (userVerificationRepository.findActive(userId).isPresent()) {
            throw new AppException(ApiErrorCode.VERIFICATION_ALREADY_VERIFIED);
        }
        if (supportTicketRepository.hasOpenVerificationRequest(userId)) {
            throw new AppException(ApiErrorCode.SUPPORT_TICKET_ALREADY_OPEN);
        }

        // A resubmission after a moderator revoked the badge is linked to the audit row that
        // withdrew it. This is what extends the conflict-of-interest rule to verification: the
        // staff member who revoked cannot then decide the request contesting their own revocation.
        // Without it the rule would exist for this category and never fire, because nothing else
        // populates admin_action_id on a verification ticket.
        //
        // A system revocation is deliberately excluded. It has no author, so it can produce no
        // conflict, and linking to it would only make the ticket look like an appeal against a
        // decision nobody made.
        UUID contestedActionId =
                userVerificationRepository.findHistory(userId).stream()
                        .filter(grant -> grant.getRevokedAt() != null)
                        .filter(
                                grant ->
                                        grant.getRevocationActor()
                                                == VerificationRevocationActor.MODERATOR)
                        .map(UserVerification::getRevokedActionId)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);

        SupportTicket ticket =
                supportTicketRepository.save(
                        SupportTicket.builder()
                                .userId(userId)
                                .contactEmail(user.getEmail())
                                .category(SupportCategory.VERIFICATION_REQUEST)
                                .subject(TICKET_SUBJECT)
                                .body(bodyFor(category, request))
                                .status(SupportTicketStatus.OPEN)
                                .source(SupportSource.AUTHENTICATED)
                                .adminActionId(contestedActionId)
                                .build());

        verificationRequestRepository.save(
                VerificationRequest.builder()
                        .ticketId(ticket.getId())
                        .userId(userId)
                        .categoryKey(category.getCategoryKey())
                        .claimedName(request.claimedName().trim())
                        .evidenceWebsite(trimToNull(request.evidenceWebsite()))
                        .evidenceOtherProfile(trimToNull(request.evidenceOtherProfile()))
                        .evidenceEmailDomain(trimToNull(request.evidenceEmailDomain()))
                        .evidencePublishedWork(trimToNull(request.evidencePublishedWork()))
                        .evidencePress(trimToNull(request.evidencePress()))
                        .evidenceOfficialListing(trimToNull(request.evidenceOfficialListing()))
                        .evidenceNote(trimToNull(request.evidenceNote()))
                        .evidenceFieldCount(filled)
                        .build());

        return myState(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public VerificationStateResponse myState(UUID userId) {
        Optional<UserVerification> active = userVerificationRepository.findActive(userId);
        List<SupportTicket> latestTickets =
                supportTicketRepository.findVerificationTickets(userId, PageRequest.of(0, 1));
        SupportTicket latest = latestTickets.isEmpty() ? null : latestTickets.get(0);
        VerificationRequest requestRow =
                latest == null
                        ? null
                        : verificationRequestRepository.findById(latest.getId()).orElse(null);
        boolean outstanding = latest != null && !latest.getStatus().isTerminal();

        // A revocation outranks a decided ticket. It is the more recent thing that happened to the
        // account, and it is the one the person needs explained; a stale approval reason underneath
        // a withdrawn badge would read as though the badge were still held.
        String decisionReason =
                userVerificationRepository.findHistory(userId).stream()
                        .filter(grant -> grant.getRevokedAt() != null)
                        .map(UserVerification::getRevocationReason)
                        .findFirst()
                        .orElseGet(
                                () ->
                                        latest != null && latest.getStatus().isTerminal()
                                                ? latest.getStaffResponse()
                                                : null);

        return new VerificationStateResponse(
                active.isPresent(),
                active.map(UserVerification::getCategoryKey).orElse(null),
                active.map(grant -> iconFor(grant.getCategoryKey())).orElse(null),
                active.map(UserVerification::getGrantedAt).orElse(null),
                outstanding ? latest.getId() : null,
                latest == null ? null : latest.getStatus().name().toLowerCase(),
                requestRow == null ? null : requestRow.getCategoryKey(),
                latest == null ? null : latest.getCreatedAt(),
                decisionReason,
                active.isEmpty() && !outstanding);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VerificationQueueItemResponse> queue(
            UUID actorId, SupportTicketStatus status, int limit) {
        requireStaff(actorId);
        PageRequest page = PageRequest.of(0, pageSize(limit));
        return (status == null
                        ? supportTicketRepository.findVerificationQueue(page)
                        : supportTicketRepository.findVerificationQueueByStatus(status, page))
                .stream().map(this::toQueueItem).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public VerificationQueueItemResponse getForStaff(UUID actorId, UUID ticketId) {
        UserRole actorRole = requireStaff(actorId);
        SupportTicket ticket = requireVerificationTicket(ticketId);
        supportAuthorizationService.assertMayAct(
                actorId, actorRole, ticket, StaffAction.READ, decisionAuthorOf(ticket));
        return toQueueItem(ticket);
    }

    @Override
    @Transactional
    public VerificationQueueItemResponse approve(
            UUID actorId, UUID ticketId, VerificationDecisionRequest decision) {
        return decide(actorId, ticketId, decision, true);
    }

    @Override
    @Transactional
    public VerificationQueueItemResponse reject(
            UUID actorId, UUID ticketId, VerificationDecisionRequest decision) {
        return decide(actorId, ticketId, decision, false);
    }

    private VerificationQueueItemResponse decide(
            UUID actorId, UUID ticketId, VerificationDecisionRequest decision, boolean approve) {
        UserRole actorRole = requireStaff(actorId);
        SupportTicket ticket = requireVerificationTicket(ticketId);
        VerificationRequest request = requireRequestRow(ticketId);
        User target = requireUser(request.getUserId());

        // Two gates, each owning what it is for, and neither duplicating the other.
        //
        // The ticket gate carries the workflow rules the support framework already enforces for
        // every category: holding the claim, and the conflict-of-interest rule that stops the staff
        // member who revoked a badge deciding the request contesting that revocation.
        //
        // The admin gate carries the actor-and-target rules, which is where "not yourself" and "not
        // an administrator" live for every administrative action in this codebase. Granting
        // yourself a badge is exactly what that guard exists to stop.
        //
        // The appeal-requires-admin rule inside the ticket gate does not fire here, and that is by
        // construction rather than by exemption: VERIFICATION_REQUEST.isAppeal() is false, so a
        // moderator is admitted to this queue. Verification is a discretionary grant, not a verdict
        // only an administrator can execute.
        supportAuthorizationService.assertMayAct(
                actorId,
                actorRole,
                ticket,
                approve ? StaffAction.RESPOND : StaffAction.REJECT,
                decisionAuthorOf(ticket));
        adminAuthorizationService.assertMayDecideVerification(actorId, actorRole, target);

        SupportTicketStatus targetStatus =
                approve ? SupportTicketStatus.ANSWERED : SupportTicketStatus.REJECTED;
        validateTransition(ticket.getStatus(), targetStatus);

        ticket.setStaffResponse(decision.reason());
        ticket.setInternalNote(decision.internalNote());
        ticket.setRespondedBy(actorId);
        ticket.setRespondedAt(OffsetDateTime.now(ZoneOffset.UTC));
        ticket.setStatus(targetStatus);
        SupportTicket saved = supportTicketRepository.save(ticket);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(AdminActionRecorder.SUPPORT_RESPONSE_KEY, decision.reason());
        metadata.put("categoryKey", request.getCategoryKey());
        AdminActionResponse audit =
                adminActionRecorder.record(
                        actorId,
                        approve
                                ? AdminActionType.GRANT_VERIFICATION
                                : AdminActionType.REJECT_VERIFICATION,
                        request.getUserId(),
                        TARGET_ENTITY_TYPE,
                        ticketId,
                        null,
                        decision.reason(),
                        metadata);

        if (approve) {
            // The grant, the ticket transition and the audit row commit together, following
            // AdminHashtagServiceImpl exactly. users.is_verified and users.verified_category follow
            // from the trigger on this insert; nothing here writes either column.
            userVerificationRepository.save(
                    UserVerification.builder()
                            .userId(request.getUserId())
                            .categoryKey(request.getCategoryKey())
                            .requestTicketId(ticketId)
                            .grantedBy(actorId)
                            .grantedActionId(audit.id())
                            .build());
            enqueueVerificationChanged(request.getUserId());
        }

        // A platform notice: the deciding staff member is not named, as with every other
        // support and enforcement notice.
        notificationService.create(
                NotificationDraft.systemNotice(
                        request.getUserId(),
                        NotificationType.SUPPORT_TICKET_UPDATE,
                        TARGET_ENTITY_TYPE,
                        ticketId,
                        null,
                        null,
                        null));
        return toQueueItem(saved);
    }

    @Override
    @Transactional
    public void revoke(UUID actorId, UUID targetUserId, VerificationDecisionRequest decision) {
        UserRole actorRole = requireStaff(actorId);
        User target = requireUser(targetUserId);
        adminAuthorizationService.assertMayDecideVerification(actorId, actorRole, target);

        UserVerification active =
                userVerificationRepository
                        .findActive(targetUserId)
                        .orElseThrow(() -> new AppException(ApiErrorCode.VERIFICATION_NOT_ACTIVE));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(AdminActionRecorder.SUPPORT_RESPONSE_KEY, decision.reason());
        metadata.put("categoryKey", active.getCategoryKey());
        AdminActionResponse audit =
                adminActionRecorder.record(
                        actorId,
                        AdminActionType.REVOKE_VERIFICATION,
                        targetUserId,
                        REVOKE_TARGET_ENTITY_TYPE,
                        active.getId(),
                        null,
                        decision.reason(),
                        metadata);

        applyRevocation(
                active,
                VerificationRevocationActor.MODERATOR,
                actorId,
                decision.reason(),
                audit.id());
    }

    @Override
    @Transactional
    public boolean applyStatusChange(UUID targetUserId, UserStatus newStatus) {
        // Deactivation retains the badge. It is a voluntary act by the account holder, the account
        // is invisible to everyone while it lasts, and withdrawing a badge there would punish
        // something that is not an offence. An active account obviously keeps it too.
        if (newStatus != UserStatus.SUSPENDED && newStatus != UserStatus.BANNED) {
            return false;
        }
        Optional<UserVerification> active = userVerificationRepository.findActive(targetUserId);
        if (active.isEmpty()) {
            return false;
        }

        String reason =
                newStatus == UserStatus.BANNED
                        ? "The badge was withdrawn automatically because the account was banned."
                        : "The badge was withdrawn automatically because the account was suspended.";

        // No support ticket is created. This is a status-driven side effect, not a request, and a
        // ticket would put a row in the moderator queue that nobody asked for and nobody can act
        // on.
        //
        // The null actor is what distinguishes this from a moderator's decision in the audit log,
        // the same convention the discipline ladder's automatic strike and the suspension expiry
        // sweep already use. revocation_actor records the same fact on the grant row, because the
        // moderator reviewing a resubmission reads that table rather than the audit log.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(AdminActionRecorder.SUPPORT_RESPONSE_KEY, reason);
        metadata.put("categoryKey", active.get().getCategoryKey());
        metadata.put("triggeredByStatus", newStatus.name().toLowerCase());
        AdminActionResponse audit =
                adminActionRecorder.record(
                        null,
                        AdminActionType.REVOKE_VERIFICATION,
                        targetUserId,
                        REVOKE_TARGET_ENTITY_TYPE,
                        active.get().getId(),
                        null,
                        reason,
                        metadata);

        applyRevocation(active.get(), VerificationRevocationActor.SYSTEM, null, reason, audit.id());
        return true;
    }

    private void applyRevocation(
            UserVerification grant,
            VerificationRevocationActor actor,
            UUID revokedBy,
            String reason,
            UUID auditId) {
        grant.setRevokedAt(OffsetDateTime.now(ZoneOffset.UTC));
        grant.setRevocationActor(actor);
        grant.setRevokedBy(revokedBy);
        grant.setRevocationReason(reason);
        grant.setRevokedActionId(auditId);
        userVerificationRepository.save(grant);
        enqueueVerificationChanged(grant.getUserId());
    }

    // Enqueued in the transaction that changes the badge, so the notification tier rewrites the
    // verified-actor flag if and only if the change committed. The rewrite itself runs in the
    // consumer, off this request's path.
    private void enqueueVerificationChanged(UUID userId) {
        outboxService.enqueue(
                SupportEventTypes.USER_VERIFICATION_CHANGED_V1,
                SupportEventTypes.USER_VERIFICATION_CHANGED_V1,
                "user",
                userId,
                null,
                Map.of("userId", userId.toString()));
    }

    private VerificationQueueItemResponse toQueueItem(SupportTicket ticket) {
        VerificationRequest request = requireRequestRow(ticket.getId());
        UserSummaryResponse requester =
                userSummaryService
                        .loadSummaries(List.of(request.getUserId()))
                        .get(request.getUserId());
        List<VerificationGrantResponse> history =
                userVerificationRepository.findHistory(request.getUserId()).stream()
                        .map(
                                grant ->
                                        new VerificationGrantResponse(
                                                grant.getId(),
                                                grant.getCategoryKey(),
                                                grant.getGrantedAt(),
                                                grant.getRevokedAt(),
                                                grant.getRevocationActor() == null
                                                        ? null
                                                        : grant.getRevocationActor()
                                                                .name()
                                                                .toLowerCase(),
                                                grant.getRevocationReason()))
                        .toList();
        return new VerificationQueueItemResponse(
                ticket.getId(),
                requester,
                ticket.getStatus().name().toLowerCase(),
                ticket.getAssignedTo(),
                request.getCategoryKey(),
                request.getClaimedName(),
                request.getEvidenceFieldCount(),
                request.getEvidenceWebsite(),
                request.getEvidenceOtherProfile(),
                request.getEvidenceEmailDomain(),
                request.getEvidencePublishedWork(),
                request.getEvidencePress(),
                request.getEvidenceOfficialListing(),
                request.getEvidenceNote(),
                request.getCreatedAt(),
                history);
    }

    // The same permitted-target map SupportTicketServiceImpl enforces, restated because a
    // verification decision does not travel through that service's respond method. ESCALATED is
    // decidable here for the same reason it is there: an escalated ticket is an administrator's to
    // close, and the admin gate above is what decides whether this actor may.
    private static void validateTransition(
            SupportTicketStatus current, SupportTicketStatus target) {
        boolean valid =
                switch (current) {
                    case OPEN, IN_PROGRESS, ESCALATED ->
                            target == SupportTicketStatus.ANSWERED
                                    || target == SupportTicketStatus.REJECTED;
                    case ANSWERED, REJECTED, PENDING_CONFIRMATION -> false;
                };
        if (!valid) {
            throw new AppException(ApiErrorCode.SUPPORT_TICKET_INVALID_TRANSITION);
        }
    }

    private String iconFor(String categoryKey) {
        return verificationCategoryRepository
                .findById(categoryKey)
                .map(VerificationCategory::getIconKey)
                .orElse(null);
    }

    /**
     * Resolves who wrote the revocation a resubmission contests, for the conflict-of-interest rule.
     *
     * <p>Reads the grant history rather than the audit log, because the grant row already holds
     * both the audit identifier and the staff member who revoked, so no second lookup is needed.
     * Null is the common and correct answer: a first-time request contests nothing.
     */
    private UUID decisionAuthorOf(SupportTicket ticket) {
        if (ticket.getAdminActionId() == null || ticket.getUserId() == null) {
            return null;
        }
        return userVerificationRepository.findHistory(ticket.getUserId()).stream()
                .filter(grant -> ticket.getAdminActionId().equals(grant.getRevokedActionId()))
                .map(UserVerification::getRevokedBy)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    // The ticket body exists so the general support surfaces render something meaningful for a
    // verification ticket. It restates the two identifying fields only; the evidence lives in the
    // structured row, where the review surface reads it field by field.
    private static String bodyFor(
            VerificationCategory category, CreateVerificationRequest request) {
        return "Verification requested in "
                + category.getDisplayName()
                + " for the name "
                + request.claimedName().trim()
                + ".";
    }

    private static String[] evidenceOf(CreateVerificationRequest request) {
        return new String[] {
            request.evidenceWebsite(),
            request.evidenceOtherProfile(),
            request.evidenceEmailDomain(),
            request.evidencePublishedWork(),
            request.evidencePress(),
            request.evidenceOfficialListing(),
            request.evidenceNote()
        };
    }

    private static short countFilled(String[] evidence) {
        short filled = 0;
        for (String value : evidence) {
            if (trimToNull(value) != null) {
                filled++;
            }
        }
        return filled;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private UserRole requireStaff(UUID actorId) {
        // Read from the source of truth rather than from a token claim, for the reason every other
        // authorization decision here does: a claim minted before a demotion is stale.
        UserRole role =
                userRepository
                        .findByIdAndDeletedAtIsNull(actorId)
                        .map(User::getRole)
                        .orElseThrow(() -> new AppException(ApiErrorCode.FORBIDDEN));
        if (role != UserRole.MODERATOR && role != UserRole.ADMIN) {
            throw new AppException(ApiErrorCode.FORBIDDEN);
        }
        return role;
    }

    private User requireUser(UUID userId) {
        return userRepository
                .findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new AppException(ApiErrorCode.USER_NOT_FOUND));
    }

    private VerificationRequest requireRequestRow(UUID ticketId) {
        return verificationRequestRepository
                .findById(ticketId)
                .orElseThrow(() -> new AppException(ApiErrorCode.VERIFICATION_REQUEST_NOT_FOUND));
    }

    private SupportTicket requireVerificationTicket(UUID ticketId) {
        // findStaffVisible, not findById: every other staff-facing query excludes
        // PENDING_CONFIRMATION, and reading one here would let an unconfirmed ticket be decided
        // through the verification routes while the queue that lists tickets never showed it.
        SupportTicket ticket =
                supportTicketRepository
                        .findStaffVisible(ticketId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ApiErrorCode.VERIFICATION_REQUEST_NOT_FOUND));
        // Not-found rather than a type error: a general support ticket reached through the
        // verification routes is not a verification request, and saying so would confirm that a
        // ticket with that identifier exists.
        if (ticket.getCategory() != SupportCategory.VERIFICATION_REQUEST) {
            throw new AppException(ApiErrorCode.VERIFICATION_REQUEST_NOT_FOUND);
        }
        return ticket;
    }

    private static int pageSize(int limit) {
        return Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
    }
}
