package com.app.modules.social.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A temporary restriction on who may interact with a user, mapped to {@code
 * interaction_limits(user_id)}.
 *
 * <p>At most one row exists per user. At least one of the two audience toggles is on, enforced by a
 * database check. The limit applies only while {@code expires_at} is in the future; an expired row
 * stays until the service replaces or removes it, so every reader must compare {@code expires_at}
 * instead of relying on row presence.
 */
@Entity
@Table(name = "interaction_limits")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InteractionLimit {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "limit_non_followers", nullable = false)
    private boolean limitNonFollowers;

    @Column(name = "limit_recent_followers", nullable = false)
    private boolean limitRecentFollowers;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    private OffsetDateTime createdAt;

    /** Maintained by trigger {@code trg_interaction_limits_updated_at}. */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    private OffsetDateTime updatedAt;
}
