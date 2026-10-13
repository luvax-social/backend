package com.app.modules.post.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A "not interested" signal on one post, mapped to {@code post_not_interested(user_id, post_id)}.
 *
 * <p>The row removes the post from that user's recommendation surfaces and feeds the same signal to
 * the recommender. It is deleted together with either the user or the post.
 */
@Entity
@Table(name = "post_not_interested")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostNotInterested {

    @EmbeddedId private PostNotInterestedId id;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    private OffsetDateTime createdAt;
}
