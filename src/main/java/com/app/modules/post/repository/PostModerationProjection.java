package com.app.modules.post.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Everything the moderation removal and restore paths need about a post, readable while the row is
 * soft-deleted.
 *
 * <p>Native and projection-shaped on purpose. {@code Post} carries
 * {@code @SQLRestriction("deleted_at IS NULL")}, so a removed post cannot be loaded through the
 * entity at all, and restore has to read one.
 */
public interface PostModerationProjection {

    UUID getUserId();

    String getCaption();

    /**
     * Creation instant.
     *
     * <p>Typed as {@code Instant} because a native-query projection receives the driver's own JDBC
     * type for {@code timestamptz} and Spring has no converter from it to {@code OffsetDateTime}.
     */
    Instant getCreatedAt();

    /** First publication instant, or null for a post that was never published. */
    Instant getPublishedAt();

    /** The scheduled time a post removed while scheduled still carries, or null. */
    Instant getScheduledAt();

    /** The place the post carries, or null; it may since have been banned or deleted. */
    UUID getPlaceId();

    String getStatus();

    /** Status held before the moderation removal, or null for a post removed before V59. */
    String getStatusBeforeModeration();
}
