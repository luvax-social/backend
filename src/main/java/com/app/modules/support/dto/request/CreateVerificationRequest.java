package com.app.modules.support.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A request for a verified badge.
 *
 * <p>Every evidence field is optional individually and at least three must be filled together. That
 * rule is not expressible as a bean-validation annotation on any one field, so it is enforced in
 * the service with its own error code and again by a CHECK constraint on the table. The client
 * enforcing it too is a convenience, never the contract.
 *
 * <p>There is no file upload field, deliberately. No identity documents of any kind are collected.
 */
@Schema(description = "A request for a verified badge on the calling account")
public record CreateVerificationRequest(
        @Schema(description = "Verification category key", example = "music")
                @NotBlank
                @Size(max = 50)
                String categoryKey,
        @Schema(description = "The display name being claimed", example = "Nguyen Quang Minh")
                @NotBlank
                @Size(max = 100)
                String claimedName,
        @Schema(description = "Official website", nullable = true) @Size(max = 2000)
                String evidenceWebsite,
        @Schema(description = "A verified profile on another platform", nullable = true)
                @Size(max = 2000)
                String evidenceOtherProfile,
        @Schema(description = "An organisational email domain", nullable = true) @Size(max = 2000)
                String evidenceEmailDomain,
        @Schema(
                        description = "Published work, portfolio, discography or publication list",
                        nullable = true)
                @Size(max = 2000)
                String evidencePublishedWork,
        @Schema(description = "Press coverage", nullable = true) @Size(max = 2000)
                String evidencePress,
        @Schema(description = "An official organisational listing", nullable = true)
                @Size(max = 2000)
                String evidenceOfficialListing,
        @Schema(description = "A free-text note to the moderator", nullable = true)
                @Size(max = 2000)
                String evidenceNote) {}
