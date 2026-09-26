package com.app.modules.mail.enums;

/** Observable outcome of one send attempt, mirroring the {@code email_delivery_status} enum. */
public enum EmailDeliveryStatus {
    PENDING,
    SENT,
    FAILED,
    // Rejected by the per-recipient window before any provider call was made.
    THROTTLED,
    // The recipient was ineligible, such as a soft-deleted account.
    SKIPPED
}
