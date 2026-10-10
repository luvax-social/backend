package com.app.modules.report.repository;

import java.util.Optional;
import java.util.UUID;

import com.app.modules.report.enums.ReportType;

/** Resolves ownership for the polymorphic entity targeted by a report. */
public interface ReportTargetRepository {

    /**
     * Finds the owner of a live target entity as the reporter is allowed to see it.
     *
     * <p>A hidden comment is visible to its author and to the owner of the post it sits on, and to
     * nobody else. Anyone else gets the same empty answer as for a comment that does not exist, so
     * a report attempt cannot be used to find hidden comments. The author is let through so the
     * self-report refusal answers exactly as it does for a visible comment.
     *
     * @param reportType domain type that determines the target table
     * @param entityId target entity identifier
     * @param reporterId the account submitting the report
     * @return target owner identifier, or empty when the target does not exist, is deleted, or is
     *     hidden from the reporter
     */
    Optional<UUID> findOwnerId(ReportType reportType, UUID entityId, UUID reporterId);
}
