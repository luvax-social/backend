package com.app.modules.support.service.impl;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.common.security.service.RateLimiterService;
import com.app.common.turnstile.TurnstileOutcome;
import com.app.common.turnstile.TurnstileSurface;
import com.app.common.turnstile.TurnstileVerifier;
import com.app.common.vocabulary.service.VocabularyService;
import com.app.modules.admin.enums.AdminActionType;
import com.app.modules.admin.repository.AdminActionRepository;
import com.app.modules.admin.service.AdminActionRecorder;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.service.NotificationService;
import com.app.modules.support.dto.request.CreateSupportTicketRequest;
import com.app.modules.support.dto.request.EscalateSupportTicketRequest;
import com.app.modules.support.dto.request.PublicSupportTicketRequest;
import com.app.modules.support.dto.request.RespondSupportTicketRequest;
import com.app.modules.support.dto.request.SignedAppealRequest;
import com.app.modules.support.dto.response.AppealLinkResponse;
import com.app.modules.support.dto.response.SupportTicketResponse;
import com.app.modules.support.dto.response.SupportTicketStaffResponse;
import com.app.modules.support.entity.SupportTicket;
import com.app.modules.support.enums.SupportCategory;
import com.app.modules.support.enums.SupportSource;
import com.app.modules.support.enums.SupportTicketStatus;
import com.app.modules.support.mapper.SupportTicketMapper;
import com.app.modules.support.repository.SupportTicketRepository;
import com.app.modules.support.service.SupportAuthorizationService;
import com.app.modules.support.service.SupportAuthorizationService.StaffAction;
import com.app.modules.support.service.SupportConfirmationMailer;
import com.app.modules.support.service.SupportTicketService;
import com.app.modules.support.service.SupportTokenService;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.repository.UserRepository;

@Service
public class SupportTicketServiceImpl implements SupportTicketService {

    private static final String TARGET_ENTITY_TYPE = "support_ticket";
    private static final int MAX_PAGE_SIZE = 100;

    // The daily half of the public-form bound. AuthRateLimitFilter carries one window per endpoint
    // rule, and the hourly bound of 3 lives there; a second window cannot be expressed in that
    // configuration, so the daily bound of 10 is enforced here on the same Redis sliding-window
    // primitive the filter uses. Keyed on the submitted address rather than the IP, so the two
    // bounds are independent: the filter stops one address bursting from one machine, and this
    // stops one address being used all day from many.
    private static final int PUBLIC_DAILY_LIMIT = 10;
    private static final long PUBLIC_DAILY_WINDOW_SECONDS = 86400L;

    private final SupportTicketRepository supportTicketRepository;
    private final SupportAuthorizationService supportAuthorizationService;
    private final SupportTokenService supportTokenService;
    private final TurnstileVerifier turnstileVerifier;
    private final SupportConfirmationMailer confirmationMailer;
    private final SupportTicketMapper supportTicketMapper;
    private final UserRepository userRepository;
    private final AdminActionRepository adminActionRepository;
    private final AdminActionRecorder adminActionRecorder;
    private final NotificationService notificationService;
    private final RateLimiterService rateLimiterService;
    private final VocabularyService vocabularyService;

    public SupportTicketServiceImpl(
            SupportTicketRepository supportTicketRepository,
            SupportAuthorizationService supportAuthorizationService,
            SupportTokenService supportTokenService,
            TurnstileVerifier turnstileVerifier,
            SupportConfirmationMailer confirmationMailer,
            SupportTicketMapper supportTicketMapper,
            UserRepository userRepository,
            AdminActionRepository adminActionRepository,
            AdminActionRecorder adminActionRecorder,
            NotificationService notificationService,
            RateLimiterService rateLimiterService,
            VocabularyService vocabularyService) {
        this.supportTicketRepository = supportTicketRepository;
        this.supportAuthorizationService = supportAuthorizationService;
        this.supportTokenService = supportTokenService;
        this.turnstileVerifier = turnstileVerifier;
        this.confirmationMailer = confirmationMailer;
        this.supportTicketMapper = supportTicketMapper;
        this.userRepository = userRepository;
        this.adminActionRepository = adminActionRepository;
        this.adminActionRecorder = adminActionRecorder;
        this.notificationService = notificationService;
        this.rateLimiterService = rateLimiterService;
        this.vocabularyService = vocabularyService;
    }

