package com.app.modules.support.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.support.entity.SupportTicket;

@Repository
public interface SupportTicketRepository extends JpaRepository<SupportTicket, UUID> {

    /**
     * Whether the account already holds an ordinary support ticket that is not terminal.
     *
     * <p>The service-layer half of the one-open-ticket rule. The other half is the partial unique
     * index {@code uq_support_tickets_one_open_support_per_user}, which is what actually holds
     * under a concurrent double submit; this check exists so the ordinary case returns a named
     * error code instead of surfacing a constraint violation.
     *
     * <p>{@code verification_request} is excluded, matching that index exactly. The guard is per
     * lane rather than global from V107 onward: a pending verification request must not stand in
     * the way of contesting a ban, which is the priority inversion a single global guard produced.
     * Leaving it here while the index excluded it would put the refusal back in the service layer
     * and make the database and the code disagree about the same rule.
     *
     * <p>{@code pending_confirmation} is excluded here exactly as it is excluded from the index. An
     * unconfirmed public submission is not yet a real ticket and must not block the account's
     * genuine one.
     *
     * @param userId the account to check
     * @return true when a non-terminal, confirmed, non-verification ticket exists for that account
     */
    @Query(
            value =
                    "SELECT EXISTS (SELECT 1 FROM support_tickets WHERE user_id = :userId"
                            + " AND category <> 'verification_request'"
                            + " AND status IN ('open', 'in_progress', 'escalated'))",
            nativeQuery = true)
    boolean hasOpenTicket(@Param("userId") UUID userId);

    /**
     * Claims an unassigned ticket for one staff member.
     *
     * <p>The {@code assigned_to IS NULL} predicate is the whole point: two moderators claiming at
     * once produce one update of 1 and one of 0, and the loser is told rather than silently
     * overwriting the winner. Shaped on {@code AdminUserRepository.reinstateExpiredSuspension},
     * which resolves the same race the same way.
     *
     * <p>Escalated reports have no ownership model at all today, and that gap is why this one is
     * guarded from the start.
     *
     * @param ticketId the ticket to claim
     * @param staffId the claiming staff member
     * @param claimedAt the claim timestamp
     * @return 1 when this call claimed the ticket, 0 when someone else already held it
     */
    @Modifying
    @Query(
            value =
                    "UPDATE support_tickets SET assigned_to = :staffId, assigned_at = :claimedAt,"
                            + " status = 'in_progress' WHERE id = :ticketId"
                            + " AND assigned_to IS NULL AND status = 'open'",
            nativeQuery = true)
    int claimIfUnassigned(
            @Param("ticketId") UUID ticketId,
            @Param("staffId") UUID staffId,
            @Param("claimedAt") OffsetDateTime claimedAt);

    /**
     * Confirms a public submission, moving it into the staff queue.
     *
     * <p>Guarded on the current status so replaying a confirmation link cannot resurrect a ticket
     * that has since been answered or rejected.
     *
     * @param ticketId the ticket to confirm
     * @return 1 when this call confirmed it, 0 when it was not pending confirmation
     */
    @Modifying
    @Query(
            value =
                    "UPDATE support_tickets SET status = 'open' WHERE id = :ticketId"
                            + " AND status = 'pending_confirmation'",
            nativeQuery = true)
    int confirmIfPending(@Param("ticketId") UUID ticketId);

    /** A user's own tickets, newest first. Never exposes another account's rows. */
    @Query(
            "SELECT t FROM SupportTicket t WHERE t.userId = :userId"
                    + " ORDER BY t.createdAt DESC, t.id DESC")
    List<SupportTicket> findOwnTickets(@Param("userId") UUID userId, Pageable pageable);

    /**
     * The staff queue.
     *
     * <p>{@code pending_confirmation} is excluded unconditionally: an unconfirmed public submission
     * has not proved the submitter controls the address and must not reach a moderator's queue.
     *
     * @param status status to match, or null for every staff-visible status
     * @param pageable page size carrier
     * @return matching tickets, newest first
     */
    @Query(
            "SELECT t FROM SupportTicket t WHERE t.status <> com.app.modules.support.enums"
                    + ".SupportTicketStatus.PENDING_CONFIRMATION"
                    + " ORDER BY t.createdAt DESC, t.id DESC")
    List<SupportTicket> findStaffQueue(Pageable pageable);

    /**
     * The staff queue narrowed to one status.
     *
     * <p>A separate method rather than a nullable parameter on the one above, and the reason is a
     * real failure rather than a preference. {@code (:status IS NULL OR t.status = :status)}
     * against a PostgreSQL enum column leaves the driver unable to infer a type for the bare
     * parameter in {@code ? IS NULL}, and PostgreSQL answers {@code could not determine data type
     * of parameter $1}. Every call to the combined form failed with a 500 at runtime; only a mocked
     * repository made it look sound.
     *
     * @param status the status to match
     * @param pageable page size carrier
     * @return matching tickets, newest first
     */
    @Query(
            "SELECT t FROM SupportTicket t WHERE t.status = :status"
                    + " AND t.status <> com.app.modules.support.enums.SupportTicketStatus"
                    + ".PENDING_CONFIRMATION"
                    + " ORDER BY t.createdAt DESC, t.id DESC")
    List<SupportTicket> findStaffQueueByStatus(
            @Param("status") com.app.modules.support.enums.SupportTicketStatus status,
            Pageable pageable);

