package com.app.modules.users.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.UpdateTimestamp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Per-user notification and privacy preferences. Defaults match the V03 schema. */
@Entity
@Table(name = "user_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSettings {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "notify_likes", nullable = false)
    @Builder.Default
    private boolean notifyLikes = true;

    @Column(name = "notify_comments", nullable = false)
    @Builder.Default
    private boolean notifyComments = true;

    @Column(name = "notify_follows", nullable = false)
    @Builder.Default
    private boolean notifyFollows = true;

    @Column(name = "notify_mentions", nullable = false)
    @Builder.Default
    private boolean notifyMentions = true;

    @Column(name = "notify_messages", nullable = false)
    @Builder.Default
    private boolean notifyMessages = true;

    @Column(name = "show_activity_status", nullable = false)
    @Builder.Default
    private boolean showActivityStatus = true;

    @Column(name = "allow_story_replies", nullable = false)
    @Builder.Default
    private boolean allowStoryReplies = true;

    /**
     * False when the account has asked not to be offered in other people's suggestions.
     *
     * <p>Applied at read time as well as in the precompute job, because a twelve-hour cycle would
     * otherwise keep offering an account that opted out this morning until tonight.
     */
    @Column(name = "suggestible", nullable = false)
    @Builder.Default
    private boolean suggestible = true;

    @Column(name = "allow_message_requests", nullable = false)
    @Builder.Default
    private boolean allowMessageRequests = true;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
