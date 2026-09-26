package com.app.modules.comment.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.generator.EventType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A comment on a post, modelled as an adjacency list with a {@code root_id} ancestor pointer.
 *
 * <p>Maps to the {@code comments} table. The denormalized counters ({@code like_count}, {@code
 * reply_count}) are maintained by Postgres triggers (V16) and are never written from application
 * code. {@code content} is the only mutable column, and changing it also stamps {@code edited_at}.
 * Soft delete sets {@code deleted_at} — never hard-delete; {@code @SQLRestriction} excludes deleted
 * rows from every query.
 */
@Entity
@Table(name = "comments")
// Both tombstones, so every JPQL, derived and entity-graph read hides an administratively
// removed comment without each query restating the predicate. Native queries bypass this
// filter and carry the predicate themselves.
@SQLRestriction("deleted_at IS NULL AND admin_removed_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "post_id", nullable = false, updatable = false)
    private UUID postId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "parent_id", updatable = false)
    private UUID parentId;

    @Column(name = "root_id", updatable = false)
    private UUID rootId;

    @Column(name = "depth", nullable = false, updatable = false)
    private short depth;

    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "moderation_status", nullable = false)
    private String moderationStatus;

    /** Maintained exclusively by Postgres trigger {@code trg_comment_like_count} (V16). */
    @Column(name = "like_count", insertable = false, updatable = false)
    private int likeCount;

    /** Maintained exclusively by Postgres trigger {@code trg_comment_reply_count} (V16). */
    @Column(name = "reply_count", insertable = false, updatable = false)
    private int replyCount;

    // Database-generated like updated_at, rather than @CreationTimestamp. The column's DEFAULT
    // NOW() and the sibling updated_at default both resolve to the same transaction timestamp, so
    // the two agree exactly at insert. Under @CreationTimestamp this value came from the JVM
    // clock while updated_at came from Postgres, leaving them permanently unequal on a comment
    // nobody had touched. Hibernate already re-reads updated_at after every insert, so reading
    // this one back costs no extra round trip.
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    // trg_comments_updated_at (V16) is the sole writer of this column; Hibernate never sends it in
    // an INSERT or UPDATE and instead re-selects it afterward so the entity reflects the
    // trigger-written value instead of a stale application-side guess the trigger would discard.
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    // Set by application code on a content edit only (V45); null means the content has never
    // changed. Deliberately not a trigger column: updated_at already covers "this row changed",
    // and its trigger fires for a like or a reply too, which is why it cannot answer whether the
    // author edited anything.
    @Column(name = "edited_at")
    private OffsetDateTime editedAt;

    /** Set by application code on soft delete; {@code null} for live rows (GLOBAL_RULES §6). */
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    /**
     * Set by administrative removal; {@code null} when not administratively removed.
     *
     * <p>Independent of {@link #deletedAt}, which the author owns. The row is hidden when either is
     * set, and clearing this one never undoes the author's own deletion.
     */
    @Column(name = "admin_removed_at")
    private OffsetDateTime adminRemovedAt;
}
