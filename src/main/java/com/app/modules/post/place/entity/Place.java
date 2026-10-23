package com.app.modules.post.place.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import com.app.modules.post.place.converter.PlaceStatusConverter;
import com.app.modules.post.place.enums.PlaceStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A named location a post can reference.
 *
 * <p>Maps to {@code places}. There are no coordinates: a place is a name, identified by {@code
 * normalizedName}, which folds case, accents and spacing so spellings of one name are one place.
 * {@code postCount} is maintained exclusively by trigger {@code trg_place_post_count} (V154).
 * {@code deleted} is a state, never a row removal, so the posts that used a place keep their
 * reference.
 */
@Entity
@Table(name = "places")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Place {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** The label as the first person to use it typed it. */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "normalized_name", nullable = false, updatable = false, length = 100)
    private String normalizedName;

    @Convert(converter = PlaceStatusConverter.class)
    @Column(name = "status", nullable = false, columnDefinition = "place_status")
    private PlaceStatus status;

    /** Administrator's justification for the current status; null while the place is untouched. */
    @Column(name = "status_note")
    private String statusNote;

    @Column(name = "status_at")
    private OffsetDateTime statusAt;

    /** Administrator that set the current status; null once that account is deleted. */
    @Column(name = "status_by")
    private UUID statusBy;

    /** The account that first used the name; null once that account is deleted. */
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /** Maintained exclusively by Postgres trigger {@code trg_place_post_count} (V154). */
    @Column(name = "post_count", insertable = false, updatable = false)
    private int postCount;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
