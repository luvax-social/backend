package com.app.modules.support.enums;

/**
 * Lifecycle of a support ticket, mirroring the {@code support_ticket_status} PostgreSQL enum.
 *
 * <p>{@code PENDING_CONFIRMATION} belongs to the public form alone: the row exists but is invisible
 * to staff until the submitter proves control of the address.
 *
 * <p>{@code ANSWERED} and {@code REJECTED} are terminal, and terminal is what the one-open-ticket
 * guard treats as closed.
 */
public enum SupportTicketStatus {
    PENDING_CONFIRMATION,
    OPEN,
    IN_PROGRESS,
    ESCALATED,
    ANSWERED,
    REJECTED;

    /**
     * Whether the ticket has reached a state it never leaves.
     *
     * @return true for {@code ANSWERED} and {@code REJECTED}
     */
    public boolean isTerminal() {
        return this == ANSWERED || this == REJECTED;
    }

    /**
     * Whether staff can see the ticket at all.
     *
     * @return false only for {@code PENDING_CONFIRMATION}
     */
    public boolean isVisibleToStaff() {
        return this != PENDING_CONFIRMATION;
    }
}
