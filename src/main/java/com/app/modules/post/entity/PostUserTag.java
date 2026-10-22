package com.app.modules.post.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import com.app.modules.post.converter.TagStatusConverter;
import com.app.modules.post.enums.TagStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A person tagged on one media item of a post.
 *
 * <p>Maps to {@code post_user_tags}. The tag belongs to a {@code post_media} row, so the same
 * person can be tagged on several items of a carousel, and {@code (post_id, post_media_id)} is a
 * composite foreign key that stops a tag naming an item of another post. The coordinates are
 * fractions of the item between 0 and 1. A {@code pending} tag is visible only to the post owner
 * and the tagged person; the limit of tags per item is enforced by a trigger.
 */
@Entity
@Table(name = "post_user_tags")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostUserTag {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "post_id", nullable = false, updatable = false)
    private UUID postId;

    @Column(name = "post_media_id", nullable = false, updatable = false)
    private UUID postMediaId;

    @Column(name = "tagged_user_id", nullable = false, updatable = false)
    private UUID taggedUserId;

    @Column(name = "x_position", nullable = false, precision = 5, scale = 4)
    private BigDecimal xPosition;

    @Column(name = "y_position", nullable = false, precision = 5, scale = 4)
    private BigDecimal yPosition;

    @Convert(converter = TagStatusConverter.class)
    @Column(name = "status", nullable = false, columnDefinition = "tag_status")
    private TagStatus status;

    /** The account that placed the tag; null once that account is deleted. */
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;
}
