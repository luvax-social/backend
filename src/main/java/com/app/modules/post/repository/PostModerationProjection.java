package com.app.modules.post.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Everything the moderation removal and restore paths need about a post, readable while the row is
 * soft-deleted.
 *
 * <p>Native and projection-shaped on purpose. {@code Post} carries
 * an entity restriction for both owner and moderation tombstones, so a removed post cannot be loaded through the
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

    String getStatus();

    /** Owner deletion timestamp; moderation cannot clear it. */
    Instant getDeletedAt();

    /** Status held before the moderation removal, or null for a post removed before V59. */
    String getStatusBeforeModeration();
}
