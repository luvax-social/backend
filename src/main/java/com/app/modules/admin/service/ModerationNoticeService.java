package com.app.modules.admin.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.app.modules.admin.dto.response.ModerationNoticeResponse;

/** Batched moderation notices for the notification feed. */
public interface ModerationNoticeService {

    /**
     * Resolves each moderation decision the recipient was notified of: what was decided, why, what
     * it acted on, and whether the recipient can appeal it.
     *
     * <p>The affected text is included only when the recipient is the account the decision was
     * against, and never media. A decision is appealable when its type is one of the punitive
     * actions an appeal can contest and it was against the recipient.
     *
     * @param recipientId the account the notices are for
     * @param adminActionIds the audit rows; duplicates are collapsed
     * @return one notice per audit row found; an id naming no audit row is absent
     */
    Map<UUID, ModerationNoticeResponse> loadNotices(
            UUID recipientId, Collection<UUID> adminActionIds);
}
