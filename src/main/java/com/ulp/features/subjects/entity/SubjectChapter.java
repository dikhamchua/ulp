package com.ulp.features.subjects.entity;

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
 * Sample chapter outline row for a subject ({@code subject_chapters}).
 *
 * <p>Not linked to class {@code sections}. Soft-deleted rows release
 * {@code display_order} so the unique (subject_id, order) slot can be reused.
 */
@Entity
@Table(name = "subject_chapters")
@SQLRestriction("is_deleted = 0")
public class SubjectChapter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(nullable = false, length = 200)
    private String title;

    /** Position among live chapters; null after soft-delete. */
    @Column(name = "display_order")
    private Short displayOrder;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** JPA-only constructor. */
    protected SubjectChapter() {
    }

    /**
     * Creates a live chapter ready to persist.
     *
     * @param subjectId    owning subject
     * @param title        display title
     * @param displayOrder position among live chapters
     * @param createdBy    actor user id, or null
     */
    public SubjectChapter(Long subjectId, String title, Short displayOrder, Long createdBy) {
        this.subjectId = subjectId;
        this.title = title;
        this.displayOrder = displayOrder;
        this.createdBy = createdBy;
        this.deleted = false;
    }

    @PrePersist
    void onPersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** Renames the chapter. Caller validates title first. */
    public void rename(String newTitle) {
        this.title = newTitle;
    }

    /** Repositions among siblings of the same subject. */
    public void changeOrder(short newOrder) {
        this.displayOrder = newOrder;
    }

    /**
     * Soft-deletes and clears {@code display_order} so the unique order slot
     * is free for a new live chapter.
     */
    public void markDeleted() {
        this.deleted = true;
        this.displayOrder = null;
    }

    public Long getId() {
        return id;
    }

    public Long getSubjectId() {
        return subjectId;
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

    public Long getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
