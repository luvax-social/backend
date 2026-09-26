package com.app.modules.support.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The structured half of a {@code verification_request} support ticket.
 *
 * <p>Keyed one-to-one on the ticket rather than carrying its own identifier, because it has no life
 * of its own: the ticket owns the workflow, and this owns what was asked for. Every column is
 * immutable after insert, matching how {@code SupportTicket} treats everything the user wrote.
 *
 * <p>There is deliberately no file upload and no column for one. No identity documents, no
 * passport, no scan of anything. That is a design constraint rather than an omission.
 */
@Entity
@Table(name = "verification_requests")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerificationRequest {

    @Id
    @Column(name = "ticket_id", nullable = false, updatable = false)
    private UUID ticketId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "category_key", nullable = false, length = 50, updatable = false)
    private String categoryKey;

    @Column(name = "claimed_name", nullable = false, length = 100, updatable = false)
    private String claimedName;

    @Column(name = "evidence_website", columnDefinition = "TEXT", updatable = false)
    private String evidenceWebsite;

    @Column(name = "evidence_other_profile", columnDefinition = "TEXT", updatable = false)
    private String evidenceOtherProfile;

    @Column(name = "evidence_email_domain", columnDefinition = "TEXT", updatable = false)
    private String evidenceEmailDomain;

    @Column(name = "evidence_published_work", columnDefinition = "TEXT", updatable = false)
    private String evidencePublishedWork;

    @Column(name = "evidence_press", columnDefinition = "TEXT", updatable = false)
    private String evidencePress;

    @Column(name = "evidence_official_listing", columnDefinition = "TEXT", updatable = false)
    private String evidenceOfficialListing;

    @Column(name = "evidence_note", columnDefinition = "TEXT", updatable = false)
    private String evidenceNote;

    /**
     * How many of the seven evidence fields were filled.
     *
     * <p>Denormalised so the database can enforce the three-field minimum with a CHECK constraint.
     * Computed by the service from the seven columns it writes in the same statement, so the two
     * cannot disagree.
     */
    @Column(name = "evidence_field_count", nullable = false, updatable = false)
    private short evidenceFieldCount;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
