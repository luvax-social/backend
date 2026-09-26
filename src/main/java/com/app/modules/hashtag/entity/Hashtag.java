package com.app.modules.hashtag.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.app.modules.hashtag.converter.HashtagStatusConverter;
import com.app.modules.hashtag.enums.HashtagStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Canonical hashtag registry.
 *
 * <p>Maps to the {@code hashtags} table. The {@code name} is stored without the {@code #} prefix
 * and lowercased; normalization is enforced by {@code HashtagServiceImpl}. The {@code postCount}
 * counter is maintained exclusively by the Postgres trigger {@code trg_hashtag_post_count} (V16)
 * and is intentionally marked {@code insertable = false, updatable = false}.
 */
@Entity
@Table(name = "hashtags")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Hashtag {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    /** Maintained exclusively by Postgres trigger {@code trg_hashtag_post_count} (V16). */
    @Column(name = "post_count", insertable = false, updatable = false)
    private int postCount;

    /**
     * Lifecycle state; {@code active} for every hashtag created through ordinary first use.
     *
     * <p>{@code deleted} is a state, never a row removal: removing the row would cascade to {@code
     * post_hashtags} and drive the {@code post_count} trigger over every post that used the tag.
     */
    @Convert(converter = HashtagStatusConverter.class)
    @Column(name = "status", nullable = false, columnDefinition = "hashtag_status")
    private HashtagStatus status;

    /** Administrator's justification for the current status; null while the tag is untouched. */
    @Column(name = "status_note")
    private String statusNote;

    @Column(name = "status_at")
    private OffsetDateTime statusAt;

    /** Administrator that set the current status; null once that account is deleted. */
    @Column(name = "status_by")
    private UUID statusBy;

    /** When an administrator pinned this hashtag platform-wide; null means not pinned. */
    @Column(name = "pinned_at")
    private OffsetDateTime pinnedAt;

    @Column(name = "pinned_by")
    private UUID pinnedBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
