package com.app.common.seed.model;

/**
 * One comment the post owner's Hidden Words or interaction limit filtered, as authored in {@code
 * safety/interaction_control.json}.
 *
 * <p>{@code postIndex} selects the owner's published post by its order in {@code posts.json}.
 * {@code reason} is {@code hidden_words}, {@code limited_interaction} or {@code hidden_parent}; the
 * last is a reply to the hidden comment named by {@code replyTo}. An {@code unhidden} entry is a
 * comment the owner released, so it is stored approved and only its hide and unhide signals record
 * what happened.
 */
public record HiddenCommentSeed(
        String id,
        String postOwner,
        int postIndex,
        String commenter,
        String text,
        String reason,
        String replyTo,
        boolean unhidden) {}
