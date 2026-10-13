package com.app.modules.social.entity;

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
 * One account muting another, mapped to {@code user_mutes(user_id, muted_user_id)}.
 *
 * <p>A mute hides the target's posts, stories or both from the muting user's surfaces without
 * telling the target. The database rejects a self-mute and a row with both scopes off; turning both
 * scopes off is an unmute, which the service performs by deleting the row.
 */
@Entity
@Table(name = "user_mutes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserMute {

    @EmbeddedId private UserMuteId id;

    @Column(name = "mute_posts", nullable = false)
    private boolean mutePosts;

    @Column(name = "mute_stories", nullable = false)
    private boolean muteStories;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    private OffsetDateTime createdAt;

    /** Maintained by trigger {@code trg_user_mutes_updated_at}; it orders the mute list. */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    private OffsetDateTime updatedAt;
}
