package com.app.modules.post.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import com.app.modules.post.converter.CollaborationStatusConverter;
import com.app.modules.post.enums.CollaborationStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An invitation to co-author a post and what became of it.
 *
 * <p>Maps to {@code post_collaborators}. The post owner keeps sole edit and delete rights; a
 * co-author is shown on the post only while the status is {@code accepted}. Rows are kept after a
 * response so {@code accepted_at} survives for attributing a post's performance to every author who
 * held a share of it. The cap of live rows per post and the {@code users.post_count} adjustments
 * are enforced by triggers (V159).
 */
@Entity
@Table(name = "post_collaborators")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostCollaborator {

    @EmbeddedId private PostCollaboratorId id;

    @Convert(converter = CollaborationStatusConverter.class)
    @Column(name = "status", nullable = false, columnDefinition = "collaboration_status")
    private CollaborationStatus status;

    /** The owner who invited this account; null once that account is deleted. */
    @Column(name = "invited_by", updatable = false)
    private UUID invitedBy;

    @Generated(event = EventType.INSERT)
    @Column(name = "invited_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime invitedAt;

    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    public UUID getPostId() {
        return id.getPostId();
    }

    public UUID getUserId() {
        return id.getUserId();
    }
}
