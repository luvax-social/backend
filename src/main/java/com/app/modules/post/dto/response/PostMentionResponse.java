package com.app.modules.post.dto.response;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A stored mention in a caption or comment.
 *
 * <p>A client replaces {@code @handle} in the text with a link to {@code username} only for handles
 * in this list, and shows every other {@code @name} as plain text, so a renamed or deleted account
 * can never point old text at someone else.
 */
@Schema(description = "A mention stored by user id")
public record PostMentionResponse(
        @Schema(description = "The text as typed, without the @.") String handle,
        @Schema(description = "The mentioned account.") UUID userId,
        @Schema(description = "That account's current username.") String username) {}
