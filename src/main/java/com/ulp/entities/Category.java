package com.ulp.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code categories} table (V1 schema) — course
 * categories organised as a hard two-level tree (parent → child).
 *
 * <p>The self-reference is stored as a scalar {@code Long parentId} column,
 * not a {@code @ManyToOne Category parent} object relation. This mirrors the
 * {@link Section} pattern: it avoids Hibernate lazy-loading loops and keeps
 * the entity simple — the service composes the tree with explicit repository
 * queries. A {@code null} {@code parentId} marks a top-level parent.
 *
 * <p>The table has NO {@code is_deleted} column, so deletion is a real
 * {@code DELETE}; there is no {@code @SQLRestriction} here. Active/inactive is
 * expressed by the {@code is_active} flag instead.
 *
 * <p>Plain getters (no Lombok {@code @Data}) to avoid the equals/hashCode
 * pitfalls flagged in the project conventions.
 */
@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 150)
    private String slug;

    /** Parent category id; {@code null} for a top-level (parent) category. */
    @Column(name = "parent_id")
    private Long parentId;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** JPA-only constructor; do not call from application code. */
    protected Category() {
    }

    /**
     * Creates a new category ready to be persisted.
     *
     * @param name        display name (<=150 chars)
     * @param slug        URL slug (unique, <=150 chars)
     * @param parentId    owning parent id, or {@code null} for a top-level category
     * @param description optional free-text description
     * @param active      whether the category is active on creation
     */
    public Category(String name, String slug, Long parentId, String description, boolean active) {
        this.name = name;
        this.slug = slug;
        this.parentId = parentId;
        this.description = description;
        this.active = active;
    }

    // ── Lifecycle hooks ────────────────────────────────────────────────

    @PrePersist
    void onPersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ── Business helpers ───────────────────────────────────────────────

    /** Applies edited fields. Caller must have validated name/slug/parent first. */
    public void applyEdit(String name, String slug, Long parentId, String description, boolean active) {
        this.name = name;
        this.slug = slug;
        this.parentId = parentId;
        this.description = description;
        this.active = active;
    }

    /** Flips the active flag and returns the new state. */
    public boolean toggleActive() {
        this.active = !this.active;
        return this.active;
    }

    /** True when this row is a top-level category (no parent). */
    public boolean isTopLevel() {
        return parentId == null;
    }

    // ── Getters ────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public Long getParentId() {
        return parentId;
    }

    public String getDescription() {
        return description;
    }

    public boolean isActive() {
        return active;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
