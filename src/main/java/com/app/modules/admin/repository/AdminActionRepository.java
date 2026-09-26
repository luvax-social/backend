package com.app.modules.admin.repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.app.modules.admin.entity.AdminAction;

@org.springframework.stereotype.Repository
public interface AdminActionRepository
        extends Repository<AdminAction, UUID>, AdminActionRepositoryCustom {

    Optional<AdminAction> findById(UUID id);

    /**
     * The most recent decision against one account that is appealable and not yet appealed.
     *
     * <p>What the lost-link recovery path re-mints a link for. It picks one row rather than letting
     * the requester choose, because the requester has by definition lost the message that named a
     * decision and has no identifier to offer.
     *
     * <p>Bounded and indexed rather than a scan. {@code idx_admin_actions_target_created} (V82)
     * serves both the {@code target_user_id} filter and the ordering, so the plan reads index
     * entries in order and stops at the first row that survives the two remaining predicates;
     * {@code idx_support_tickets_admin_action} (V100) serves the anti-join. Both are partial on
     * {@code IS NOT NULL} and both predicates here satisfy that.
     *
     * <p>The action type is compared as text rather than bound as an enum array. A bare parameter
     * against a PostgreSQL enum column cannot be typed by the driver, which is the same failure
     * {@code SupportTicketRepository.findStaffQueueByStatus} documents. The cast costs nothing
     * here: {@code target_user_id} is the selective column and the index already applies it.
     *
     * @param targetUserId the account the decisions were taken against
     * @param appealableTypes the appealable action type names, lowercased to match the enum labels
     * @return the row to re-mint a link for, or empty when there is none
     */
    @Query(
            value =
                    "SELECT a.* FROM admin_actions a"
                            + " WHERE a.target_user_id = :targetUserId"
                            + " AND CAST(a.action_type AS text) IN (:appealableTypes)"
                            + " AND NOT EXISTS ("
                            + "   SELECT 1 FROM support_tickets t WHERE t.admin_action_id = a.id)"
                            + " ORDER BY a.created_at DESC, a.id DESC"
                            + " LIMIT 1",
            nativeQuery = true)
    Optional<AdminAction> findMostRecentAppealable(
            @Param("targetUserId") UUID targetUserId,
            @Param("appealableTypes") Collection<String> appealableTypes);
}
