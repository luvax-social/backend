package com.app.modules.message.enums;

/**
 * Where a conversation stands in the message request flow, mirroring the Postgres {@code
 * conversation_request_status} enum.
 */
public enum ConversationRequestStatus {
    ACCEPTED,
    PENDING,
    DECLINED
}
