package com.app.modules.admin.repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;

/**
 * Native-SQL implementation of the administrative user reads.
 *
 * <p>Predicates are appended only when the corresponding filter is present rather than being
 * written as {@code :param IS NULL OR column = :param}. PostgreSQL cannot turn that disjunction
 * into an index condition, so the always-present form costs a sequential scan on every filtered
 * call.
 *
 * <p>The keyset predicate uses a row-value comparison, {@code (created_at, id) < (?, ?)}, which the
 * planner resolves as a single seek on a composite index. The expanded {@code a < ? OR (a = ? AND b
 * < ?)} form does not.
 */
public class AdminUserRepositoryImpl implements AdminUserRepositoryCustom {

    // No deleted_at predicate anywhere in this class. That is the deliberate inversion of
    // GLOBAL_RULES.md section 3 documented on AdminUserRepositoryCustom.
    private static final String SELECT_USER = "SELECT u.* FROM users u";
    private static final String KEYSET_ORDER =
            " ORDER BY u.created_at DESC, u.id DESC LIMIT :limit";
    private static final String KEYSET_PREDICATE =
            "(u.created_at, u.id) < (CAST(:cursorCreatedAt AS timestamptz), CAST(:cursorId AS uuid))";

    private final EntityManager entityManager;

    public AdminUserRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    // SQL text only joins constant predicates; every caller-controlled value is bound separately.
    @SuppressWarnings("java:S2077")
    public List<User> findPage(
            UserStatus status,
            UserRole role,
            OffsetDateTime cursorCreatedAt,
            UUID cursorId,
            int limit) {
        List<String> predicates = new ArrayList<>();
        if (status != null) {
            predicates.add("u.status = CAST(:status AS user_status)");
        }
        if (role != null) {
            predicates.add("u.role = CAST(:role AS user_role)");
        }
        boolean paged = cursorCreatedAt != null && cursorId != null;
        if (paged) {
            predicates.add(KEYSET_PREDICATE);
        }

        Query query =
                entityManager.createNativeQuery(
                        SELECT_USER + where(predicates) + KEYSET_ORDER, User.class);
        if (status != null) {
            query.setParameter("status", status.toJson());
        }
        if (role != null) {
            query.setParameter("role", role.toJson());
        }
        bindKeyset(query, paged, cursorCreatedAt, cursorId);
        query.setParameter("limit", limit);
        return typedResult(query);
    }

    @Override
    // SQL text only joins constant predicates; every caller-controlled value is bound separately.
    @SuppressWarnings("java:S2077")
    public List<User> search(
            String query, UUID exactId, OffsetDateTime cursorCreatedAt, UUID cursorId, int limit) {
        StringBuilder match =
                new StringBuilder("(u.username ILIKE :pattern OR u.email ILIKE :pattern");
        if (exactId != null) {
            match.append(" OR u.id = CAST(:exactId AS uuid)");
        }
        match.append(')');

        List<String> predicates = new ArrayList<>();
        predicates.add(match.toString());
        boolean paged = cursorCreatedAt != null && cursorId != null;
        if (paged) {
            predicates.add(KEYSET_PREDICATE);
        }

        Query nativeQuery =
                entityManager.createNativeQuery(
                        SELECT_USER + where(predicates) + KEYSET_ORDER, User.class);
        nativeQuery.setParameter("pattern", "%" + escapeLikeWildcards(query) + "%");
        if (exactId != null) {
            nativeQuery.setParameter("exactId", exactId);
        }
        bindKeyset(nativeQuery, paged, cursorCreatedAt, cursorId);
        nativeQuery.setParameter("limit", limit);
        return typedResult(nativeQuery);
    }

    @Override
    public Optional<User> findByIdIncludingDeleted(UUID userId) {
        Query query =
                entityManager.createNativeQuery(SELECT_USER + " WHERE u.id = :userId", User.class);
        query.setParameter("userId", userId);
        return typedResult(query).stream().findFirst();
    }

    @Override
    public List<UUID> findExpiredSuspensionIds(int limit, java.time.OffsetDateTime now) {
        Query query =
                entityManager.createNativeQuery(
                        "SELECT u.id FROM users u"
                                + " WHERE u.status = 'suspended'"
                                + " AND u.suspended_until IS NOT NULL"
                                + " AND u.suspended_until <= :now"
                                + " ORDER BY u.suspended_until"
                                + " LIMIT :limit");
        query.setParameter("limit", limit);
        query.setParameter("now", now);
        @SuppressWarnings("unchecked")
        List<UUID> ids = query.getResultList();
        return ids;
    }

    private static String where(List<String> predicates) {
        return predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
    }

    private static void bindKeyset(
            Query query, boolean paged, OffsetDateTime cursorCreatedAt, UUID cursorId) {
        if (paged) {
            query.setParameter("cursorCreatedAt", cursorCreatedAt);
            query.setParameter("cursorId", cursorId);
        }
    }

    // An unescaped '%' or '_' in an administrator's search text would silently widen the match to
    // every row rather than looking for the literal character the administrator typed.
    private static String escapeLikeWildcards(String query) {
        return query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @SuppressWarnings("unchecked")
    private static List<User> typedResult(Query query) {
        return query.getResultList();
    }
}
