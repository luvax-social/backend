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
 * One phrase of the platform-wide hidden word list, mapped to {@code hidden_words}.
 *
 * <p>Administrators maintain this list. {@code normalizedPhrase} is the matching form and is unique
 * across the list, while {@code phrase} keeps the text as the administrator typed it. The creator
 * link is cleared, not cascaded, when the creating account is deleted, so the list survives staff
 * turnover.
 */
@Entity
@Table(name = "hidden_words")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HiddenWord {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "phrase", nullable = false, length = 64)
    private String phrase;

    @Column(name = "normalized_phrase", nullable = false, length = 256)
    private String normalizedPhrase;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    private OffsetDateTime createdAt;

    /** Maintained by trigger {@code trg_hidden_words_updated_at}. */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    private OffsetDateTime updatedAt;
}
