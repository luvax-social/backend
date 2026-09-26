package com.app.modules.support.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.generator.EventType;

import com.app.modules.support.converter.SupportCategoryConverter;
import com.app.modules.support.converter.SupportSourceConverter;
import com.app.modules.support.converter.SupportTicketStatusConverter;
import com.app.modules.support.enums.SupportCategory;
import com.app.modules.support.enums.SupportSource;
import com.app.modules.support.enums.SupportTicketStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One support request and the single staff response to it.
 *
 * <p>Mutability is split the way {@code Report} and {@code AdminAction} split theirs. Everything
 * the user wrote - the category, the subject, the body, the contact address, the source and the
 * audit row being appealed - is {@code updatable = false}, so no staff workflow step can rewrite
 * the request it is answering. Only the workflow columns move.
 *
 * <p>{@code internalNote} is staff-only. It is never mapped into a user-facing response and never
 * carried into a mail; {@code staffResponse} is the field that reaches the user.
 */
@Entity
@Table(name = "support_tickets")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupportTicket {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Null for a public submission that never resolved to an account. */
    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "contact_email", nullable = false, length = 255, updatable = false)
    private String contactEmail;

    @Convert(converter = SupportCategoryConverter.class)
    @Column(
            name = "category",
            nullable = false,
            columnDefinition = "support_category",
            updatable = false)
    private SupportCategory category;

    @Column(name = "subject", nullable = false, length = 200, updatable = false)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String body;

    @Convert(converter = SupportTicketStatusConverter.class)
    @Column(name = "status", nullable = false, columnDefinition = "support_ticket_status")
    private SupportTicketStatus status;

    @Convert(converter = SupportSourceConverter.class)
    @Column(
            name = "source",
            nullable = false,
            columnDefinition = "support_source",
            updatable = false)
    private SupportSource source;

    /**
     * The audit row this ticket appeals against, or null.
     *
     * <p>Also the input to the conflict-of-interest rule: the staff member who wrote that row may
     * not act on this ticket.
     */
    @Column(name = "admin_action_id", updatable = false)
    private UUID adminActionId;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "assigned_at")
    private OffsetDateTime assignedAt;

    /** The text sent to the user. Never contains {@link #internalNote}. */
    @Column(name = "staff_response", columnDefinition = "TEXT")
    private String staffResponse;

    /** Staff-only, and never leaves the system. */
    @Column(name = "internal_note", columnDefinition = "TEXT")
    private String internalNote;

    @Column(name = "responded_by")
    private UUID respondedBy;

    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    @Column(name = "escalated_by")
    private UUID escalatedBy;

    @Column(name = "escalated_at")
    private OffsetDateTime escalatedAt;

    @Column(name = "escalation_reason", columnDefinition = "TEXT")
    private String escalationReason;

    // Read back after the insert, because the column is a database default the entity never sets.
    // Without this the create responses - the appeal path especially, which has no follow-up read
    // to correct it - answer with createdAt null for a ticket that plainly has a creation time.
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
