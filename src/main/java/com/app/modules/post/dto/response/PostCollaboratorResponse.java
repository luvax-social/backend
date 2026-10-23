package com.app.modules.post.dto.response;

import com.app.common.response.UserSummaryResponse;
import com.app.modules.post.enums.CollaborationStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** A co-author of a post, or an invitation the viewer is allowed to see. */
@Schema(description = "A co-author or pending invitation of a post")
public record PostCollaboratorResponse(
        @Schema(description = "The invited or co-authoring account.") UserSummaryResponse user,
        @Schema(
                        description =
                                "accepted for a co-author shown to everyone who can see the post;"
                                        + " invited only to the post owner and the invitee.")
                CollaborationStatus status) {}
