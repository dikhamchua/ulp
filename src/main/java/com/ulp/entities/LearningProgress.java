package com.ulp.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code learning_progress} table (ULP-4.5).
 *
 * <p>Tracks one student's progress through a single lesson. The unique key
 * {@code idx_lp_user_lesson} guarantees at most one row per (user, lesson);
 * the service relies on that constraint for its idempotent upsert.
 *
 * <p>Status transitions: a freshly opened lesson creates the row as
 * {@link #STATUS_IN_PROGRESS}; the student toggle moves it to
 * {@link #STATUS_COMPLETED} and back. {@code NOT_STARTED} exists in the DB
 * CHECK for completeness but is never written here — a missing row already
 * means "not started".
 */
@Entity
@Table(name = "learning_progress")
public class LearningProgress {

    public static final String STATUS_NOT_STARTED = "NOT_STARTED";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";

    private static final BigDecimal PERCENT_NONE = BigDecimal.ZERO;
    private static final BigDecimal PERCENT_FULL = BigDecimal.valueOf(100);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "lesson_id", nullable = false)
    private Long lessonId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "progress_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal progressPercent;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** JPA-only constructor; do not call from application code. */
    protected LearningProgress() {
    }

    /**
     * Creates a row for a lesson the student has just opened: status
     * {@link #STATUS_IN_PROGRESS}, {@code started_at} = now, percent 0.
     *
     * @param userId   viewing student's id
     * @param lessonId opened lesson's id
     */
    public LearningProgress(Long userId, Long lessonId) {
        this.userId = userId;
        this.lessonId = lessonId;
        this.status = STATUS_IN_PROGRESS;
        this.progressPercent = PERCENT_NONE;
        this.startedAt = LocalDateTime.now();
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

    /** True when this lesson is marked COMPLETED for the student. */
    public boolean isCompleted() {
        return STATUS_COMPLETED.equals(status);
    }

    /**
     * Marks the lesson completed: status {@link #STATUS_COMPLETED},
     * {@code completed_at} = now, percent 100. Backfills {@code started_at}
     * when the row was created directly as COMPLETED (toggle without a
     * prior open).
     */
    public void markCompleted() {
        this.status = STATUS_COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.progressPercent = PERCENT_FULL;
        if (this.startedAt == null) {
            this.startedAt = LocalDateTime.now();
        }
    }

    /**
     * Reverts a completed lesson back to {@link #STATUS_IN_PROGRESS}:
     * clears {@code completed_at} and resets percent to 0.
     */
    public void revertToInProgress() {
        this.status = STATUS_IN_PROGRESS;
        this.completedAt = null;
        this.progressPercent = PERCENT_NONE;
    }

    // ── Getters ────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getLessonId() {
        return lessonId;
    }

    public String getStatus() {
        return status;
    }

    public BigDecimal getProgressPercent() {
        return progressPercent;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
