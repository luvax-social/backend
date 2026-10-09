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
 * A request to see less of one author, mapped to {@code account_show_fewer(user_id, author_id)}.
 *
 * <p>The row is a ranking preference for the recommendation feed only; it never hides content and
 * never reaches the author. The database rejects a row where the user and the author are the same
 * account.
 */
@Entity
@Table(name = "account_show_fewer")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountShowFewer {

    @EmbeddedId private AccountShowFewerId id;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    private OffsetDateTime createdAt;
}
