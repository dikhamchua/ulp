package com.ulp.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * JPA entity mapped to the {@code activity_lessons} table.
 * Represents an immutable audit-log entry for every mutation performed on a
 * {@link Lesson}: CREATED, UPDATED, PUBLISHED, UNPUBLISHED, DELETED, REORDERED.
 *
 * <p><b>Important:</b> {@code lessonId} is stored as a plain {@code Long}
 * rather than a {@code @ManyToOne} association to {@link Lesson}. This
 * mirrors the {@link SectionActivity} design: {@code Lesson} carries a
 * {@code @SQLRestriction("is_deleted = 0")} filter, so a JPA join would
 * silently exclude rows whose target lesson has been soft-deleted. Keeping
 * the foreign key as a bare {@code Long} ensures the audit log remains
 * intact and independent of the lesson lifecycle.
 *
 * <p>Plain getters (no Lombok {@code @Data} / {@code @Getter}) — kept
 * explicit to match the conventions used by the surrounding entities and
 * sidestep equals/hashCode pitfalls flagged in the project guidelines.
 */
@Entity
@Table(name = "activity_lessons")
public class LessonActivity {

    public static final String TYPE_CREATED = "CREATED";
    public static final String TYPE_UPDATED = "UPDATED";
    public static final String TYPE_PUBLISHED = "PUBLISHED";
    public static final String TYPE_UNPUBLISHED = "UNPUBLISHED";
    public static final String TYPE_DELETED = "DELETED";
    public static final String TYPE_REORDERED = "REORDERED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lesson_id", nullable = false)
    private Long lessonId;

    @Column(nullable = false, length = 50)
    private String type;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "JSON")
    private String metadata;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /** JPA-only constructor; do not call from application code. */
    protected LessonActivity() {
    }

    public LessonActivity(Long lessonId, String type, String description,
                          String metadata, Long createdBy) {
        this.lessonId = lessonId;
        this.type = type;
        this.description = description;
        this.metadata = metadata;
        this.createdBy = createdBy;
    }

    // ── Getters ────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public Long getLessonId() {
        return lessonId;
    }

    public String getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public String getMetadata() {
        return metadata;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
