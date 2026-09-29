package com.app.modules.admin.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * One {@code admin_actions} row as the ClickHouse replica stores it.
 *
 * <p>Text-typed where ClickHouse is: the action type is the lowercase enum label and the metadata
 * is the stored JSON text, so the consumer forwards them without a round trip through a Java type.
 * {@link #getRowVersion()} is what lets the replica keep the newest state of a row whatever order
 * its replication events arrive in.
 */
public interface AdminActionReplicaRow {

    UUID getId();

    UUID getAdminId();

    String getActionType();

    UUID getTargetUserId();

    String getTargetEntityType();

    UUID getTargetEntityId();

    UUID getReportId();

    String getReason();

    String getMetadata();

    Instant getCreatedAt();

    long getRowVersion();
}
