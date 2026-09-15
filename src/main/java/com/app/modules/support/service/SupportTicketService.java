package com.app.modules.support.service;

import java.util.List;
import java.util.UUID;

import com.app.common.exception.AppException;
import com.app.modules.support.dto.request.CreateSupportTicketRequest;
import com.app.modules.support.dto.request.EscalateSupportTicketRequest;
import com.app.modules.support.dto.request.InProductAppealRequest;
import com.app.modules.support.dto.request.PublicSupportTicketRequest;
import com.app.modules.support.dto.request.RespondSupportTicketRequest;
import com.app.modules.support.dto.request.SignedAppealRequest;
import com.app.modules.support.dto.response.AppealLinkResponse;
import com.app.modules.support.dto.response.SupportTicketResponse;
import com.app.modules.support.dto.response.SupportTicketStaffResponse;
import com.app.modules.support.enums.SupportTicketStatus;

/**
 * The support centre: one request from a user, one response from staff.
 *
 * <p>Three entry paths exist because {@code TokenPrincipalResolverImpl} admits only {@code ACTIVE}
 * accounts, so the population most likely to need support - banned and suspended accounts - cannot
 * reach an authenticated endpoint at all.
 */
public interface SupportTicketService {

    /**
     * Opens a ticket for an authenticated account.
     *
     * @param userId the authenticated account
     * @param request the ticket
     * @return the ticket as its author sees it
     * @throws AppException {@code SUPPORT_TICKET_ALREADY_OPEN} when the account already holds a
     *     non-terminal ticket
     */
    SupportTicketResponse createAuthenticated(UUID userId, CreateSupportTicketRequest request);

    /**
     * Opens an appeal by redeeming the single-use token from a moderation notice.
     *
     * <p>Redeeming the token mints no session and no refresh token. It authorises exactly one
     * action: creating this one ticket against the audit row the token names.
     *
     * @param request the token and the appeal
     * @return the ticket as its author sees it
     * @throws AppException {@code SUPPORT_TOKEN_INVALID} when the token is unknown, expired or
     *     already redeemed, and {@code SUPPORT_TICKET_ALREADY_OPEN} when the account already holds
     *     a non-terminal ticket
     */
    SupportTicketResponse createFromSignedLink(SignedAppealRequest request);

    /**
     * Opens an appeal from a session, against an audit row the caller owns.
     *
     * <p>The second entry to the same ticket, for the appellant who never lost access. A signed
     * link exists because its holder has no session; requiring one from somebody who does forces a
     * mail round trip for no security gain, and leaves them stranded when that mail never arrives.
     *
     * <p>Ownership is established here by reading {@code admin_actions.target_user_id}, never from
     * the request. A caller who is not the target of the named row receives the same answer an
     * unknown identifier receives, so the endpoint cannot be used to discover whether a given id
     * names a real decision.
     *
     * <p>Shares {@code openAppeal} with {@link #createFromSignedLink}, so the one-open-ticket
     * guard, the one-appeal-per-decision guard and the resulting ticket shape cannot drift between
     * the two paths. Any unspent signed link for the same row is revoked, so the two entries can
     * never both be used against one decision.
     *
     * @param userId the authenticated appellant
     * @param request the audit row and the appeal
     * @return the ticket as its author sees it
     * @throws AppException {@code SUPPORT_APPEAL_ACTION_NOT_FOUND} when the row is unknown or
     *     belongs to another account, {@code SUPPORT_APPEAL_NOT_APPEALABLE} when the action type
     *     carries no appeal category, {@code SUPPORT_APPEAL_ALREADY_FILED} when the decision has
     *     already been appealed, and {@code SUPPORT_TICKET_ALREADY_OPEN} when the account already
     *     holds a non-terminal ticket
     */
    SupportTicketResponse createInProductAppeal(UUID userId, InProductAppealRequest request);

    /**
     * Reports what an appeal link authorises, without redeeming it.
     *
     * <p>Exists so the landing screen can refuse a dead link before the reader writes their appeal
     * rather than after. Presence of a token string is not validity, and the screen previously had
     * nothing else to check: it rendered the whole form for any string at all, and the reader
     * learned the link was dead only on submit, with their text lost.
     *
     * <p>Must not consume the token. Redemption stays with {@link #createFromSignedLink}, which is
     * the one call that spends it.
     *
     * @param rawToken the token from the link
     * @return the appeal category the link authorises
     * @throws AppException {@code SUPPORT_TOKEN_INVALID} when the token is unknown, expired or
     *     already redeemed
     */
    AppealLinkResponse describeSignedLink(String rawToken);

