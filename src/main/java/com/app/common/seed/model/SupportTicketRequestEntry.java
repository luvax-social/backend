package com.app.common.seed.model;

/** One pooled support-ticket opening request: what the user wrote, for one category. */
public record SupportTicketRequestEntry(String subject, String body) {}