    @Override
    @Transactional
    public SupportTicketResponse createAuthenticated(
            UUID userId, CreateSupportTicketRequest request) {
        User user = requireUser(userId);
        requireNoOpenTicket(userId);
        SupportTicket ticket =
                SupportTicket.builder()
                        .userId(userId)
                        // Captured even here, because the account may be banned by the time staff
                        // answer and this address is then the only channel that reaches the person.
                        .contactEmail(user.getEmail())
                        .category(request.category())
                        .subject(request.subject())
                        .body(request.body())
                        .status(SupportTicketStatus.OPEN)
                        .source(SupportSource.AUTHENTICATED)
                        .build();
        return supportTicketMapper.toOwnerResponse(supportTicketRepository.save(ticket));
    }

    @Override
    @Transactional
    public SupportTicketResponse createFromSignedLink(SignedAppealRequest request) {
        // Redeeming the token mints nothing. No session, no refresh token row, no security context.
        // It authorises exactly one write: this ticket, against the audit row the token names.
        //
        // Read without spending, so every refusal below leaves the token redeemable. The appeal
        // link is the only credential a banned account holds and it arrives in a mail they cannot
        // cause to be resent, so an ordinary refusal - already holding an open ticket, say - must
        // not cost them the whole thirty-day window. Consumption happens last, below.
        SupportTokenService.AppealGrant grant =
                supportTokenService.peekAppealToken(request.token());
        User user = requireUser(grant.userId());
        requireNoOpenTicket(grant.userId());
        SupportTicket ticket =
                SupportTicket.builder()
                        .userId(grant.userId())
                        .contactEmail(user.getEmail())
                        // From the token, never from the request. A client-supplied category would
                        // let the submitter appeal something the token did not authorise.
                        .category(grant.category())
                        .subject(request.subject())
                        .body(request.body())
                        .status(SupportTicketStatus.OPEN)
                        .source(SupportSource.SIGNED_LINK)
                        .adminActionId(grant.adminActionId())
                        .build();
        // Flushed here so a constraint violation surfaces while the token is still unspent.
        SupportTicket saved = supportTicketRepository.saveAndFlush(ticket);
        // Spent last, once nothing above can still refuse. The consume is atomic, so two requests
        // racing this line still create exactly one ticket: the loser is refused here and its
        // insert rolls back with the transaction.
        supportTokenService.consumeAppealToken(request.token());
        return supportTicketMapper.toOwnerResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public AppealLinkResponse describeSignedLink(String rawToken) {
        // Peek, never consume. This is the read the landing screen makes on mount, so redeeming
        // here would spend the link merely by opening it - and an email client prefetching the URL
        // would spend it before the reader ever saw the page.
        return new AppealLinkResponse(supportTokenService.peekAppealToken(rawToken).category());
    }

    @Override
    @Transactional
    public void createPublic(PublicSupportTicketRequest request, String clientIp) {
        // support_category_configs is the authority for what this form may carry, and it is read
        // from the same rows the form's own selector is built from, so the advertised set and the
        // accepted set cannot disagree. They previously came from two sources and agreed only by
        // coincidence of maintenance: disabling a category hid it from the selector while this
        // path kept accepting it from any client that posted the key directly.
        if (!vocabularyService.allowsPublicForm(
                request.category().name().toLowerCase(java.util.Locale.ROOT))) {
            throw new AppException(ApiErrorCode.SUPPORT_CATEGORY_NOT_PUBLIC);
        }
        // Defence in depth, not the rule. The table above is authoritative; these two assertions
        // restate the invariants a config edit must never violate, so a row that set
        // allows_public_form on an appeal or on verification_request fails here rather than
        // creating a ticket no decision path can act on. An appeal needs an audit row to appeal
        // against, which only a signed link supplies. A verification request needs its structured
        // child row, which only the authenticated submit path writes.
        if (request.category().isAppeal()
                || request.category() == SupportCategory.VERIFICATION_REQUEST) {
            throw new AppException(ApiErrorCode.SUPPORT_CATEGORY_NOT_PUBLIC);
        }
        // Verified before anything is written, so a failed challenge leaves no row behind.
        //
        // Fails closed on every outcome that is not a positive confirmation, which is the opposite
        // of AuthTurnstileGuard and is deliberate. The auth and report surfaces can afford to admit
        // a request Cloudflare did not answer for, because a per-caller rate-limit rule still
        // stands behind them. This form has no such rule to fall back on - Turnstile plus the email
        // confirmation are the whole of its defence - so an outage must stop it rather than open
        // it. See the TurnstileVerifier class comment for why the reverse would be exploitable.
        if (turnstileVerifier.verify(
                        request.turnstileToken(), clientIp, TurnstileSurface.PUBLIC_SUPPORT)
                != TurnstileOutcome.VERIFIED) {
            throw new AppException(ApiErrorCode.SUPPORT_CAPTCHA_FAILED);
        }
        String email = request.contactEmail().trim().toLowerCase(java.util.Locale.ROOT);
        if (!rateLimiterService.isAllowed(
                "support:public:daily:" + email, PUBLIC_DAILY_LIMIT, PUBLIC_DAILY_WINDOW_SECONDS)) {
            throw new AppException(ApiErrorCode.SUPPORT_DAILY_LIMIT_REACHED);
        }
        // Resolved when it happens to match an account, so a confirmed submission from a banned
        // user's own address still ties back to them. Never required: the whole point of this path
        // is that the submitter may have no account at all.
        UUID resolvedUserId =
                userRepository.findByEmailIgnoreCase(email).map(User::getId).orElse(null);
        SupportTicket ticket =
                SupportTicket.builder()
                        .userId(resolvedUserId)
                        .contactEmail(email)
                        .category(request.category())
                        .subject(request.subject())
                        .body(request.body())
                        // Invisible to staff until the address is confirmed. Turnstile proves the
                        // submitter is probably not a bot; only this proves they can read the
                        // mailbox they named, which is what stops the form being used to open
                        // tickets in somebody else's name.
                        .status(SupportTicketStatus.PENDING_CONFIRMATION)
                        .source(SupportSource.PUBLIC_FORM)
                        .build();
        SupportTicket saved = supportTicketRepository.save(ticket);
        confirmationMailer.sendConfirmation(
                saved.getContactEmail(),
                supportTokenService.createConfirmationToken(saved.getId()));
    }

    @Override
    @Transactional
    public void confirmPublic(String rawToken) {
        // Read without spending, for the same reason as the appeal path: a link that refuses must
        // stay usable, because this is the only thing that moves the submission out of
        // PENDING_CONFIRMATION and staff cannot see it until it does.
        UUID ticketId = supportTokenService.peekConfirmationToken(rawToken);
        // The guard is in the UPDATE predicate, so replaying a link cannot resurrect a ticket that
        // has since been answered or rejected. Refusing here leaves the token intact.
        if (supportTicketRepository.confirmIfPending(ticketId) == 0) {
            throw new AppException(ApiErrorCode.SUPPORT_TOKEN_INVALID);
        }
        // Spent last, once the ticket is confirmed. A second visit - a mail client prefetching the
        // link, or the reader opening it twice - is refused by the UPDATE predicate above rather
        // than by a missing token, so it still answers with a reason.
        supportTokenService.consumeConfirmationToken(rawToken);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupportTicketResponse> listOwn(UUID userId, int limit) {
        return supportTicketRepository
                .findOwnTickets(userId, PageRequest.of(0, pageSize(limit)))
                .stream()
                .map(supportTicketMapper::toOwnerResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SupportTicketResponse getOwn(UUID userId, UUID ticketId) {
        SupportTicket ticket = requireTicket(ticketId);
        supportAuthorizationService.assertIsOwner(userId, ticket);
        return supportTicketMapper.toOwnerResponse(ticket);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupportTicketStaffResponse> listForStaff(
            UUID actorId, SupportTicketStatus status, int limit) {
        requireStaff(actorId);
        PageRequest page = PageRequest.of(0, pageSize(limit));
        return (status == null
                        ? supportTicketRepository.findStaffQueue(page)
                        : supportTicketRepository.findStaffQueueByStatus(status, page))
                .stream().map(supportTicketMapper::toStaffResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SupportTicketStaffResponse getForStaff(UUID actorId, UUID ticketId) {
        UserRole actorRole = requireStaff(actorId);
        SupportTicket ticket = requireStaffVisibleTicket(ticketId);
        supportAuthorizationService.assertMayAct(
                actorId, actorRole, ticket, StaffAction.READ, decisionAuthorOf(ticket));
        return supportTicketMapper.toStaffResponse(ticket);
    }

    @Override
    @Transactional
    public SupportTicketStaffResponse claim(UUID actorId, UUID ticketId) {
        UserRole actorRole = requireStaff(actorId);
        SupportTicket ticket = requireStaffVisibleTicket(ticketId);
        supportAuthorizationService.assertMayAct(
                actorId, actorRole, ticket, StaffAction.CLAIM, decisionAuthorOf(ticket));
        // The predicate re-checks that the ticket is still unassigned, so two moderators claiming
        // at
        // once produce one winner and one refusal rather than a silent overwrite.
        if (supportTicketRepository.claimIfUnassigned(
                        ticketId, actorId, OffsetDateTime.now(ZoneOffset.UTC))
                == 0) {
            throw new AppException(ApiErrorCode.SUPPORT_TICKET_ALREADY_CLAIMED);
        }
        return supportTicketMapper.toStaffResponse(requireTicket(ticketId));
    }

    @Override
    @Transactional
    public SupportTicketStaffResponse respond(
            UUID actorId, UUID ticketId, RespondSupportTicketRequest request, boolean reject) {
        UserRole actorRole = requireStaff(actorId);
        SupportTicket ticket = requireStaffVisibleTicket(ticketId);
        SupportTicketStatus target =
                reject ? SupportTicketStatus.REJECTED : SupportTicketStatus.ANSWERED;
        supportAuthorizationService.assertMayAct(
                actorId,
                actorRole,
                ticket,
                reject ? StaffAction.REJECT : StaffAction.RESPOND,
                decisionAuthorOf(ticket));
        validateTransition(ticket.getStatus(), target);

        ticket.setStaffResponse(request.staffResponse());
        ticket.setInternalNote(request.internalNote());
        ticket.setRespondedBy(actorId);
        ticket.setRespondedAt(OffsetDateTime.now(ZoneOffset.UTC));
        ticket.setStatus(target);
        SupportTicket saved = supportTicketRepository.save(ticket);

        // The audit row is what raises the mail: AdminActionRecorder maps these two action types to
        // notice templates and enqueues the event in this same transaction. The metadata carries
        // the
        // response text and nothing else, which is what keeps internal_note out of the mail - the
        // note is never placed in the map, so no template can render it.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(AdminActionRecorder.SUPPORT_RESPONSE_KEY, request.staffResponse());
        adminActionRecorder.record(
                actorId,
                reject
                        ? AdminActionType.REJECT_SUPPORT_TICKET
                        : AdminActionType.RESPOND_SUPPORT_TICKET,
                saved.getUserId(),
                TARGET_ENTITY_TYPE,
                saved.getId(),
                null,
                request.staffResponse(),
                metadata);
        notifyOwner(saved);
        return supportTicketMapper.toStaffResponse(saved);
    }

    @Override
    @Transactional
    public SupportTicketStaffResponse escalate(
            UUID actorId, UUID ticketId, EscalateSupportTicketRequest request) {
        UserRole actorRole = requireStaff(actorId);
        SupportTicket ticket = requireStaffVisibleTicket(ticketId);
        supportAuthorizationService.assertMayAct(
                actorId, actorRole, ticket, StaffAction.ESCALATE, decisionAuthorOf(ticket));
        validateTransition(ticket.getStatus(), SupportTicketStatus.ESCALATED);

        ticket.setEscalatedBy(actorId);
        ticket.setEscalatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        ticket.setEscalationReason(request.reason());
        ticket.setStatus(SupportTicketStatus.ESCALATED);
        SupportTicket saved = supportTicketRepository.save(ticket);

        adminActionRecorder.record(
                actorId,
                AdminActionType.ESCALATE_SUPPORT_TICKET,
                saved.getUserId(),
                TARGET_ENTITY_TYPE,
                saved.getId(),
                null,
                request.reason(),
                null);
        return supportTicketMapper.toStaffResponse(saved);
    }

    // The permitted-target map, in the shape ReportServiceImpl.validateTransition uses: one
    // explicit
    // switch and one error code for every disallowed pair.
    private static void validateTransition(
            SupportTicketStatus current, SupportTicketStatus target) {
        boolean valid =
                switch (current) {
                    case OPEN, IN_PROGRESS ->
                            target == SupportTicketStatus.ANSWERED
                                    || target == SupportTicketStatus.REJECTED
                                    || target == SupportTicketStatus.ESCALATED;
                    // An escalated ticket is an administrator's to decide. It does not go back to
                    // the moderator queue, for the same reason an escalated report does not.
                    case ESCALATED ->
                            target == SupportTicketStatus.ANSWERED
                                    || target == SupportTicketStatus.REJECTED;
                    // Terminal, and pending_confirmation is not a staff-reachable state at all.
                    case ANSWERED, REJECTED, PENDING_CONFIRMATION -> false;
                };
        if (!valid) {
            throw new AppException(ApiErrorCode.SUPPORT_TICKET_INVALID_TRANSITION);
        }
    }

    private void notifyOwner(SupportTicket ticket) {
        // A public ticket that never resolved to an account has nobody to notify in-product. The
        // mail still goes to the address, which is the whole point of that path.
        if (ticket.getUserId() == null) {
            return;
        }
        notificationService.create(
                ticket.getRespondedBy(),
                ticket.getUserId(),
                NotificationType.SUPPORT_TICKET_UPDATE,
                TARGET_ENTITY_TYPE,
                ticket.getId(),
                null);
    }

    /**
     * Resolves who wrote the decision a ticket appeals, for the conflict-of-interest rule.
     *
     * <p>Null is the common and correct answer: the ticket may appeal nothing, or the audit row may
     * record an automatic action. {@code admin_actions.admin_id} is null for the strike the
     * discipline ladder writes and for the unsuspend the expiry sweep writes, and a null actor
     * blocks nobody.
     */
    private UUID decisionAuthorOf(SupportTicket ticket) {
        if (ticket.getAdminActionId() == null) {
            return null;
        }
        return adminActionRepository
                .findById(ticket.getAdminActionId())
                .map(action -> action.getAdminId())
                .orElse(null);
    }

    private void requireNoOpenTicket(UUID userId) {
        // The partial unique index is what actually holds under a concurrent double submit. This
        // check exists so the ordinary case answers with a named code rather than a constraint
        // violation surfacing as a 500.
        if (supportTicketRepository.hasOpenTicket(userId)) {
            throw new AppException(ApiErrorCode.SUPPORT_TICKET_ALREADY_OPEN);
        }
    }

    private UserRole requireStaff(UUID actorId) {
        // Read from the source of truth rather than from a token claim, for the reason every other
        // authorization decision in this codebase does: a claim minted before a demotion is stale.
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

    private SupportTicket requireTicket(UUID ticketId) {
        return supportTicketRepository
                .findById(ticketId)
                .orElseThrow(() -> new AppException(ApiErrorCode.SUPPORT_TICKET_NOT_FOUND));
    }

    private SupportTicket requireStaffVisibleTicket(UUID ticketId) {
        return Optional.ofNullable(supportTicketRepository.findStaffVisible(ticketId))
                .flatMap(found -> found)
                .orElseThrow(() -> new AppException(ApiErrorCode.SUPPORT_TICKET_NOT_FOUND));
    }

    private static int pageSize(int limit) {
        return Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
    }
}