    /**
     * Whether the account already holds an outstanding verification request.
     *
     * <p>The verification half of the split guard. {@code
     * uq_support_tickets_one_open_support_per_user} and {@code
     * uq_support_tickets_one_open_verification_per_user} (V107) hold one lane each, so a pending
     * verification request no longer blocks a ban appeal and an open appeal no longer blocks a
     * verification request. This check exists so the ordinary case answers a named error code
     * instead of surfacing a constraint violation.
     *
     * @param userId the account to check
     * @return true when a non-terminal verification request already exists for that account
     */
    @Query(
            value =
                    "SELECT EXISTS (SELECT 1 FROM support_tickets WHERE user_id = :userId"
                            + " AND category = 'verification_request'"
                            + " AND status IN ('open', 'in_progress', 'escalated'))",
            nativeQuery = true)
    boolean hasOpenVerificationRequest(@Param("userId") UUID userId);

    /**
     * One account's verification requests, newest first.
     *
     * @param userId the account
     * @param pageable page size carrier
     * @return verification tickets newest first
     */
    @Query(
            "SELECT t FROM SupportTicket t WHERE t.userId = :userId"
                    + " AND t.category = com.app.modules.support.enums.SupportCategory"
                    + ".VERIFICATION_REQUEST"
                    + " ORDER BY t.createdAt DESC, t.id DESC")
    List<SupportTicket> findVerificationTickets(@Param("userId") UUID userId, Pageable pageable);

    /**
     * The verification review queue.
     *
     * <p>Separate from {@code findStaffQueue} so a moderator working verification is not reading
     * past ban appeals, and so the general queue is not diluted by requests that need a different
     * surface to review. {@code pending_confirmation} cannot occur here, because a verification
     * request is only ever created by an authenticated caller, but the predicate is kept for
     * symmetry with the general queue.
     *
     * @param status status to match, or null for every staff-visible status
     * @param pageable page size carrier
     * @return verification tickets newest first
     */
    @Query(
            "SELECT t FROM SupportTicket t WHERE t.category = com.app.modules.support.enums"
                    + ".SupportCategory.VERIFICATION_REQUEST"
                    + " AND t.status <> com.app.modules.support.enums.SupportTicketStatus"
                    + ".PENDING_CONFIRMATION"
                    + " ORDER BY t.createdAt DESC, t.id DESC")
    List<SupportTicket> findVerificationQueue(Pageable pageable);

    /**
     * The verification queue narrowed to one status.
     *
     * <p>Split from the unfiltered form for the same typing reason as {@code
     * findStaffQueueByStatus}: a nullable enum parameter inside {@code ? IS NULL} cannot be typed
     * by PostgreSQL and fails at runtime.
     *
     * @param status the status to match
     * @param pageable page size carrier
     * @return matching verification tickets, newest first
     */
    @Query(
            "SELECT t FROM SupportTicket t WHERE t.category = com.app.modules.support.enums"
                    + ".SupportCategory.VERIFICATION_REQUEST"
                    + " AND t.status = :status"
                    + " AND t.status <> com.app.modules.support.enums.SupportTicketStatus"
                    + ".PENDING_CONFIRMATION"
                    + " ORDER BY t.createdAt DESC, t.id DESC")
    List<SupportTicket> findVerificationQueueByStatus(
            @Param("status") com.app.modules.support.enums.SupportTicketStatus status,
            Pageable pageable);

    /**
     * Whether this decision has already been appealed, in any status.
     *
     * <p>One decision, one appeal. A terminal first appeal does not trip the one-open-ticket guard,
     * so without this a rejected appeal could be refiled indefinitely and the same audit row could
     * accumulate contradictory verdicts.
     *
     * <p>Served by {@code idx_support_tickets_admin_action} (V100), whose {@code admin_action_id IS
     * NOT NULL} predicate this query satisfies because the parameter is never null here.
     *
     * @param adminActionId the audit row being appealed
     * @return true when a ticket already appeals that row
     */
    @Query(
            value =
                    "SELECT EXISTS (SELECT 1 FROM support_tickets"
                            + " WHERE admin_action_id = :adminActionId)",
            nativeQuery = true)
    boolean hasAppealForAction(@Param("adminActionId") UUID adminActionId);

    /** Reads a ticket only when it is visible to staff. */
    @Query(
            "SELECT t FROM SupportTicket t WHERE t.id = :ticketId AND t.status <> com.app.modules"
                    + ".support.enums.SupportTicketStatus.PENDING_CONFIRMATION")
    Optional<SupportTicket> findStaffVisible(@Param("ticketId") UUID ticketId);
}
