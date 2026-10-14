package com.app.modules.message.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One user's emoji reaction to one message.
 *
 * <p>Maps to {@code message_reactions} (V146), which allows one row per user per message. The
 * entity is read-only: every write goes through the upsert and delete statements of {@code
 * MessageReactionRepository}, because the database stamps {@code reacted_at} and a changed emoji is
 * a new reaction time. {@code admin_removed_at} is the moderation tombstone and is independent of
 * the message's own tombstones: a reaction is shown only while neither it nor its message carries
 * one.
 */
@Entity
@Immutable
@Table(name = "message_reactions")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageReaction {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "message_id", nullable = false, updatable = false)
    private UUID messageId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "emoji", nullable = false, updatable = false)
    private String emoji;

    @Column(name = "reacted_at", nullable = false, updatable = false)
    private OffsetDateTime reactedAt;

    @Column(name = "admin_removed_at", updatable = false)
    private OffsetDateTime adminRemovedAt;
}
