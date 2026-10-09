package com.app.modules.social.policy;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One interaction submitted to {@link InteractionPolicyService#evaluate}.
 *
 * @param actorId the account acting
 * @param targetUserId the account the interaction is directed at: the post owner for a comment, the
 *     mentioned account, the recipient, or the other participant
 * @param type the kind of interaction
 * @param content the text being written, or null when there is none
 * @param secondaryTargetIds extra accounts a block must also be checked against, such as the parent
 *     comment's author; null is read as none
 * @param conversation the conversation a {@link InteractionType#MESSAGE_SEND} goes into, required
 *     for that type and ignored for the others
 */
public record InteractionRequest(
        UUID actorId,
        UUID targetUserId,
        InteractionType type,
        String content,
        List<UUID> secondaryTargetIds,
        ConversationContext conversation) {

    public InteractionRequest {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(targetUserId, "targetUserId");
        Objects.requireNonNull(type, "type");
        secondaryTargetIds =
                secondaryTargetIds == null ? List.of() : List.copyOf(secondaryTargetIds);
    }
}
