package com.app.modules.support.service;

import java.util.UUID;

import com.app.common.exception.AppException;
import com.app.modules.support.enums.SupportCategory;

/**
 * Single-use tokens for the two support entry paths that have no session behind them.
 *
 * <p>Follows {@code TokenServiceImpl} exactly: a {@code SecureRandom} 32-byte Base64-URL token,
 * only its SHA-256 hex stored in Redis, a forward key and a reverse key, issue made atomic by Lua
 * so a new token invalidates the previous one, and consumption made atomic by a Lua GET-then-DEL so
 * a token cannot be redeemed twice.
 *
 * <p>Consuming an appeal token does <strong>not</strong> mint a session. It authorises exactly one
 * action: creating one ticket against one audit row. {@code AuthServiceImpl.verifyEmail} is the
 * counter-example in this codebase - it consumes a token and calls {@code issueSession} with no
 * account-state check, which mints tokens for a banned account that are then refused on first use -
 * and this path deliberately does not repeat that shape.
 */
public interface SupportTokenService {

    /**
     * What an appeal token authorises: one ticket, against one audit row, for one account.
     *
     * @param userId the account the moderation action was taken against
     * @param adminActionId the audit row being appealed
     * @param category the appeal category the action implies, preselected for the submitter
     */
    record AppealGrant(UUID userId, UUID adminActionId, SupportCategory category) {}

    /**
     * Issues the appeal token embedded in a moderation notice.
     *
     * @param userId the account the notice went to
     * @param adminActionId the audit row the notice describes
     * @param category the appeal category that action implies
     * @return the raw token to place in the link; only its hash is stored
     */
    String createAppealToken(UUID userId, UUID adminActionId, SupportCategory category);

    /**
     * Redeems an appeal token, atomically and once.
     *
     * @param rawToken the token from the link
     * @return what the token authorises
     * @throws AppException {@code SUPPORT_TOKEN_INVALID} when the token is unknown, expired or
     *     already redeemed
     */
    AppealGrant consumeAppealToken(String rawToken);

    /**
     * Reads an appeal token without redeeming it.
     *
     * <p>Exists so every check that can refuse a request runs while the token is still spendable. A
     * token is the only credential a banned account holds, so a refusal the submitter can act on
     * must not also remove their ability to act on it. Callers validate against this, then call
     * {@link #consumeAppealToken} last, as the final step before the write commits; consumption
     * stays atomic, so a double submit still redeems exactly once.
     *
     * @param rawToken the token from the link
     * @return what the token authorises
     * @throws AppException {@code SUPPORT_TOKEN_INVALID} when the token is unknown, expired or
     *     already redeemed
     */
    AppealGrant peekAppealToken(String rawToken);

    /**
     * Destroys any unspent appeal token for one audit row.
     *
     * <p>Called when the in-product appeal path opens an appeal against that row. The two entry
     * paths lead to the same ticket and one decision gets one appeal, so leaving a signed link live
     * after the in-product appeal is filed would hand the holder a credential that can only ever be
     * refused - and, worse, one that survives a rejection.
     *
     * <p>Idempotent and silent. The common case is that no token was ever minted for the row, which
     * is not an error.
     *
     * @param adminActionId the audit row whose link is being withdrawn
     */
    void revokeAppealToken(UUID adminActionId);

    /**
     * Issues the token that confirms a public submitter controls the address they gave.
     *
     * @param ticketId the ticket held in {@code PENDING_CONFIRMATION}
     * @return the raw token to place in the confirmation link
     */
    String createConfirmationToken(UUID ticketId);

    /**
     * Redeems a confirmation token, atomically and once.
     *
     * @param rawToken the token from the link
     * @return the ticket to move into the staff queue
     * @throws AppException {@code SUPPORT_TOKEN_INVALID} when the token is unknown, expired or
     *     already redeemed
     */
    UUID consumeConfirmationToken(String rawToken);

    /**
     * Reads a confirmation token without redeeming it.
     *
     * <p>Same reason as {@link #peekAppealToken}: the confirmation link is the only thing that
     * moves a public submission out of {@code PENDING_CONFIRMATION}, so a refusal that spends it
     * strands the ticket where staff cannot see it.
     *
     * @param rawToken the token from the link
     * @return the ticket the token confirms
     * @throws AppException {@code SUPPORT_TOKEN_INVALID} when the token is unknown, expired or
     *     already redeemed
     */
    UUID peekConfirmationToken(String rawToken);
}
