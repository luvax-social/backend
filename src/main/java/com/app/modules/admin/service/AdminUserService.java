package com.app.modules.admin.service;

import java.util.UUID;

import com.app.common.exception.AppException;
import com.app.common.response.CursorPageResponse;
import com.app.modules.admin.dto.request.AdminActionRequest;
import com.app.modules.admin.dto.request.AdminRoleChangeRequest;
import com.app.modules.admin.dto.response.AdminActionResponse;
import com.app.modules.admin.dto.response.AdminUserDetailResponse;
import com.app.modules.admin.dto.response.AdminUserListItemResponse;
import com.app.modules.admin.dto.response.AdminUserLookupResponse;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;

/**
 * Administrative reads over accounts, plus the two account operations that are neither a status
 * change nor content moderation.
 *
 * <p>Every read here spans every account status and includes soft-deleted rows, which inverts the
 * project-wide soft-delete rule. That is the point of an administrative view, and it is why this
 * service is reachable only from the ADMIN-only surface.
 */
public interface AdminUserService {

    /**
     * Lists accounts newest first, optionally narrowed by status and role.
     *
     * @param status status to match, or null for every status
     * @param role role to match, or null for every role
     * @param cursor opaque keyset cursor from a previous page, or null for the first page
     * @param limit requested page size
     * @return one cursor page of accounts ordered by creation time descending
     * @throws AppException when the cursor is malformed
     */
    CursorPageResponse<AdminUserListItemResponse> listUsers(
            UserStatus status, UserRole role, String cursor, int limit);

    /**
     * Searches accounts by exact id, or by case-insensitive substring of username or email.
     *
     * @param query search text; at least two characters after trimming
     * @param cursor opaque keyset cursor from a previous page, or null for the first page
     * @param limit requested page size
     * @return one cursor page of matching accounts ordered by creation time descending
     * @throws AppException when the query is too short or the cursor is malformed
     */
    CursorPageResponse<AdminUserListItemResponse> searchUsers(
            String query, String cursor, int limit);

    /**
     * Returns the full administrative view of one account, including its live sessions and the most
     * recent reports filed against it.
     *
     * <p>Also reports what the requesting administrator may do to the account, evaluated against
     * the same component the write endpoints enforce, so a client can render the controls it can
     * actually use rather than discovering the answer from a rejection.
     *
     * @param actorId the administrator making the request, whose permitted operations are reported
     * @param userId account to inspect
     * @return the account's administrative detail
     * @throws AppException {@code USER_NOT_FOUND} when no row holds that id, {@code FORBIDDEN} when
     *     the actor no longer resolves to a live account
     */
    AdminUserDetailResponse getUserDetail(UUID actorId, UUID userId);

    /**
     * Revokes every live session of an account and records the action.
     *
     * <p>Ends refresh capability at once. An access token the user already holds keeps working
     * until it expires, because the blacklist is keyed on the token's own {@code jti} and no
     * administrator holds it.
     *
     * @param actorId the acting administrator
     * @param userId account whose sessions to end
     * @param request audit reason
     * @return the audit row, whose metadata records how many sessions were revoked
     * @throws AppException {@code USER_NOT_FOUND} when no live account holds that id
     */
    AdminActionResponse forceLogout(UUID actorId, UUID userId, AdminActionRequest request);

    /**
     * Resolves several account identifiers to display information in one call.
     *
     * <p>Queues, audit rows, violation rows and activity rows all carry bare identifiers, and
     * resolving them one at a time costs one request per distinct account on a page.
     *
     * <p>Returns one entry per requested identifier, in the order requested and with duplicates
     * collapsed, so the caller can build a complete map. An unknown or deleted identifier is
     * returned with {@code found} false rather than omitted.
     *
     * @param userIds identifiers to resolve
     * @return one entry per distinct identifier, in request order
     * @throws com.app.common.exception.AppException {@code BAD_REQUEST} when more identifiers are
     *     supplied than the endpoint accepts
     */
    java.util.List<AdminUserLookupResponse> resolveUserSummaries(java.util.List<UUID> userIds);

    AdminActionResponse revokeSession(
            UUID actorId, UUID userId, UUID sessionId, AdminActionRequest request);

    /**
     * Changes an account's role and revokes its sessions in the same transaction.
     *
     * <p>The permitted transitions are defined by {@link AdminAuthorizationService}. The actor's
     * role is resolved from the database inside the transaction, never from a token claim, because
     * a claim minted before a demotion outlives the demotion.
     *
     * @param actorId the acting administrator
     * @param userId account whose role to change
     * @param request the requested role and the audit reason
     * @return the audit row for the role change
     * @throws AppException {@code FORBIDDEN} when the actor is not an administrator, {@code
     *     USER_NOT_FOUND} when no live account holds that id, {@code ADMIN_SELF_ACTION_NOT_ALLOWED}
     *     when the actor targets itself, {@code ADMIN_TARGET_PROTECTED} when the target is an
     *     administrator, and {@code ADMIN_ROLE_TRANSITION_NOT_ALLOWED} for a skip-level promotion
     *     or a no-op
     */
    AdminActionResponse changeRole(UUID actorId, UUID userId, AdminRoleChangeRequest request);
}
