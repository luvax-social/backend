package com.app.modules.admin.dto.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Outcome of restoring a post that moderation had removed.
 *
 * <p>Restore is the one write path that strips a banned hashtag instead of refusing, because a
 * moderator correcting its own removal must not be blocked by an unrelated administrator decision.
 * The post therefore comes back carrying fewer tags than its caption names, and the moderator has
 * to be told: the audit row records it, but a moderator reads a response, not an audit row.
 *
 * <p>Wraps the audit event rather than adding a field to it. The audit event describes what was
 * recorded; these names describe what else happened to the post, which is not the same thing and
 * does not apply to the fourteen other actions that share that shape.
 *
 * <p>The list is the post's present state, not the delta of one action. It names every banned tag
 * the caption still carries after this restore, so repeating a restore names the same tags again.
 * That is deliberate: a reviewer wants to know what the post is missing now, and a delta would be
 * empty on the second restore while the caption still named a tag the post does not carry.
 *
 * @param action the immutable audit event written for this restore
 * @param remainingBannedHashtags normalized names the caption still carries that are not associated
 *     with the post because an administrator has banned them; empty when the caption names no
 *     banned tag, which is the normal case and also every restore whose result is not published
 * @param strippedPlace name of the place the restore dropped because an administrator banned or
 *     deleted it while the post was removed; null when the post kept its place or had none
 */
@Schema(description = "Outcome of restoring a post removed by moderation")
public record AdminPostRestoreResponse(
        @Schema(description = "The immutable audit event written for this restore")
                AdminActionResponse action,
        @Schema(
                        description =
                                "Normalized hashtag names the caption still carries that are not"
                                        + " associated with the post because they are banned. This"
                                        + " is the post's state after the restore, not the set this"
                                        + " one call changed, so repeating a restore returns the"
                                        + " same names. Empty when the caption names no banned"
                                        + " tag.")
                List<String> remainingBannedHashtags,
        @Schema(
                        description =
                                "Name of the place the restore dropped because an administrator"
                                        + " banned or deleted it while the post was removed, so the"
                                        + " post came back without it. Null when the post kept its"
                                        + " place or had none.",
                        nullable = true)
                String strippedPlace) {}
