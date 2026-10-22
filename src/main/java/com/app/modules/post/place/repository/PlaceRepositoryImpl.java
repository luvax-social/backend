package com.app.modules.post.place.repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import com.app.modules.post.place.entity.Place;
import com.app.modules.post.place.enums.PlaceStatus;

/**
 * Native-SQL implementation of the administrative place reads.
 *
 * <p>Predicates are appended only when the matching filter is present, so the planner can use
 * {@code idx_places_status_created}; the keyset predicate is a row-value comparison for the same
 * reason. Both follow the administrative hashtag reads.
 */
public class PlaceRepositoryImpl implements PlaceRepositoryCustom {

    private static final String SELECT_PLACE = "SELECT p.* FROM places p";
    private static final String KEYSET_ORDER =
            " ORDER BY p.created_at DESC, p.id DESC LIMIT :limit";
    private static final String KEYSET_PREDICATE =
            "(p.created_at, p.id) < (CAST(:cursorCreatedAt AS timestamptz), CAST(:cursorId AS uuid))";
    private static final String STATUS_PREDICATE = "p.status = CAST(:status AS place_status)";

    private final EntityManager entityManager;

    public PlaceRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    // SQL text only joins constant predicates; every caller-controlled value is bound separately.
    @SuppressWarnings("java:S2077")
    public List<Place> findAdminPage(
            PlaceStatus status, OffsetDateTime cursorCreatedAt, UUID cursorId, int limit) {
        List<String> predicates = new ArrayList<>();
        if (status != null) {
            predicates.add(STATUS_PREDICATE);
        }
        boolean paged = cursorCreatedAt != null && cursorId != null;
        if (paged) {
            predicates.add(KEYSET_PREDICATE);
        }
        Query query =
                entityManager.createNativeQuery(
                        SELECT_PLACE + where(predicates) + KEYSET_ORDER, Place.class);
        if (status != null) {
            query.setParameter("status", status.toJson());
        }
        bindKeyset(query, paged, cursorCreatedAt, cursorId);
        query.setParameter("limit", limit);
        return typedResult(query);
    }

    @Override
    // SQL text only joins constant predicates; every caller-controlled value is bound separately.
    @SuppressWarnings("java:S2077")
    public List<Place> searchAdmin(
            String queryText,
            PlaceStatus status,
            OffsetDateTime cursorCreatedAt,
            UUID cursorId,
            int limit) {
        List<String> predicates = new ArrayList<>();
        predicates.add("(p.name ILIKE :pattern OR p.normalized_name ILIKE :pattern)");
        if (status != null) {
            predicates.add(STATUS_PREDICATE);
        }
        boolean paged = cursorCreatedAt != null && cursorId != null;
        if (paged) {
            predicates.add(KEYSET_PREDICATE);
        }
        Query query =
                entityManager.createNativeQuery(
                        SELECT_PLACE + where(predicates) + KEYSET_ORDER, Place.class);
        query.setParameter("pattern", "%" + escapeLikeWildcards(queryText) + "%");
        if (status != null) {
            query.setParameter("status", status.toJson());
        }
        bindKeyset(query, paged, cursorCreatedAt, cursorId);
        query.setParameter("limit", limit);
        return typedResult(query);
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
    private static List<Place> typedResult(Query query) {
        return query.getResultList();
    }
}
