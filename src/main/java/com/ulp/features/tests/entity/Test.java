package com.ulp.features.tests.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code tests} table (V1) plus the V20 scheduling
 * columns. A {@code Test} is an online exam: a set of MCQ/MR questions with a
 * time window and a time model.
 *
 * <p>{@link SQLRestriction} filters soft-deleted rows out of every default
 * query. No {@code @Data} — explicit getters + business helpers only, matching
 * {@link com.ulp.features.flashcards.entity.FlashcardDeck}.
 */
@Entity
@Table(name = "tests")
@SQLRestriction("is_deleted = 0")
public class Test {

    public static final String TYPE_MOCK = "MOCK";
    public static final String TYPE_MODULE = "MODULE";
    public static final String TYPE_PRACTICE = "PRACTICE";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    public static final String TIME_MODE_FIXED_WINDOW = "FIXED_WINDOW";
    public static final String TIME_MODE_INDIVIDUAL = "INDIVIDUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "class_id")
    private Long classId;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "passing_score", precision = 5, scale = 2)
    private BigDecimal passingScore;

    @Column(name = "total_questions")
    private Integer totalQuestions = 0;

    @Column(name = "shuffle_questions", nullable = false)
    private boolean shuffleQuestions = false;

    @Column(name = "shuffle_options", nullable = false)
    private boolean shuffleOptions = false;

    @Column(nullable = false, length = 20)
    private String status = STATUS_DRAFT;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "start_at")
    private LocalDateTime startAt;

    @Column(name = "end_at")
    private LocalDateTime endAt;

    @Column(name = "time_mode", nullable = false, length = 20)
    private String timeMode = TIME_MODE_FIXED_WINDOW;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    /** JPA-only constructor; do not call from application code. */
    protected Test() {
    }

    /** Creates a bare test owned by {@code createdBy}; fields set via setters/helpers. */
    public Test(Long createdBy, String type) {
        this.createdBy = createdBy;
        this.type = type;
    }

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

    /** Marks the test soft-deleted; excluded from all default queries. */
    public void markDeleted() {
        this.deleted = true;
    }

    public boolean isPublished() {
        return STATUS_PUBLISHED.equals(status);
    }

    public boolean isPractice() {
        return TYPE_PRACTICE.equals(type);
    }

    public boolean isIndividualTimer() {
        return TIME_MODE_INDIVIDUAL.equals(timeMode);
    }

    // ── Mutators used by the lecturer author flow ──────────────────────

    public void setTitle(String title) { this.title = title; }
    public void setDescription(String description) { this.description = description; }
    public void setClassId(Long classId) { this.classId = classId; }
    public void setType(String type) { this.type = type; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
    public void setPassingScore(BigDecimal passingScore) { this.passingScore = passingScore; }
    public void setTotalQuestions(Integer totalQuestions) { this.totalQuestions = totalQuestions; }
    public void setShuffleQuestions(boolean shuffleQuestions) { this.shuffleQuestions = shuffleQuestions; }
    public void setShuffleOptions(boolean shuffleOptions) { this.shuffleOptions = shuffleOptions; }
    public void setStatus(String status) { this.status = status; }
    public void setStartAt(LocalDateTime startAt) { this.startAt = startAt; }
    public void setEndAt(LocalDateTime endAt) { this.endAt = endAt; }
    public void setTimeMode(String timeMode) { this.timeMode = timeMode; }

    // ── Getters ────────────────────────────────────────────────────────

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public Long getClassId() { return classId; }
    public String getType() { return type; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public BigDecimal getPassingScore() { return passingScore; }
    public Integer getTotalQuestions() { return totalQuestions; }
    public boolean isShuffleQuestions() { return shuffleQuestions; }
    public boolean isShuffleOptions() { return shuffleOptions; }
    public String getStatus() { return status; }
    public Long getCreatedBy() { return createdBy; }
    public LocalDateTime getStartAt() { return startAt; }
    public LocalDateTime getEndAt() { return endAt; }
    public String getTimeMode() { return timeMode; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public boolean isDeleted() { return deleted; }
}
