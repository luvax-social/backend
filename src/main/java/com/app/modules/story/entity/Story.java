package com.app.modules.story.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;

import com.app.modules.story.converter.StoryTypeConverter;
import com.app.modules.story.enums.StoryType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Ephemeral 24-hour story owned by a user, backed by exactly one media asset.
 *
 * <p>Maps to the {@code stories} table. {@code view_count} is maintained exclusively by Postgres
 * trigger {@code trg_story_view_count} (V16) and is never written from application code. {@code
 * expires_at} is set by the service from the {@code story_duration_hours} system setting. Soft
 * delete sets {@code deleted_at}; expired rows remain in the table until the cleanup job removes
 * rows that are both soft-deleted and expired, so every read must also filter on {@code
 * expires_at}.
 */
@Entity
@Table(name = "stories")
// Soft-delete filter: every query on stories must exclude deleted rows (GLOBAL_RULES §3).
// Both tombstones, so every JPQL, derived and entity-graph read hides an administratively
// removed story without each query restating the predicate. Native queries bypass this
// filter and carry the predicate themselves.
@SQLRestriction("deleted_at IS NULL AND admin_removed_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Story {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "media_asset_id", nullable = false, updatable = false)
    private UUID mediaAssetId;

    @Convert(converter = StoryTypeConverter.class)
    @Column(name = "story_type", nullable = false, columnDefinition = "story_type")
    private StoryType storyType;

    @Column(name = "caption", columnDefinition = "TEXT")
    private String caption;

    /** Maintained exclusively by Postgres trigger {@code trg_story_view_count} (V16). */
    @Column(name = "view_count", insertable = false, updatable = false)
    private int viewCount;

    /** Maintained exclusively by Postgres trigger {@code trg_story_like_count} (V49). */
    @Column(name = "like_count", insertable = false, updatable = false)
    private int likeCount;

    /** Set by the service at creation from the {@code story_duration_hours} system setting. */
    @Column(name = "expires_at", nullable = false, updatable = false)
    private OffsetDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Set by application code on soft delete; {@code null} for live rows (GLOBAL_RULES §6). */
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    /**
     * Set by administrative removal; {@code null} when not administratively removed.
     *
     * <p>Independent of {@link #deletedAt}, which the owner owns. The row is hidden when either is
     * set, and clearing this one never undoes the owner's own deletion.
     */
    @Column(name = "admin_removed_at")
    private OffsetDateTime adminRemovedAt;
}
