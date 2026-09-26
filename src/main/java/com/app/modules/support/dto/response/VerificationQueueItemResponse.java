package com.app.modules.support.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.app.common.response.UserSummaryResponse;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One verification request as a moderator sees it.
 *
 * <p>Carries {@code previousGrants} because reviewing a resubmission blind is how the same bad
 * grant gets made twice. The list distinguishes a badge a moderator withdrew from one an account
 * status change swept away, which are different facts about the account.
 */
@Schema(description = "A verification request in the moderator queue")
public record VerificationQueueItemResponse(
        @Schema(description = "The support ticket carrying this request") UUID ticketId,
        @Schema(description = "The requesting account") UserSummaryResponse requester,
        @Schema(description = "Ticket status", example = "open") String status,
        @Schema(description = "Staff member holding the claim", nullable = true) UUID assignedTo,
        @Schema(description = "Requested category key", example = "music") String categoryKey,
        @Schema(description = "Display name being claimed") String claimedName,
        @Schema(description = "How many evidence fields were filled") short evidenceFieldCount,
        @Schema(description = "Official website", nullable = true) String evidenceWebsite,
        @Schema(description = "Profile on another platform", nullable = true)
                String evidenceOtherProfile,
        @Schema(description = "Organisational email domain", nullable = true)
                String evidenceEmailDomain,
        @Schema(description = "Published work or portfolio", nullable = true)
                String evidencePublishedWork,
        @Schema(description = "Press coverage", nullable = true) String evidencePress,
        @Schema(description = "Official listing", nullable = true) String evidenceOfficialListing,
        @Schema(description = "Note to the moderator", nullable = true) String evidenceNote,
        @Schema(description = "When the request was submitted") OffsetDateTime createdAt,
        @Schema(description = "Every badge this account has previously held")
                List<VerificationGrantResponse> previousGrants) {}
