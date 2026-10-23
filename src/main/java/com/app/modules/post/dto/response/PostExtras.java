package com.app.modules.post.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

import com.app.modules.post.enums.CollaborationStatus;
import com.app.modules.post.place.dto.response.PlaceSummaryResponse;

/**
 * The parts of a post response that do not come straight from the {@code posts} row, batch-loaded
 * by the response assembler and passed to the mapper as one value.
 *
 * @param place the post's place, or null when it has none or the place is no longer available
 * @param mentions mentions stored for the caption, as the viewer may see them
 * @param collaborators co-authors and invitations the viewer may see
 * @param viewerCollaboration the viewer's own collaboration status on the post, or null
 * @param scheduledAt the scheduled time, present only for the post owner
 */
public record PostExtras(
        PlaceSummaryResponse place,
        List<PostMentionResponse> mentions,
        List<PostCollaboratorResponse> collaborators,
        CollaborationStatus viewerCollaboration,
        OffsetDateTime scheduledAt) {}