    /**
     * Accepts a public submission, holding it invisible to staff until the address is confirmed.
     *
     * @param request the submission, including its Turnstile token
     * @param clientIp the caller's address as resolved by {@code IpExtractor}, for Turnstile and
     *     for the daily bound
     * @throws AppException {@code SUPPORT_CAPTCHA_FAILED} when Turnstile does not confirm the
     *     token, {@code SUPPORT_CATEGORY_NOT_PUBLIC} for an appeal category, and {@code
     *     SUPPORT_DAILY_LIMIT_REACHED} when the address has submitted too often today
     */
    void createPublic(PublicSupportTicketRequest request, String clientIp);

    /**
     * Confirms a public submission and moves it into the staff queue.
     *
     * @param rawToken the token from the confirmation link
     * @throws AppException {@code SUPPORT_TOKEN_INVALID} when the token is unknown, expired or
     *     already redeemed
     */
    void confirmPublic(String rawToken);

    /**
     * Lists the caller's own tickets, newest first.
     *
     * @param userId the authenticated account
     * @param limit page size
     * @return the account's own tickets
     */
    List<SupportTicketResponse> listOwn(UUID userId, int limit);

    /**
     * Reads one of the caller's own tickets.
     *
     * @param userId the authenticated account
     * @param ticketId the ticket
     * @return the ticket as its author sees it
     * @throws AppException {@code SUPPORT_TICKET_NOT_FOUND} when no such ticket belongs to the
     *     caller
     */
    SupportTicketResponse getOwn(UUID userId, UUID ticketId);

    /**
     * Lists the staff queue.
     *
     * <p>Never includes a ticket still awaiting email confirmation.
     *
     * @param actorId the staff member
     * @param status status to match, or null for every staff-visible status
     * @param limit page size
     * @return matching tickets, newest first
     * @throws AppException {@code FORBIDDEN} when the caller is not staff
     */
    List<SupportTicketStaffResponse> listForStaff(
            UUID actorId, SupportTicketStatus status, int limit);

    /**
     * Reads one ticket as staff.
     *
     * <p>A moderator may read an appeal; only the write paths are narrowed.
     *
     * @param actorId the staff member
     * @param ticketId the ticket
     * @return the staff-facing shape, including the internal note
     * @throws AppException {@code FORBIDDEN} when the caller is not staff
     */
    SupportTicketStaffResponse getForStaff(UUID actorId, UUID ticketId);

    /**
     * Claims an unassigned ticket.
     *
     * @param actorId the claiming staff member
     * @param ticketId the ticket
     * @return the ticket after the claim
     * @throws AppException {@code SUPPORT_TICKET_ALREADY_CLAIMED} when someone else holds it, and
     *     {@code SUPPORT_CONFLICT_OF_INTEREST} when the actor wrote the decision being appealed
     */
    SupportTicketStaffResponse claim(UUID actorId, UUID ticketId);

    /**
     * Answers a ticket and closes it, notifying the user in-product and by mail.
     *
     * @param actorId the responding staff member
     * @param ticketId the ticket
     * @param request the response and the optional internal note
     * @param reject true to close as rejected rather than answered
     * @return the ticket after the response
     * @throws AppException {@code SUPPORT_APPEAL_REQUIRES_ADMIN} when a moderator attempts to
     *     decide an appeal, {@code SUPPORT_CONFLICT_OF_INTEREST} when the actor wrote the decision
     *     being appealed, {@code SUPPORT_TICKET_NOT_CLAIMED} when the actor does not hold the
     *     claim, and {@code SUPPORT_TICKET_INVALID_TRANSITION} when the ticket is already terminal
     */
    SupportTicketStaffResponse respond(
            UUID actorId, UUID ticketId, RespondSupportTicketRequest request, boolean reject);

    /**
     * Hands a ticket up to an administrator.
     *
     * @param actorId the escalating staff member
     * @param ticketId the ticket
     * @param request why it needs an administrator
     * @return the ticket after escalation
     * @throws AppException {@code SUPPORT_TICKET_INVALID_TRANSITION} when the ticket is already
     *     terminal or already escalated
     */
    SupportTicketStaffResponse escalate(
            UUID actorId, UUID ticketId, EscalateSupportTicketRequest request);
}
