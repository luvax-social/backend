package com.app.modules.social.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One phrase of a user's personal hidden word list, mapped to {@code user_hidden_words}.
 *
 * <p>{@code normalizedPhrase} is the matching form and is unique per user, so the same phrase may
 * appear on several users' lists but never twice on one. The per-user entry limit is enforced by
 * the service, not by the database.
 */
@Entity
@Table(name = "user_hidden_words")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserHiddenWord {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "phrase", nullable = false, length = 64)
    private String phrase;

    @Column(name = "normalized_phrase", nullable = false, length = 256)
    private String normalizedPhrase;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    private OffsetDateTime createdAt;
}
