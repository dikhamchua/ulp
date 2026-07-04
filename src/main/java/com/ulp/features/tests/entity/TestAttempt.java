package com.ulp.features.tests.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code test_attempts} table (V1) plus the V20
 * {@code last_activity_at} heartbeat column. One attempt is one student's run
 * at one {@link Test}; a student has at most one {@code IN_PROGRESS} attempt per
 * test at a time.
 */
@Entity
@Table(name = "test_attempts")
public class TestAttempt {

    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_TIMED_OUT = "TIMED_OUT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "test_id", nullable = false)
    private Long testId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String status = STATUS_IN_PROGRESS;

    @Column(precision = 6, scale = 2)
    private BigDecimal score;

    @Column(name = "total_points", precision = 6, scale = 2)
    private BigDecimal totalPoints;

    @Column(name = "correct_count")
    private Integer correctCount;

    @Column(name = "total_questions")
    private Integer totalQuestions;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "time_spent_seconds")
    private Integer timeSpentSeconds;

    @Column(name = "last_activity_at")
    private LocalDateTime lastActivityAt;

    /** JPA-only constructor; do not call from application code. */
    protected TestAttempt() {
    }

    public TestAttempt(Long testId, Long userId) {
        this.testId = testId;
        this.userId = userId;
        this.status = STATUS_IN_PROGRESS;
        this.startedAt = LocalDateTime.now();
        this.lastActivityAt = this.startedAt;
    }

    @PrePersist
    void onPersist() {
        if (startedAt == null) startedAt = LocalDateTime.now();
    }

    // ── Business helpers ───────────────────────────────────────────────

    public boolean isInProgress() {
        return STATUS_IN_PROGRESS.equals(status);
    }

    public void touchActivity() {
        this.lastActivityAt = LocalDateTime.now();
    }

    /** Records the graded totals and closes the attempt with the given status. */
    public void finalizeGrade(BigDecimal score, BigDecimal totalPoints, int correctCount,
                              int totalQuestions, int timeSpentSeconds, String finalStatus) {
        this.score = score;
        this.totalPoints = totalPoints;
        this.correctCount = correctCount;
        this.totalQuestions = totalQuestions;
        this.timeSpentSeconds = timeSpentSeconds;
        this.submittedAt = LocalDateTime.now();
        this.status = finalStatus;
    }

    // ── Getters ────────────────────────────────────────────────────────

    public Long getId() { return id; }
    public Long getTestId() { return testId; }
    public Long getUserId() { return userId; }
    public String getStatus() { return status; }
    public BigDecimal getScore() { return score; }
    public BigDecimal getTotalPoints() { return totalPoints; }
    public Integer getCorrectCount() { return correctCount; }
    public Integer getTotalQuestions() { return totalQuestions; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public Integer getTimeSpentSeconds() { return timeSpentSeconds; }
    public LocalDateTime getLastActivityAt() { return lastActivityAt; }
}
