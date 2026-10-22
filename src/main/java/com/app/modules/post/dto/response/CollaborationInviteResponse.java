package com.app.modules.post.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.app.common.response.PreviewMediaResponse;
import com.app.common.response.UserSummaryResponse;

import io.swagger.v3.oas.annotations.media.Schema;

/** An invitation to co-author a published post that the viewer has not answered. */
@Schema(description = "A pending invitation to co-author a post")
public record CollaborationInviteResponse(
        @Schema(description = "The post the viewer is invited to co-author.") UUID postId,
        @Schema(description = "The post owner who invited the viewer.", nullable = true)
                UserSummaryResponse invitedBy,
        @Schema(description = "First media item of the post, for a thumbnail.", nullable = true)
                PreviewMediaResponse media,
        @Schema(description = "When the invitation was sent.") OffsetDateTime invitedAt) {}
