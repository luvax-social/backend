package com.app.common.seed.model;

/**
 * A message of {@code conversations.json} that quotes an earlier message of the same conversation,
 * both named by their position in the conversation's message list.
 */
public record ReplyParentSeed(String conversationId, int messageIndex, int replyToIndex) {}
