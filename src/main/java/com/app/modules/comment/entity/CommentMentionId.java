package com.app.modules.comment.entity;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Composite primary key for {@link CommentMention} {@code (comment_id, mentioned_user_id)}. */
@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class CommentMentionId implements Serializable {

    @Column(name = "comment_id")
    private UUID commentId;

    @Column(name = "mentioned_user_id")
    private UUID mentionedUserId;
}
