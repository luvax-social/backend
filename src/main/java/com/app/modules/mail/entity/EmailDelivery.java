package com.app.modules.mail.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.GenericGenerator;

import com.app.modules.mail.converter.EmailDeliveryStatusConverter;
import com.app.modules.mail.enums.EmailDeliveryStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One outbound mail send attempt.
 *
 * <p>Append-only except for the status transition and {@code sentAt}. The row outlives the
 * recipient account: {@code recipientUserId} is cleared by a hard delete while {@code
 * recipientEmail} keeps the destination, so the record that a notice was sent survives the account
 * it was sent to.
 */
@Entity
@Table(name = "email_deliveries")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailDelivery {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "recipient_user_id")
    private UUID recipientUserId;

    @Column(name = "recipient_email", nullable = false, length = 255)
    private String recipientEmail;

    @Column(name = "template_key", nullable = false, length = 100)
    private String templateKey;

    /**
     * The moderation decision this delivery announces; null for mail with no audit row behind it.
     */
    @Column(name = "admin_action_id")
    private UUID adminActionId;

    @Convert(converter = EmailDeliveryStatusConverter.class)
    @Column(name = "status", nullable = false, columnDefinition = "email_delivery_status")
    private EmailDeliveryStatus status;

    /** Returned by the provider on acceptance; null for every status other than sent. */
    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    @Column(name = "error_text", columnDefinition = "TEXT")
    private String errorText;

    /** Provider calls made; stays zero for a row that never reached a call. */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;
}
