package com.app.modules.support.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One of the eight closed verification categories, with the metadata a client needs to render it.
 *
 * <p>Read-only from the application's point of view: rows are created by migration, following the
 * enum-and-config contract in {@code docs/modules/GLOBAL_RULES.md}. Adding a ninth category is a
 * migration, not a runtime write.
 *
 * <p>{@code iconKey} is stored and served without the server knowing anything about its shape. The
 * client maps it to a glyph, which is what allows the badge to be redrawn without a migration.
 */
@Entity
@Table(name = "verification_categories")
@Getter
@Setter
@NoArgsConstructor
public class VerificationCategory {

    @Id
    @Column(name = "category_key", nullable = false, length = 50, updatable = false)
    private String categoryKey;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    /** The occupations this category covers, shown to the requester. Never parsed. */
    @Column(name = "covers", nullable = false, columnDefinition = "TEXT")
    private String covers;

    @Column(name = "icon_key", nullable = false, length = 50)
    private String iconKey;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;
}
