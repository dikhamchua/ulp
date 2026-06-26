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
 * JPA entity mapping the {@code lessons} table aligned by V14.
 *
 * <p>A lesson is a unit of content inside a {@link Section}. ULP-4.0b
 * narrows the lesson model to a single rich-text body persisted inline in
 * {@code content_richtext}; PDF / video attachments come in ULP-4.0c.
 *
 * <p>Soft-deleted rows are filtered out by {@link SQLRestriction}. The
 * {@code display_order} column is nullable on purpose: when a lesson is
 * soft-deleted, {@link #markDeleted()} clears the slot to NULL so the
 * unique key {@code uk_lesson_section_order} allows another lesson to
 * claim that position. The two-phase reorder in {@code LessonsService}
 * relies on the same pattern.
 *
 * <p>Plain getters (no Lombok {@code @Data}) to avoid the equals/hashCode
 * pitfalls flagged in the project conventions.
 */
@Entity
@Table(name = "lessons")
@SQLRestriction("is_deleted = 0")
public class Lesson {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "section_id", nullable = false)
    private Long sectionId;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(nullable = false, length = 20)
    private String status;

    /**
     * Position within the parent section, zero-based. Nullable so soft-deleted
     * rows can release their slot — see {@link #markDeleted()}.
     */
    @Column(name = "display_order")
    private Short displayOrder;

    @Column(name = "content_richtext", columnDefinition = "LONGTEXT")
    private String contentRichtext;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** JPA-only constructor; do not call from application code. */
    protected Lesson() {
    }

    /**
     * Creates a new draft lesson ready to be persisted.
     *
     * @param sectionId    owning section id
     * @param title        display title (<=300 chars)
     * @param displayOrder zero-based position within the section
     * @param createdBy    id of the lecturer who created the row
     */
    public Lesson(Long sectionId, String title, Short displayOrder, Long createdBy) {
        this.sectionId = sectionId;
        this.title = title;
        this.displayOrder = displayOrder;
        this.createdBy = createdBy;
        this.status = STATUS_DRAFT;
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

    /** Renames the lesson. Caller must have already validated the new title. */
    public void rename(String newTitle) {
        this.title = newTitle;
    }

    /** Replaces the rich-text body. Caller must pass already-sanitised HTML. */
    public void updateContent(String sanitisedHtml) {
        this.contentRichtext = sanitisedHtml;
    }

    /**
     * Transitions the lesson to {@link #STATUS_PUBLISHED} and stamps
     * {@code publishedAt} with the current time. Idempotent on the status
     * field but the timestamp is refreshed on each call.
     */
    public void publish() {
        this.status = STATUS_PUBLISHED;
        this.publishedAt = LocalDateTime.now();
    }

    /** Reverts the lesson to {@link #STATUS_DRAFT} and clears {@code publishedAt}. */
    public void unpublish() {
        this.status = STATUS_DRAFT;
        this.publishedAt = null;
    }

    /** Re-positions the lesson within its parent section. */
    public void changeOrder(short newOrder) {
        this.displayOrder = newOrder;
    }

    /**
     * Marks the lesson as soft-deleted. The {@link SQLRestriction} on this
     * entity will then exclude the row from default queries.
     *
     * <p>Also clears {@code display_order} to NULL so the lesson's slot is
     * released — the unique key {@code uk_lesson_section_order} allows
     * multiple NULLs, which lets a brand new lesson take the freed position
     * without colliding with the deleted row.
     */
    public void markDeleted() {
        this.deleted = true;
        this.displayOrder = null;
    }

    // ── Getters ────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public Long getSectionId() {
        return sectionId;
    }

    public String getTitle() {
        return title;
    }

    public String getStatus() {
        return status;
    }

    public Short getDisplayOrder() {
        return displayOrder;
    }

    public String getContentRichtext() {
        return contentRichtext;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
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
}
