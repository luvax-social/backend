package com.app.common.seed.model;

/**
 * One pooled verification claim. {@code categoryKey} must be one of the eight {@code
 * verification_categories} rows (V106): music, visual_arts, writing, science, screen, sport,
 * business, gaming. At least three of the seven evidence fields must be non-null/non-blank,
 * matching {@code verification_requests_min_evidence}.
 */
public record VerificationRequestPoolEntry(
        String claimedName,
        String categoryKey,
        String evidenceWebsite,
        String evidenceOtherProfile,
        String evidenceEmailDomain,
        String evidencePublishedWork,
        String evidencePress,
        String evidenceOfficialListing,
        String evidenceNote) {}
