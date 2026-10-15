package com.app.common.seed.model;

/**
 * One emoji reaction to a message of {@code conversations.json}, named by its conversation and the
 * message's position in the conversation's message list. An {@code undone} reaction writes no row
 * and only produces the signals of a reaction that was set and then removed.
 */
public record ReactionSeed(
        String conversationId, int messageIndex, String reactor, String emoji, boolean undone) {}
