package com.app.modules.social.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import com.app.modules.social.converter.FollowStatusConverter;
import com.app.modules.social.enums.FollowStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Follow relationship between two users.
 *
 * <p>The composite key maps to {@code follows(follower_id, following_id)}. Counter updates are
 * handled exclusively by Postgres trigger {@code trg_follow_counts}; application code must not
 * mutate user counters.
 */
@Entity
@Table(name = "follows")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Follow {

    @EmbeddedId private FollowId id;

    @Convert(converter = FollowStatusConverter.class)
    @Column(name = "status", nullable = false, columnDefinition = "follow_status")
    private FollowStatus status;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Set by trg_follow_accepted_at on acceptance; null while pending. */
    @Column(name = "accepted_at", insertable = false, updatable = false)
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    private OffsetDateTime acceptedAt;
}
