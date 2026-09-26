package com.app.modules.admin.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * What an account is shown of a moderation decision about it in its notification feed.
 *
 * <p>Kind, date and a text snippet of the affected content, never its media: a removed image is not
 * redistributed through a notice, and its storage object may already be gone. The snippet is shown
 * only to the author of the content; a reporter told the outcome of their report sees the decision
 * but never the reported text.
 *
 * @param actionType the lowercase {@code admin_action_type} label
 * @param reason the reason the moderator recorded; may be null
 * @param affectedKind post, comment, story, message or account; null when the decision named no
 *     content
 * @param affectedSnippet the affected text, shortened; null unless the recipient wrote it and it
 *     had text
 * @param affectedAt when the affected content was created; null under the same condition
 * @param appealable whether the recipient can appeal this decision in product
 */
public record ModerationNoticeResponse(
        UUID adminActionId,
        String actionType,
        String reason,
        String affectedKind,
        String affectedSnippet,
        OffsetDateTime affectedAt,
        boolean appealable) {}
