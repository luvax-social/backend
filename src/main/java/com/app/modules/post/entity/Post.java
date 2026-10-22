package com.app.modules.post.entity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.generator.EventType;

import com.app.modules.post.converter.PostStatusConverter;
import com.app.modules.post.converter.PostTypeConverter;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.enums.PostType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Aggregate root for a user post.
 *
 * <p>Maps to the {@code posts} table. The denormalized counters ({@code like_count}, {@code
 * comment_count}, {@code save_count}) are maintained by Postgres triggers (V16) and {@code
 * view_count} by a background job only; none are written from application code. Soft delete sets
 * {@code deleted_at} and {@code status = removed} — never hard-delete.
 */
@Entity
@Table(name = "posts")
// Soft-delete filter: every query on posts must exclude deleted rows (GLOBAL_RULES §3).
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "caption", columnDefinition = "TEXT")
    private String caption;

    @Convert(converter = PostTypeConverter.class)
    @Column(name = "post_type", nullable = false, columnDefinition = "post_type")
    private PostType postType;

    @Convert(converter = PostStatusConverter.class)
    @Column(name = "status", nullable = false, columnDefinition = "post_status")
    private PostStatus status;

    /** Maintained exclusively by Postgres trigger {@code trg_post_like_count} (V16). */
    @Column(name = "like_count", insertable = false, updatable = false)
    private int likeCount;

    /** Maintained exclusively by Postgres trigger {@code trg_post_comment_count} (V16). */
    @Column(name = "comment_count", insertable = false, updatable = false)
    private int commentCount;

    /** Maintained exclusively by Postgres trigger {@code trg_post_save_count} (V16). */
    @Column(name = "save_count", insertable = false, updatable = false)
    private int saveCount;

    /** Updated by a background job only — never written from request paths (GLOBAL_RULES §2). */
    @Column(name = "view_count", insertable = false, updatable = false)
    private int viewCount;

    /** The place this post is tagged at, or {@code null}; a plain reference to the places table. */
    @Column(name = "place_id")
    private UUID placeId;

    /**
     * When the post is due to be published; set while the status is {@code scheduled}, and kept
     * after a moderation removal so a restore can tell whether the time has passed.
     */
    @Column(name = "scheduled_at")
    private OffsetDateTime scheduledAt;

    // Written only by trigger fn_posts_published_at (V151): the instant the status first became
    // published, null until then and never cleared. Hibernate re-reads it after every insert and
    // update so the entity reflects the trigger-written value.
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "published_at", insertable = false, updatable = false)
    private OffsetDateTime publishedAt;

    // Database-generated like updated_at, rather than @CreationTimestamp. The column's DEFAULT
    // NOW() and the sibling updated_at default both resolve to the same transaction timestamp, so
    // the two agree exactly at insert. Under @CreationTimestamp this value came from the JVM
    // clock while updated_at came from Postgres, leaving them permanently unequal on a post
    // nobody had touched. Hibernate already re-reads updated_at after every insert, so reading
    // this one back costs no extra round trip.
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    // trg_posts_updated_at (V16) is the sole writer of this column; Hibernate never sends it in an
    // INSERT or UPDATE and instead re-selects it afterward so the entity reflects the
    // trigger-written value instead of a stale application-side guess the trigger would discard.
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    /** Set by application code on soft delete; {@code null} for live rows (GLOBAL_RULES §6). */
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    // Batches the lazy media-collection initialization across a page of posts into one IN query,
    // so a list endpoint issues a single media load instead of one per post.
    @BatchSize(size = 100)
    @Builder.Default
    private List<PostMedia> media = new ArrayList<>();
}
