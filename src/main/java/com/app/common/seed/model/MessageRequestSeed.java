package com.app.common.seed.model;

/**
 * One message request, as authored in {@code safety/interaction_control.json}: a stranger's first
 * message to an account that allows everyone to write to it.
 *
 * <p>{@code status} is {@code pending}, {@code hidden} (pending, filed in the recipient's hidden
 * requests folder), {@code declined} (the recipient deleted it) or {@code accepted} (the recipient
 * replied with {@code replyText}). The sender may send exactly one text message while a request is
 * open, so a request never carries more than that message, and a declined one reads to its sender
 * exactly like a pending one.
 */
public record MessageRequestSeed(
        String id,
        String requester,
        String recipient,
        String status,
        String text,
        String replyText,
        int daysAgo) {}
