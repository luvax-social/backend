package com.app.modules.admin.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;

/**
 * Unfiltered reads over {@code users} for the administrative surface.
 *
 * <p>Every method here deliberately inverts the project-wide {@code deleted_at IS NULL} rule stated
 * in {@code GLOBAL_RULES.md} section 3, because an administrator investigating an account must be
 * able to see one that has been removed. The inversion is contained to this interface for exactly
 * that reason: no other module injects it, so no ordinary read path can pick up an unfiltered query
 * by accident.
 */
public interface AdminUserRepositoryCustom {

    /**
     * Lists accounts newest first, optionally narrowed by status and role.
     *
     * <p>Spans every status and includes soft-deleted rows. Keyset ordering is {@code (created_at
     * DESC, id DESC)}; pass both cursor components or neither.
     *
     * @param status status to match, or null for every status
     * @param role role to match, or null for every role
     * @param cursorCreatedAt exclusive upper bound on {@code created_at}, or null for the first
     *     page
     * @param cursorId tiebreaker paired with {@code cursorCreatedAt}
     * @param limit maximum rows to return; callers over-fetch by one to detect a further page
     * @return matching accounts in keyset order
     */
    List<User> findPage(
            UserStatus status,
            UserRole role,
            OffsetDateTime cursorCreatedAt,
            UUID cursorId,
            int limit);

    /**
     * Searches accounts by exact id, or by case-insensitive substring of username or email.
     *
     * <p>Spans every status and includes soft-deleted rows, unlike the public user search. The id
     * disjunct is present only when the query text parses as a UUID.
     *
     * @param query trimmed search text, already length-checked by the caller
     * @param exactId the query parsed as a UUID, or null when it is not one
     * @param cursorCreatedAt exclusive upper bound on {@code created_at}, or null for the first
     *     page
     * @param cursorId tiebreaker paired with {@code cursorCreatedAt}
     * @param limit maximum rows to return; callers over-fetch by one to detect a further page
     * @return matching accounts in keyset order
     */
    List<User> search(
            String query, UUID exactId, OffsetDateTime cursorCreatedAt, UUID cursorId, int limit);

    /**
     * Loads one account by id regardless of status or soft-delete state.
     *
     * @param userId account to load
     * @return the account, live or soft-deleted, or empty when no row holds that id
     */
    Optional<User> findByIdIncludingDeleted(UUID userId);

    /**
     * Lists the ids of accounts whose fixed-term suspension has lapsed.
     *
     * <p>Bounded by {@code limit} so one job pass cannot hold a connection over an unbounded set.
     * Indefinite suspensions have a null {@code suspended_until} and never appear here.
     *
     * @param limit maximum ids to return
     * @param now the cutoff, from the same clock that wrote {@code suspended_until}
     * @return ids of suspended accounts whose {@code suspended_until} is in the past
     */
    List<UUID> findExpiredSuspensionIds(int limit, java.time.OffsetDateTime now);
}
