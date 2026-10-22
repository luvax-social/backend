package com.app.modules.comment.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

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
 * A comment {@code @mention}, stored by user id.
 *
 * <p>Maps to {@code comment_mentions}. The row records who was mentioned when the comment was
 * written; clients render the current username of that id and show any other {@code @name} as plain
 * text, so a rename or a deleted account can never point old text at someone else. {@code handle}
 * is the text as typed, without the {@code @}.
 */
@Entity
@Table(name = "comment_mentions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommentMention {

    @EmbeddedId private CommentMentionId id;

    @Column(name = "handle", nullable = false, length = 30)
    private String handle;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public UUID getCommentId() {
        return id.getCommentId();
    }

    public UUID getMentionedUserId() {
        return id.getMentionedUserId();
    }
}
