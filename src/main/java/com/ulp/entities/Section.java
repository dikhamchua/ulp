package com.ulp.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code sections} table introduced by V13.
 *
 * <p>A section is a container for {@link Lesson}s inside a class — i.e. a
 * "chuong" (chapter). Each class owns 0..N sections; sections are ordered
 * via {@code display_order} which is unique per class so lecturer can
 * drag-reorder them in the lessons tab.
 *
 * <p>Soft-deleted rows are filtered out by {@link SQLRestriction}; deletion
 * is non-cascading on display_order — neighbours keep their positions when
 * a section is removed (the UI doesn't compact the gaps, the lecturer can
 * reorder if they want).
 *
 * <p>Plain getters (no Lombok {@code @Data}) to avoid the equals/hashCode
 * pitfalls flagged in the project conventions.
 */
@Entity
@Table(name = "sections")
@SQLRestriction("is_deleted = 0")
public class Section {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(nullable = false, length = 200)
    private String title;

    /**
     * Position within the parent class, zero-based. Nullable in the schema
     * so soft-deleted rows can release their slot — see {@link #markDeleted()}.
     */
    @Column(name = "display_order")
    private Short displayOrder;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private Long createdBy;

    /** JPA-only constructor; do not call from application code. */
    protected Section() {
    }

    /**
     * Creates a new section ready to be persisted.
     *
     * @param classId      owning class id
     * @param title        display title (<=200 chars)
     * @param displayOrder zero-based position within the class
     * @param createdBy    id of the lecturer who created the row
     */
    public Section(Long classId, String title, Short displayOrder, Long createdBy) {
        this.classId = classId;
        this.title = title;
        this.displayOrder = displayOrder;
        this.createdBy = createdBy;
        this.deleted = false;
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

    /** Renames the section. Caller must have already validated the new title. */
    public void rename(String newTitle) {
        this.title = newTitle;
    }

    /** Re-positions the section within its parent class. */
    public void changeOrder(short newOrder) {
        this.displayOrder = newOrder;
    }

    /**
     * Marks the section as soft-deleted. The {@link SQLRestriction} on this
     * entity will then exclude the row from default queries.
     *
     * <p>Also clears {@code display_order} to NULL so the section's slot is
     * released — the unique key {@code uk_section_class_order} allows
     * multiple NULLs, which lets a brand new section take the freed
     * position without colliding with the deleted row.
     */
    public void markDeleted() {
        this.deleted = true;
        this.displayOrder = null;
    }

    // ── Getters ────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public Long getClassId() {
        return classId;
    }

    public String getTitle() {
        return title;
    }

    public Short getDisplayOrder() {
        return displayOrder;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }
}
