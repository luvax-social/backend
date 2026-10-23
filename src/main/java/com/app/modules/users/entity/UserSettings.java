package com.app.modules.users.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.UpdateTimestamp;

import com.app.modules.users.converter.MessagePermissionConverter;
import com.app.modules.users.converter.TagPermissionConverter;
import com.app.modules.users.enums.MessagePermission;
import com.app.modules.users.enums.TagPermission;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-user notification and privacy preferences.
 *
 * <p>The field defaults apply when a row is built in code; the column defaults in the database
 * (V03, V136, V137) apply to rows inserted by SQL, and the two are kept equal.
 */
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

    @Convert(converter = MessagePermissionConverter.class)
    @Column(name = "message_permission", nullable = false, columnDefinition = "message_permission")
    @Builder.Default
    private MessagePermission messagePermission = MessagePermission.EVERYONE;

    /** True when the platform-wide hidden word list also applies to this account's content. */
    @Column(name = "hidden_words_system_enabled", nullable = false)
    @Builder.Default
    private boolean hiddenWordsSystemEnabled = true;

    /** Who may tag this account in a post; decided by the interaction policy, never by callers. */
    @Convert(converter = TagPermissionConverter.class)
    @Column(name = "tag_permission", nullable = false, columnDefinition = "tag_permission")
    @Builder.Default
    private TagPermission tagPermission = TagPermission.EVERYONE;

    /** True when a tag of this account waits for its approval before it is shown. */
    @Column(name = "manual_tag_approval", nullable = false)
    @Builder.Default
    private boolean manualTagApproval = false;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
