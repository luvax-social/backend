package com.app.modules.users.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.GenericGenerator;

import com.app.modules.users.converter.VerificationRevocationActorConverter;
import com.app.modules.users.enums.VerificationRevocationActor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One grant of a verified badge, and its withdrawal if it was withdrawn.
 *
 * <p>Revocation is soft: the row stays with {@code revokedAt} set, so a moderator reviewing a
 * resubmission can see what was granted before and why it was taken back. Reviewing blind is how
 * the same bad grant gets made twice.
 *
 * <p>{@code users.is_verified} and {@code users.verified_category} are derived from this table by
 * the {@code trg_user_verification_sync} trigger and are never written by application code. The
 * partial unique index {@code uq_user_verifications_active} is what holds one active grant per
 * account under concurrency.
 */
@Entity
@Table(name = "user_verifications")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserVerification {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "category_key", nullable = false, length = 50, updatable = false)
    private String categoryKey;

    /** The ticket that produced this grant, or null for a grant made without one. */
    @Column(name = "request_ticket_id", updatable = false)
    private UUID requestTicketId;

    @Column(name = "granted_by", updatable = false)
    private UUID grantedBy;

    @Column(name = "granted_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime grantedAt;

    @Column(name = "granted_action_id", updatable = false)
    private UUID grantedActionId;

    /** Set together with {@link #revocationActor}; null exactly when the grant is still active. */
    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    /** Null for a system revocation, by the same convention {@code admin_actions.admin_id} uses. */
    @Column(name = "revoked_by")
    private UUID revokedBy;

    @Column(name = "revocation_reason", columnDefinition = "TEXT")
    private String revocationReason;

    @Convert(converter = VerificationRevocationActorConverter.class)
    @Column(name = "revocation_actor", columnDefinition = "verification_revocation_actor")
    private VerificationRevocationActor revocationActor;

    @Column(name = "revoked_action_id")
    private UUID revokedActionId;
}
